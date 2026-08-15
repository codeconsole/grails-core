<!--
SPDX-License-Identifier: Apache-2.0

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Request-processing baseline

Numbers for the Grails HTTP request-processing hot path, so that changes to it can be measured
rather than asserted. Re-run this on the same machine before and after a change; do not compare
across machines or JDK builds.

## Running

The benchmarks are opt-in - they are not attached to `build` or `check`.

```bash
# Full run (2 forks, 5 warmup + 5 measurement iterations of 1s each, ~7 minutes)
./gradlew :grails-web-benchmarks:jmh

# Any JMH option can be passed through; a smoke run, and a filter, look like this
./gradlew :grails-web-benchmarks:jmh -PjmhArgs="-wi 1 -i 1 -f 1 -w 1s -r 1s"
./gradlew :grails-web-benchmarks:jmh -PjmhArgs="GrailsWebRequestBenchmark"
```

Results are also written as JSON to `grails-web-benchmarks/build/reports/jmh/results.json`.

## What is measured

| Benchmark | Path under measurement |
|---|---|
| `GrailsWebRequestBenchmark.construct` | `new GrailsWebRequest(request, response, servletContext)` - the whole per-request bind, including however `GrailsApplicationAttributes` is obtained |
| `GrailsWebRequestBenchmark.paramsOnFreshRequest` | construction plus the first `getParams()`, i.e. what an action pays the first time it reads `params` |
| `GrailsWebRequestBenchmark.paramsCached` | the memoised `getParams()` fast path |
| `GrailsWebRequestBenchmark.paramsRebuilt` | `resetParams()` + `getParams()` - isolates the deep clone of an already-built `GrailsParameterMap` |
| `MultipartResolutionBenchmark.resolvePlain` | `WebUtils.resolveMultipartRequest` on a plain request |
| `MultipartResolutionBenchmark.resolvePlainBehindTwoWrappers` | the same, two `HttpServletRequestWrapper`s deep (the shape a filter chain produces) |
| `MultipartResolutionBenchmark.resolveMultipartBehindTwoWrappers` | a resolved multipart request found by walking the wrapper chain |
| `MultipartResolutionBenchmark.resolveMultipartByAttribute` | a resolved multipart request found through the request attribute fallback |
| `UrlMappingBenchmark.matchCachedHit` | `DefaultUrlMappingsHolder.match(uri)` for a URI already in the holder's Caffeine cache |
| `UrlMappingBenchmark.matchRestfulUriCacheMiss` | the same, rotating over 4096 distinct `/api/books/{id}` URIs so the 1000-entry cache mostly misses |
| `UrlMappingBenchmark.matchDefaultMappingUriCacheMiss` | the same, for URIs only the catch-all `"/$controller/$action?/$id?"` mapping can serve |
| `RequestPropertyAccessBenchmark.groovyUnknownProperty` | `request.someAttribute` from Groovy - metaclass miss into `HttpServletRequestExtension.getProperty`, which does a further `metaClass.getMetaProperty(name)` lookup |
| `RequestPropertyAccessBenchmark.groovyGetterBackedProperty` | `request.method` from Groovy - metaclass hit on a real getter |
| `RequestPropertyAccessBenchmark.groovyAttributeCall` | `request.getAttribute('someAttribute')` from Groovy |
| `RequestPropertyAccessBenchmark.javaGetAttribute` | the same attribute read from Java - the floor |
| `ControllerActionBenchmark.plainAction` | `GrailsControllerClass.invoke` on an action of a controller declaring no `allowedMethods` - the shape most actions have |
| `ControllerActionBenchmark.restrictedAction` | the same, for a controller that does declare `allowedMethods`, so the check and its bookkeeping both run |
| `ControllerActionBenchmark.commandObjectAction` | the same, for an action taking a command object, whose generated wrapper instantiates, binds and validates one |
| `InterceptorChainBenchmark.oneInterceptorNoOpRegistry` | `GrailsInterceptorHandlerInterceptorAdapter.preHandle` + `postHandle` with one matched interceptor and `ObservationRegistry.NOOP` - the dominant production shape |
| `InterceptorChainBenchmark.threeInterceptorsNoOpRegistry` | the same, with three matched interceptors of distinct classes |
| `InterceptorChainBenchmark.oneInterceptorObservingRegistry` | the same as the one-interceptor case, against a registry with a handler registered |
| `InterceptorChainBenchmark.threeInterceptorsObservingRegistry` | the same as the three-interceptor case, against a registry with a handler registered |
| `ControllerMappingCollectionBenchmark.oneCandidate` | `GrailsControllerUrlMappings.matchAll` for a URI only one mapping serves - the delegate's Caffeine cache always hits, so this is the uncached `collectControllerMappings` wrapper |
| `ControllerMappingCollectionBenchmark.twoCandidates` | the same, for `/api/books/42`, which a `resources` mapping and the catch-all default mapping both serve |
| `ControllerMappingCollectionBenchmark.fourCandidates` | the same, against `BenchmarkOverlappingUrlMappings`, whose patterns deliberately overlap so the per-candidate work can be read off |

The controllers used by `ControllerActionBenchmark` and `ControllerMappingCollectionBenchmark` are
compiled at setup by a `GrailsAwareClassLoader` running the real `ControllerActionTransformer`, so
the bytecode invoked is the bytecode a Grails application would run. `ControllerActionBenchmark`
prints, once per fork, how many request-attribute operations one invocation of each action performs,
measured outside the timed region - that count is the direct evidence of what the generated code
does, independent of the timing.

The mapping set used by `UrlMappingBenchmark` is in
`src/jmh/groovy/org/apache/grails/benchmarks/web/BenchmarkUrlMappings.groovy`: four static URLs,
five `resources` blocks, three multi-token dynamic URLs, two method-scoped mappings, the catch-all
default mapping and the two error mappings.

Everything runs against `org.springframework.mock.web.Mock*` objects with a
`StaticWebApplicationContext` registered into the `MockServletContext`, so no servlet container is
needed and the benchmarks still take the normal code path rather than a missing-context error path.

## Baseline

Command:

```bash
export GRADLE_OPTS="-Xms2G -Xmx5G"
./gradlew :grails-web-benchmarks:jmh
```

| | |
|---|---|
| Date | 2026-08-14 |
| Branch / commit | `refactor/multipart-spring-delegation-8.0.x` @ `ba29b546d4` |
| JDK | `21.0.7-librca` - OpenJDK 64-Bit Server VM, 21.0.7+9-LTS (the `.sdkmanrc` pin) |
| JMH | 1.37 |
| Gradle | 9.6.0 |
| Machine | Apple M4 Max, 16 cores, 48 GB, macOS 26.5 (25F71), arm64 |
| JMH config | `AverageTime`, 2 forks, 5x1s warmup + 5x1s measurement, 1 thread |
| Forked JVM options | `--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED` |
| Wall clock | 5m 13s |

```
Benchmark                                                       Mode  Cnt     Score    Error  Units
GrailsWebRequestBenchmark.construct                             avgt   10    12.082 ±  0.077  ns/op
GrailsWebRequestBenchmark.paramsCached                          avgt   10     0.483 ±  0.041  ns/op
GrailsWebRequestBenchmark.paramsOnFreshRequest                  avgt   10   573.237 ± 13.218  ns/op
GrailsWebRequestBenchmark.paramsRebuilt                         avgt   10   305.401 ±  1.704  ns/op
MultipartResolutionBenchmark.resolveMultipartBehindTwoWrappers  avgt   10     3.455 ±  0.016  ns/op
MultipartResolutionBenchmark.resolveMultipartByAttribute        avgt   10     2.344 ±  0.026  ns/op
MultipartResolutionBenchmark.resolvePlain                       avgt   10     1.660 ±  0.099  ns/op
MultipartResolutionBenchmark.resolvePlainBehindTwoWrappers      avgt   10     5.671 ±  0.043  ns/op
RequestPropertyAccessBenchmark.groovyAttributeCall              avgt   10     1.989 ±  0.010  ns/op
RequestPropertyAccessBenchmark.groovyGetterBackedProperty       avgt   10     0.935 ±  0.028  ns/op
RequestPropertyAccessBenchmark.groovyUnknownProperty            avgt   10    94.855 ±  4.904  ns/op
RequestPropertyAccessBenchmark.javaGetAttribute                 avgt   10     1.515 ±  0.045  ns/op
UrlMappingBenchmark.matchCachedHit                              avgt   10     2.531 ±  0.008  ns/op
UrlMappingBenchmark.matchDefaultMappingUriCacheMiss             avgt   10  1964.212 ± 78.179  ns/op
UrlMappingBenchmark.matchRestfulUriCacheMiss                    avgt   10  1501.724 ± 86.770  ns/op
```

### Reading the numbers

* Binding a request costs 12 ns. At this commit `GrailsWebRequest` no longer builds a
  `GrailsApplicationAttributes` per request (`959448b51b` caches it in the servlet context), so
  what remains is the servlet-context attribute read, the identity check against the current
  `ApplicationContext`, and the `DispatcherServletWebRequest` super constructor.
* The first `params` read costs ~573 ns, of which ~305 ns is the deep clone of the already-built
  `GrailsParameterMap` - i.e. over half the cost of `getParams()` is the clone, not the parse.
  Every subsequent read is free (0.5 ns). The clone is the largest single remaining item on this
  path.
* Multipart resolution is cheap in every shape measured (1.7-5.7 ns), including the plain-request
  miss that every `GrailsParameterMap` construction pays. Two wrappers cost ~4 ns more than none,
  so unwrapping depth is not worth optimising.
* URL matching is entirely a cache story: 2.5 ns on a cache hit versus 1.5-2.0 us when the URI is
  not cached. An application whose URL space is larger than the holder's 1000-entry cache (anything
  with ids in the path, i.e. most REST applications) pays the uncached number on most requests.
  This is by far the largest number in the set.
* `request.someAttribute` from Groovy costs ~95 ns against ~1.5 ns for the equivalent Java
  `getAttribute` - a ~60x multiplier, because the property is unknown to the request class and the
  extension's own `metaClass.getMetaProperty(name)` lookup runs on every access. Properties that
  *are* backed by a getter (`request.method`) resolve through the normal metaclass path in ~1 ns.

### Caveats

* Do not compare these numbers to a run on a different machine, JDK, or JMH version.
* Run on an otherwise idle machine, and check the error column. Anything whose error term is a
  large fraction of its score is noise, not a result.
* The allocation-heavy benchmarks (`paramsOnFreshRequest`, `paramsRebuilt`, the two cache-miss
  matches) are the ones most sensitive to that noise. Add `-PjmhArgs="-prof gc"` when a change is
  expected to move allocation rather than instruction count.
* `matchRestfulUriCacheMiss` / `matchDefaultMappingUriCacheMiss` rotate over 4096 URIs against a
  1000-entry cache, so they are miss-dominated but not miss-only, and they include the cost of the
  cache insert and eviction. They measure "cold URL space", not "matching with the cache removed".

### Paired before/after against 8.0.x

Two full suites run back to back on an idle machine, same JDK, same JMH, same command
(`./gradlew --no-daemon :grails-web-benchmarks:jmh`, i.e. the annotated defaults: 2 forks,
5x1s warmup + 5x1s measurement). "before" is `8.0.x` at `a83f87480e` with this module's `src/jmh`
tree copied in; "after" is `refactor/multipart-spring-delegation-8.0.x` at `abc0316b0e`. The
`MultipartResolutionBenchmark` benchmarks exist only on the branch, because
`WebUtils.resolveMultipartRequest` does.

| Benchmark | before ns/op | after ns/op | delta |
|---|---|---|---|
| `ControllerActionBenchmark.plainAction` | 34.795 ± 1.589 | 3.592 ± 0.033 | -89.7% |
| `ControllerActionBenchmark.restrictedAction` | 63.066 ± 6.665 | 59.239 ± 8.475 | noise |
| `ControllerActionBenchmark.commandObjectAction` | 28932.163 ± 730.856 | 28312.746 ± 120.241 | noise |
| `InterceptorChainBenchmark.oneInterceptorNoOpRegistry` | 295.293 ± 10.920 | 127.541 ± 1.441 | -56.8% |
| `InterceptorChainBenchmark.threeInterceptorsNoOpRegistry` | 1116.942 ± 49.219 | 545.608 ± 15.400 | -51.2% |
| `InterceptorChainBenchmark.oneInterceptorObservingRegistry` | 543.227 ± 11.358 | 380.574 ± 300.017 | -30% (one disturbed fork; steady state ~310) |
| `InterceptorChainBenchmark.threeInterceptorsObservingRegistry` | 1972.929 ± 35.609 | 1134.393 ± 14.594 | -42.5% |
| `ControllerMappingCollectionBenchmark.oneCandidate` | 364.732 ± 9.122 | 361.294 ± 4.391 | noise |
| `ControllerMappingCollectionBenchmark.twoCandidates` | 609.936 ± 7.207 | 577.452 ± 9.838 | -5.3% |
| `ControllerMappingCollectionBenchmark.fourCandidates` | 1234.752 ± 25.852 | 1210.762 ± 28.863 | noise |
| `GrailsWebRequestBenchmark.construct` | 16.283 ± 0.184 | 11.710 ± 0.055 | -28.1% |
| `GrailsWebRequestBenchmark.paramsCached` | 0.459 ± 0.023 | 0.455 ± 0.027 | noise |
| `GrailsWebRequestBenchmark.paramsOnFreshRequest` | 561.768 ± 3.046 | 526.189 ± 58.196 | noise |
| `GrailsWebRequestBenchmark.paramsRebuilt` | 303.642 ± 2.341 | 298.861 ± 3.283 | -1.6% |
| `UrlMappingBenchmark.matchCachedHit` | 2.522 ± 0.030 | 2.486 ± 0.012 | -1.4% |
| `UrlMappingBenchmark.matchRestfulUriCacheMiss` | 1549.992 ± 21.144 | 1287.754 ± 6.441 | -16.9% |
| `UrlMappingBenchmark.matchDefaultMappingUriCacheMiss` | 1881.323 ± 40.402 | 1595.361 ± 18.018 | -15.2% |
| `RequestPropertyAccessBenchmark.groovyUnknownProperty` | 93.464 ± 2.893 | 87.616 ± 7.533 | noise |
| `RequestPropertyAccessBenchmark.groovyGetterBackedProperty` | 0.906 ± 0.023 | 0.908 ± 0.034 | noise |
| `RequestPropertyAccessBenchmark.groovyAttributeCall` | 1.983 ± 0.038 | 1.985 ± 0.030 | noise |
| `RequestPropertyAccessBenchmark.javaGetAttribute` | 1.476 ± 0.025 | 1.483 ± 0.010 | noise |

Nothing regressed. The request-attribute counts printed by `ControllerActionBenchmark` are the
independent confirmation of the controller result: an action of a controller with no
`allowedMethods` goes from `getAttribute=2 setAttribute=1 removeAttribute=1` to nothing at all,
while a controller that does declare `allowedMethods` is unchanged at `2/1/1`, which is what
"controllers that use allowedMethods generate byte-identical code" means in practice.

`collectControllerMappings` is byte-identical between the two commits, so the mapping-collection
numbers are a measurement of what is still there rather than of a change: 361 ns for one candidate
and 577 ns for the two a REST URI typically produces, on top of the 2.5 ns the URL match itself
costs once cached. Roughly 300 ns of each candidate is `webRequest.resetParams()`, which is the same
clone `paramsRebuilt` measures at 299 ns.

### Earlier run (not a clean before/after)

An earlier run of the same benchmarks, on `0709bf4f38` - before `959448b51b` (attributes cached per
servlet context), `ab68e688e9` (no defensive copy of the servlet parameter map) and `5511f09507`
landed - produced:

```
GrailsWebRequestBenchmark.construct                   avgt   10    27.106 ±   12.338  ns/op
GrailsWebRequestBenchmark.paramsCached                avgt   10     1.773 ±    2.192  ns/op
GrailsWebRequestBenchmark.paramsOnFreshRequest        avgt   10  2285.017 ± 1369.898  ns/op
GrailsWebRequestBenchmark.paramsRebuilt               avgt   10  1765.546 ±  235.900  ns/op
UrlMappingBenchmark.matchRestfulUriCacheMiss          avgt   10  2496.115 ± 3011.989  ns/op
```

Treat this as an illustration that the harness responds to the code under it, **not** as a
before/after measurement: that run was taken on a busy machine, and its error bars are wide enough
(up to 120% of score) that only `paramsRebuilt` moved by more than its own error. Producing a real
before/after means running both commits back to back on an idle machine.

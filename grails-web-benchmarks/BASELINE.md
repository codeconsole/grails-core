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
| `GrailsWebRequestBenchmark.construct` | `new GrailsWebRequest(request, response, servletContext)` - reflectively constructs `DefaultGrailsApplicationAttributes` per request |
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

The mapping set used by `UrlMappingBenchmark` is in
`src/jmh/groovy/org/apache/grails/benchmarks/web/BenchmarkUrlMappings.groovy`: four static URLs,
five `resources` blocks, three multi-token dynamic URLs, two method-scoped mappings, the catch-all
default mapping and the two error mappings.

Everything runs against `org.springframework.mock.web.Mock*` objects with a
`StaticWebApplicationContext` registered into the `MockServletContext`, so no servlet container is
needed and the benchmarks still take the normal code path rather than a missing-context error path.

## Baseline

PLACEHOLDER_ENVIRONMENT

PLACEHOLDER_TABLE

PLACEHOLDER_NOTES

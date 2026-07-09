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

# enable-mvc-check

A dedicated Grails functional test application that pins down the behavior of
`@EnableWebMvc` in a Grails application, so that behavior stays the same whether the
annotation is auto-injected by the framework (Grails 7) or declared explicitly by the
application (Grails 8 onwards).

## Background

Grails 7 auto-injected `@EnableWebMvc` into every Application class, which suppresses
Spring Boot's `WebMvcAutoConfiguration`. Grails 8 stops injecting it, so Boot's MVC
auto-configuration becomes active by default. An application that depended on the old
behavior can opt back in by declaring `@EnableWebMvc` on its Application class — which is
exactly what this app does (`grails-app/init/enablemvccheck/Application.groovy`).

The integration tests assert the `@EnableWebMvc` behavior and act as a regression suite
for the opt-in path: they must keep passing across the removal of the auto-injection,
proving the behaviors stay the same with or without framework injection of the annotation.

## Behaviors pinned by this suite

The suite asserts user-observable HTTP behavior only (bean registrations are an
implementation detail — what matters for regressions is the behavior change). With
`@EnableWebMvc` present, Boot's `WebMvcAutoConfiguration` is suppressed, so:

| Behavior | Test |
|---|---|
| Form-encoded `DELETE` bodies are NOT parsed into request parameters (no form-content filter). `PUT`/`PATCH` bodies are parsed by Grails itself (`GrailsParameterMap`) and `POST` by the container, with or without the annotation — those are controls | `WebMvcDefaultsFunctionalSpec` |
| `classpath:/public` resources are NOT served at the context root (no Boot catch-all static-resource handler) | `WebMvcDefaultsFunctionalSpec` |
| No static `index.html` welcome page for the unmapped root path (no `WelcomePageHandlerMapping`) | `WebMvcDefaultsFunctionalSpec` |
| Locale resolution follows `Accept-Language` and Grails' `?lang=` switching is inert (`@EnableWebMvc` registers an `AcceptHeaderLocaleResolver`, so grails-i18n's `SessionLocaleResolver` backs off). This intentionally differs from Grails 7, where the i18n plugin overrode the annotation's resolver via bean-definition overriding — framework beans now back off cleanly instead of overriding (upgrade notes section 31.4) | `WebMvcDefaultsFunctionalSpec` |

Removing `@EnableWebMvc` from this app's Application class makes every non-control
feature method fail — those failures are precisely the Grails 8 behavior changes
documented in the upgrade notes (section "Spring Boot WebMvcAutoConfiguration side
effects").

This app intentionally has no spring-security-core dependency (that plugin registers its
own `FormContentFilter`, which would mask the form-content behavior) and no `/` URL
mapping (so welcome-page handling is observable).

## Running

```bash
./gradlew :grails-test-examples-enable-mvc-check:integrationTest
```

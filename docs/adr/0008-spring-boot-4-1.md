# 8. Spring Boot 4.1 is the supported line

- **Status:** Accepted
- **Date decided:** 2026-10-30
- **Date recorded:** 2026-10-30
- **Supersedes:** the version-policy sentence in [0006](0006-dependency-maintenance-workflow.md)
  ("stay on the current supported Spring Boot 3.x line; Boot 4.x deliberately not taken")

## Context

[0006](0006-dependency-maintenance-workflow.md) left the project on Spring Boot 3.5 because
Boot 4 was a Spring Framework 7 / Jackson 3 migration, and that did not fit inside a bounded
security pass. The sibling `notification-platform` has since been built on Boot 4.1 (4.1.1
at its last commit). This API was the one still on 3.5.16.

The public contract the Angular UI depends on — routes, status codes, the `ApiResponse`
body, bearer auth — had to stay put. [0002](0002-dependency-version-overrides-expire.md)
also applies: the first step of a parent bump is to re-check every version override, because
a pin that the parent has overtaken becomes a silent downgrade.

## Decision

**Move the parent to Spring Boot 4.1.2**, the current 4.1 patch, one patch ahead of
`notification-platform`'s 4.1.1. Stay on Java 21.

What had to change for the application to keep behaving the same way:

- **Starters.** `spring-boot-starter-web` → `spring-boot-starter-webmvc`, and
  `spring-boot-starter-oauth2-resource-server` →
  `spring-boot-starter-security-oauth2-resource-server`. The old names still resolve but
  are deprecated. Tests gained `spring-boot-starter-webmvc-test` and
  `spring-boot-starter-security-test`, because `@WebMvcTest`, `@AutoConfigureMockMvc` and
  `@WithMockUser` are no longer pulled in by `spring-boot-starter-test`. The annotations
  themselves moved to `org.springframework.boot.webmvc.test.autoconfigure`.
- **Jackson 3.** Boot 4's HTTP stack uses `tools.jackson`. The hand-built Jackson 2
  `ObjectMapper` bean was removed so it no longer shadows Boot's `JsonMapper`.
  `spring.jackson.use-jackson2-defaults=true` keeps that mapper's defaults aligned with
  the Jackson 2 behaviour the UI already parses. The Jolokia `RestTemplate` registers a
  `JacksonJsonHttpMessageConverter` that still accepts `text/plain`, which is how the
  broker's Jolokia bridge labels its JSON. The JMS converter is
  `JacksonJsonMessageConverter` with the same text target type as before.
- **Spring Security 7.** `DaoAuthenticationProvider` now takes the `UserDetailsService`
  in its constructor. A successful password login also carries a `FACTOR_PASSWORD`
  authority. That is not an application role, so login copies only `ROLE_*` authorities
  into the token and the response. The filter chain, the `roles` claim, and the role
  prefix are otherwise unchanged.
- **Testcontainers 2** (managed by Boot 4.1) renamed `junit-jupiter` to
  `testcontainers-junit-jupiter`. The container APIs these tests call did not move.
- **Overrides, re-checked before anything else.** Dropped, because keeping them would
  downgrade or do nothing: Tomcat 10.1.57 (parent is Tomcat 11.0.27, and 10.1 cannot
  serve Servlet 6.1), Netty 4.1.136 (parent is 4.2.19), commons-lang3 3.20.0 (parent
  matches), Lombok 1.18.38 (parent is 1.18.48), Mockito 5.18.0 and the obsolete
  `mockito-inline` artifact (parent is 5.23.0; the inline maker is in `mockito-core`).
  `commons-beanutils` 1.11.0 left `dependencyManagement` because Artemis 2.55.0 declares
  that version itself. **Kept:** `artemis.version` 2.55.0, because the parent manages
  2.53.0. Boot 4.1 imports `org.apache.artemis:artemis-bom`, so the property still applies
  to the post-TLP coordinates. Drop it when the parent manages >= 2.55.0.

## Consequences

The HTTP API is the same. JSON is produced by Jackson 3 with Jackson 2 defaults, not by a
raw `new ObjectMapper()`. For the DTOs this app actually returns (strings, booleans, maps)
that is the same document the UI parsed before.

Jackson 2 databind 2.21.7 remains on the classpath transitively via springdoc. The
`CVE-2026-54515` suppression is now limited to `jackson-databind-2.*` so it cannot hide a
finding on the Jackson 3 jar. The log4j and swagger-ui suppressions were re-checked against
the new versions; their reasons (wrong artifact, and docs served only on the dev profile)
still hold. A full NVD scan still needs the `NVD_API_KEY` secret and is the nightly job's
responsibility.

`artemis.version` 2.55.0 will become a downgrade on a future parent bump, exactly as
[0002](0002-dependency-version-overrides-expire.md) describes. The pom says when to delete it.

> **Update (2026-10-30): dependency bump on top of 4.1.2.** The first full NVD scan of the
> Boot 4.1.2 classpath failed the gate on `artemis-core-client` 2.55.0: CVE-2026-57967,
> -49364, -67593 (critical) and -49362, -49363 (high). All are published as affecting
> Artemis 2.50.0 through 2.56.0, fixed in 2.57.0. They are broker-side protocol flaws matched
> onto the client jar by CPE, so **`artemis.version` moved 2.55.0 → 2.57.0** (fix over
> suppress), and its expiry is now "parent manages >= 2.57.0". The unit suite,
> `MessageServiceIT` and the manual JMS pass against the 2.31.2 broker all passed on 2.57.0.
> The same advisories list ActiveMQ Artemis 1.0.0 through 2.44.0 as affected too, which
> covers the 2.31.2 broker image; the client bump does nothing for the broker.
> **Surefire / Failsafe 3.6.1** and **versions-maven-plugin 2.23.0** were also taken ahead
> of the parent, through its own version properties, each with an `EXPIRY` in the pom.

## What was left alone

- **The broker image stays `apache/activemq-artemis:2.31.2`** in `compose.yaml` and
  `MessageServiceIT`. That pin is the dev-stack pairing, not a library the parent manages.
  Bumping the broker is a compatibility exercise of its own (see the manual pass in
  `CLAUDE.md`), not part of moving the framework.
- **Surefire / Failsafe stay on the parent's 3.5.6**, not the newer 3.6.1 published a few
  days before this change. The explicit 3.2.5 pin was the stale part.
  > Update (2026-10-30): taken to 3.6.1 after all; see the update under Consequences.
- **Maven stays on the 3.9 line** (wrapper and the Docker build image moved 3.9.9 → 3.9.16).
  Maven 4 is still a release candidate.
- **`RestTemplate` was not rewritten to `RestClient`.** Boot 4 did not require it, and the
  Jolokia client already has the behaviour it needs.
- **Boot 4.2** was not taken. 4.2.0 was a release candidate on this date; 4.1 is the line
  `notification-platform` uses.
- **Unused test dependencies were removed rather than bumped:** `embedded-redis` (only
  referenced by the unused `TestRedisServer` helper) and `artemis-junit-5` (no references).
  Neither was on a test classpath that a test actually exercised.

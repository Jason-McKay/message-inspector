# Architecture Decision Records

Short records of decisions that were **not obvious**, written so the reasoning survives
the people who made it. Each one states the context, the decision, and what it cost —
including the parts that did not work out.

Format is Nygard-style, numbered, append-only: supersede a record rather than editing it
once it is accepted.

| # | Title | Status |
|---|---|---|
| [0001](0001-two-broker-access-paths.md) | Two broker access paths: JMS for messages, Jolokia for queue metadata | Accepted |
| [0002](0002-dependency-version-overrides-expire.md) | Every dependency version override carries an expiry | Accepted |
| [0003](0003-redis-over-localstorage-for-preferences.md) | Last-used-queue preference lives in Redis, keyed by user, not in the browser | Accepted |
| [0004](0004-layered-package-structure.md) | Packages named by role: `integration/` for external systems, no speculative layers | Accepted |
| [0005](0005-jwt-bearer-over-cookie-session.md) | Authentication is a stateless JWT bearer token, not a cookie session | Accepted |
| [0006](0006-dependency-maintenance-workflow.md) | OWASP Dependency-Check is the dependency-maintenance gate | Accepted |
| [0007](0007-ci-cd-pipeline.md) | CI runs fast unit tests per push; OWASP and reproducible builds are separate | Accepted |
| [0008](0008-spring-boot-4-1.md) | Spring Boot 4.1 is the supported line | Accepted |

0001–0006 were written retrospectively, while the reasoning was still recoverable: 0001–0002
on 2026-07-21, and 0003–0006 during the Phase 6 documentation pass on 2026-07-23. They are
reconstructions, and the "date decided" line in each says when the decision was actually
taken. **0007 is the exception** — it was written contemporaneously, as Phase 7's CI/CD
decisions were made on 2026-07-24. **0008** records the later move from Boot 3.5 to Boot 4.1
(2026-10-30) and supersedes the version-policy sentence in 0006.

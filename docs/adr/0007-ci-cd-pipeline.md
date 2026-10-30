# 7. CI runs fast unit tests per push; OWASP and reproducible builds are separate

- **Status:** Accepted
- **Date decided:** 2026-07-24 (Phase 7, the CI/CD pass)
- **Date recorded:** 2026-07-24 (contemporaneous — unlike 0001–0006, this one was written
  as the decision was taken)

## Context

Phase 7 adds continuous integration and a reproducible build to a codebase that already had
a clean PR-per-phase workflow but no automation: nothing ran on push, and "it builds on my
machine" was the only guarantee. The flagship was already portfolio-ready at Phase 6, so
this phase is upside — a green pipeline badge and a one-command run — not a gap to close.

Three things had to be decided rather than defaulted, because each trades speed against
coverage and the wrong default makes the pipeline either slow or dishonest:

1. **How much of the test suite runs per push** — `./mvnw test` (unit + `@WebMvcTest`) or
   `./mvnw verify` (adds `MessageServiceIT` against a real broker container).
2. **How often the OWASP gate runs** — every push or on a schedule.
3. **What the Docker deliverable is** — a build artifact, a runnable local stack, or a
   published image.

## Decision

**Per push, run the fast suite only. Run the slow, external-dependency work separately.**

- **CI (`ci.yml`): `./mvnw test` on every push/PR.** Unit tests plus `@WebMvcTest` slices.
  It needs Docker on the runner — a couple of tests spin up Redis via Testcontainers — but
  **not** the shared `infrastructure/` stack, and `ubuntu-latest` ships Docker, so no
  `services:` block is required. `MessageServiceIT` (failsafe/`verify`) is deliberately
  **not** in CI: it is the heavier broker-backed path, kept as a local and future step.
- **Security (`security.yml`): the OWASP gate runs nightly (cron) + `workflow_dispatch`,
  not per push.** The gate is already real (`failBuildOnCVSS=7`, see
  [0006](0006-dependency-maintenance-workflow.md)); running it on every push would tax every
  build with a scan whose cold run is ~6 min and whose warm run is ~2 min. Nightly keeps
  the NVD cache warm (`actions/cache`) and surfaces newly-disclosed CVEs without gating
  unrelated pushes. The NVD API key comes from the `NVD_API_KEY` **repo secret**
  (`-Dnvd.api.key=...`), never committed.
- **Docker: a multi-stage `Dockerfile` + a self-contained `compose.yaml`** (app + Artemis +
  Redis, one command: `docker compose up --build`). **No image is published to a registry** —
  the deliverable is a reproducible local run, not a distribution.
- **Frontend CI:** Node pinned to **20.11.1** (`.nvmrc`; Angular 16 rejects Node 24), headless
  Karma (`npx ng test --watch=false --browsers=ChromeHeadless`), then `npm run build`.
  > **Update (2026-10-30): the frontend is now Angular 22.2.4 on Node 24.18.0.** Angular 20+
  > rejects the 20.11.1 pin, so `.nvmrc` moved to 24.18.0. CI now runs `npm run lint`
  > (angular-eslint) before the headless tests, and `npm run build` writes to
  > `dist/message-inspector-ui/browser/` (the esbuild application builder's layout).

**Enabling fix — external-system config must be property-driven, not hardcoded.** `RedisConfig`
had hardcoded `localhost:6379`, which made `spring.data.redis.*` inert. That broke *both*
Phase 7 deliverables — `./mvnw test` failed on a fresh CI runner (no Redis on localhost) and
a container could not reach Redis by service name — and had quietly made
`RedisQueueRepositoryIntegrationTest` depend on a live localhost Redis. The custom
`redisConnectionFactory()` bean was removed so Spring Boot auto-configures the factory from
properties. This is the same principle Phase 4 applied to `ArtemisConfig` (which reads
`spring.artemis.*`): anything talking to an external system is configured, never pinned.

## Consequences

**Accepted costs / current limitations:**

- **Broker compatibility is still not covered by CI.** `MessageServiceIT` does not run there,
  and no unit test opens a JMS connection, so a green pipeline says nothing about whether the
  client can talk to the broker. Client/broker version jumps still need the manual pass
  recorded in `CLAUDE.md` — consistent with [0006](0006-dependency-maintenance-workflow.md).
- **The security job depends on an out-of-band secret.** Without `NVD_API_KEY` set on the
  repo, the scan is NVD-rate-limited to the point of being unusable. This is documented in
  the README and the roadmap; the secret was added 2026-07-24.
- **The first scan builds the ~600 MB NVD cache** (~6 min cold, observed on the first CI
  run 2026-07-25 — the full 370k-record NVD pull); every run after is ~2 min off the cache.
  (This beat the ~60 min figure from the Phase 2 *local* first scan noted in
  [0006](0006-dependency-maintenance-workflow.md) — a different machine, and the NVD API has
  since sped up.)
- **No published image.** Running the containerised app elsewhere means building it there.
  Publishing to a registry (e.g. GHCR) is a natural, deliberately-deferred addition.
- **Frontend headless Chrome is new ground in CI.** The recipe is the established local one;
  if CI Chrome ever needs `--no-sandbox` it would require adding a `karma.conf.js`. (The
  first CI run was clean, so this did not materialise.)

**Why it is right:** push feedback stays fast and honest — every push proves the code
compiles and the fast suite is green — while the expensive, externally-dependent work
(vulnerability scanning against live NVD data) runs on a cadence that matches how often its
inputs actually change. The one-command compose run makes the "reproducible build" claim
concrete without taking on the operational surface of a registry.

## Alternatives considered

- **Run `./mvnw verify` in CI.** Rejected for now: `MessageServiceIT` needs a broker
  container, making every push slower for a path better exercised by the documented manual
  broker pass. A natural future addition as a separate, non-blocking job.
- **Run OWASP on every push.** Rejected: it would add scan time to every build and couple
  push feedback to NVD availability, for a gate whose inputs change daily, not per commit.
- **Publish a container image to GHCR on merge.** Deferred, not rejected — it needs registry
  auth/permissions and is distribution, which is beyond this phase's "reproducible local run"
  goal. Pairs naturally with a future deploy target.
- **Add a Redis service container to CI instead of fixing `RedisConfig`.** Rejected: it would
  paper over a real bug (inert `spring.data.redis.*`) that also broke the containerised run,
  which the compose deliverable made non-optional to fix properly.

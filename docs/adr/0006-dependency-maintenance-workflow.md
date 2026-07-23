# 6. OWASP Dependency-Check is the dependency-maintenance gate

- **Status:** Accepted
- **Date decided:** 2026-07-20 (Phase 2, the security/dependency pass)
- **Date recorded:** 2026-07-23, during the Phase 6 documentation pass

## Context

The project needed a repeatable way to know what CVEs its dependencies carry and to keep
that number honest over time — not a one-off audit, but a mechanism that stays useful.
[0002](0002-dependency-version-overrides-expire.md) records the *lesson* Phase 2 taught
about individual overrides going stale; this record is about the *workflow* that surrounds
it — how scanning, triage, and the eventual build gate fit together.

A static read of the pom cannot answer "is this exploitable" or "is there a fix version".
Only an actual scan against current vulnerability data can, and only if it is cheap enough
to re-run that it actually gets re-run.

## Decision

**OWASP Dependency-Check is the standing dependency-maintenance gate**, run as a single
command and read from a machine-friendly report:

- `./mvnw dependency-check:check` for the HTML report; add `-Dformat=ALL` and triage from
  the **CSV** (`DependencyName, CVE, CVSSv3_BaseSeverity, CVSSv3_BaseScore, CPE Confidence`
  sorts into a worklist in one line of PowerShell).
- The NVD API key comes from an active `nvd` profile in `~/.m2/settings.xml`, wired in as
  `<nvdApiKey>${nvd.api.key}</nvdApiKey>` — not committed.
- `dependency-check-suppressions.xml` is wired in and carries the decision criteria and a
  template; it holds **no entries yet by design** — nothing has been triaged, so nothing
  can honestly be suppressed.
- **Upgrade decisions are verified empirically** — each Phase 2 bump was confirmed by
  re-running the scan and watching the finding disappear, never by trusting a changelog.
- **Version policy:** stay on the current supported Spring Boot 3.x line. Boot 4.x was
  **deliberately not taken** — it is a Spring Framework 7 / major migration that would blow
  past a bounded security pass and collide with the refactor phase.

## Consequences

**Accepted costs / current limitations:**

- **`failBuildOnCVSS` is set to 11**, above the maximum CVSS of 10, so the scan **never
  fails the build** — it reports, it does not gate. This is a conscious pre-CI choice, set
  explicitly (not left defaulted) so the intent is visible. Turning it into a real gate
  (7 or 8) is a **prerequisite for the Phase 7 CI job** and is blocked on the triage below.
- **Triage is unfinished.** 12 CVEs across 4 dependencies remain, none critical, none with
  an available fix. They are neither suppressed-with-reason nor formally accepted yet, so
  the suppressions file is still empty. The Phase 5 springdoc toggle (docs off outside dev)
  is what finally makes the 22 swagger-ui RetireJS findings *suppressible* — that thread
  connects here.
- **First scan is slow** (~60 min to build the ~600 MB NVD cache); subsequent scans are
  ~2 min. A killed first run leaves an H2 lock that must be deleted before retrying. These
  are operational gotchas, documented so they are not rediscovered.
- **The scan says nothing about broker compatibility.** No test opens a JMS connection, so
  a green scan and a green suite together still do not prove the client can talk to the
  broker — client/broker version jumps need the manual pass recorded in CLAUDE.md.

**Why it is still right:** a single-command scan against live NVD data, read from a CSV
worklist, is cheap enough to actually re-run, and pairing it with the override-expiry
discipline of [0002](0002-dependency-version-overrides-expire.md) turns dependency hygiene
from a one-off cleanup into a repeatable step. The headline Phase 2 result — 133 → 12
unique CVEs (−91%), 5 → 0 criticals, tests green throughout — came out of exactly this
loop.

## Alternatives considered

- **GitHub Dependabot / Dependabot alerts.** Not rejected — complementary, and a natural
  Phase 7 addition. Dependency-Check was chosen first because it runs locally and offline
  against the same pom the build uses, with no dependency on the repo host.
- **Snyk or a commercial scanner.** Rejected for a portfolio project: OWASP Dependency-Check
  is free, self-hosted, and demonstrates the workflow without a paid service in the loop.
- **Manual pom review only.** Rejected: it cannot answer exploitability or fix-availability,
  which is the entire question — see [0002](0002-dependency-version-overrides-expire.md) for
  what a static read misses.

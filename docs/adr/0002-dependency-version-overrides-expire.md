# 2. Every dependency version override carries an expiry

- **Status:** Accepted
- **Date decided:** 2026-07-20 (Phase 2, the security/dependency pass)
- **Date recorded:** 2026-07-21

## Context

Phase 2 raised the Spring Boot parent from 3.2.6 to 3.5.16 to clear a backlog of CVEs.
Doing so exposed something more interesting than the CVEs themselves.

The pom carried three hand-written version overrides. Every one of them had been correct
when written and had since **silently inverted**:

| Override | Pinned at | Boot 3.2.6 managed | Boot 3.5.16 manages | What it became |
|---|---|---|---|---|
| `netty.version` | 4.1.110 | 4.1.109 | 4.1.135 | a **downgrade** of 25 patch versions |
| `jackson-bom` import | 2.19.0 | 2.15.x | 2.21.4 | a **downgrade** of two minor versions |
| `snakeyaml` | 2.4 | — | 2.4 | pure redundancy |

Each was written as a *bump* — "the parent is behind, pull this forward". Each became a
*ceiling* the moment the parent caught up and overtook it. Nothing failed, nothing warned,
and the build stayed green the whole time. The pins were quietly holding libraries
**below** what the framework would otherwise have given us, which is the precise opposite
of why they were added — and for `jackson-databind`, it meant the parent bump alone did
not move it at all. Removing the BOM import is what finally did.

This is a slow, silent failure mode. A version override is a snapshot of a disagreement
with your dependency manager at one moment in time. Dependency managers move; snapshots
do not.

## Decision

**A version override is a liability with an expiry date. Re-check every pin at each parent
bump, or do not add it.**

Concretely:

1. Prefer the parent's managed property (`<tomcat.version>`, `<commons-lang3.version>`)
   over a `<dependencyManagement>` entry, so the override is visibly a delta against the
   parent rather than an independent declaration.
2. Every override carries an `EXPIRY:` comment in the pom stating **the condition under
   which it should be deleted** — not a date, a condition ("drop when the parent manages
   >= 10.1.57").
3. **The first step of any future parent bump is to re-check all of them**, before
   anything else.
4. Verify empirically. Each Phase 2 upgrade was confirmed by re-running the OWASP scan and
   watching the finding disappear, not by trusting a changelog.

## Consequences

Phase 2 removed three stale overrides and deliberately added four new ones, each currently
ahead of the parent — the same debt, knowingly taken:

| Override | Pinned at | Boot 3.5.16 manages | Drop when |
|---|---|---|---|
| `tomcat.version` | 10.1.57 | 10.1.55 | parent manages >= 10.1.57 |
| `commons-lang3.version` | 3.20.0 | 3.17.0 | parent manages >= 3.20.0 |
| `commons-beanutils` (depMgmt) | 1.11.0 | *unmanaged* | Artemis depends on >= 1.11.0 |
| `artemis.version` | 2.55.0 | 2.40.0 | parent manages >= 2.55.0 |

So this ADR does not eliminate the problem — it makes the problem legible. The four
overrides above will become downgrades on some future parent bump exactly as the previous
three did. The difference is that each now says in the pom what would have to be true for
it to be deleted, and the bump checklist starts by reading them.

One wrinkle worth flagging: `artemis.version` is defined by Boot against the **old**
`org.apache.activemq` coordinates, while Artemis has since migrated to
`org.apache.artemis` as a top-level Apache project. If Boot follows that migration, the
override needs re-checking for a reason that has nothing to do with its version number.

**Result of the phase this came from:** 133 → 12 unique CVEs (−91%), 11 → 4 vulnerable
dependencies, 5 → 0 criticals, tests green throughout.

# 4. Packages named by role: `integration/` for external systems, no speculative layers

- **Status:** Accepted
- **Date decided:** 2026-07-21 (Phase 3, the layered-architecture refactor)
- **Date recorded:** 2026-07-23, during the Phase 6 documentation pass

## Context

Phase 3 restructured the backend into standard layered packages. The starting point was a
flat layout with a `repository/` package holding classes that talked to three different
external systems — the Artemis broker over JMS, the same broker's Jolokia HTTP bridge, and
Redis — as if they were all data repositories in the DDD sense.

They are not. `repository/` in the layered/DDD vocabulary means "the persistence boundary
for the domain's aggregates". This application **has no persistence layer and no domain
aggregates**. Every one of those classes is an adapter to an external system the app does
not own. Calling them repositories asserted a layer that does not exist and hid what they
actually are: integration points, each with its own transport, credentials, and failure
mode.

A portfolio "standard layered structure" target list was written up front —
`controller, service, repository, dto, mapper, domain, integration/{artemis,jolokia,cache},
security, util`. Following it literally would have created several packages with nothing
honest to put in them.

## Decision

Name packages by the **role** they play, not by a template:

- **`integration/{artemis,jolokia,cache}`** — everything that talks to an external system,
  grouped by which system. `artemis` is JMS (message-level ops), `jolokia` is HTTP+JSON
  (queue metadata — see [0001](0001-two-broker-access-paths.md)), `cache` is Redis
  (preferences — see [0003](0003-redis-over-localstorage-for-preferences.md)). The package
  name says "this is a boundary", which is the one thing a reader most needs to know before
  touching the code.
- **`repository/` was deleted, not kept empty.** An empty package named after a layer the
  app lacks is worse than no package.
- **Four deliberate deviations from the target list**, each recorded so a reader comparing
  the plan to the code does not assume the refactor was left half-done:
  - no **`domain/`** — there are no domain models; the DTOs are the whole model.
  - no **`mapper/`** — nothing to map to; it arrives with the first real domain model.
  - no **`util/`** — nothing is a genuine cross-cutting helper today; an empty `util` is
    structure for its own sake.
  - **`repository/` removed** — as above.

Final structure: `config, controller, dto, integration/{artemis,jolokia,cache}, security,
service`.

## Consequences

**Accepted costs:**

- **It reads as "incomplete" against a checklist.** A reviewer who expects the canonical
  `domain/mapper/repository` triad will notice they are absent. That is why this ADR and
  the `## Conventions` section of CLAUDE.md both state the deviations explicitly — the
  absence is a decision, not an omission.
- **`dto/` carries more weight than usual.** With no `domain/`, the DTOs are simultaneously
  the wire format and the app's only model. `JolokiaResponse` in particular is an
  integration-shaped DTO; keeping it out of the controller's response was a later fix
  (Phase 5), not part of this structure.
- **The move was mechanical and safe, but the tests could not have proven it.** Every moved
  class is Mockito-verified, so a package move is invisible to the unit suite; only
  `contextLoads()` would have caught a broken component scan, and only the live broker
  passes caught real behaviour. The refactor leaned on manual broker passes, not green
  unit tests, for its safety.

**Why it is still right:** the structure now tells the truth about what the code is. Every
package name a junior reader sees corresponds to something real in the app, and the one
concept that matters most — "these classes cross a process boundary" — is the package name
they live under.

## Alternatives considered

- **Keep `repository/`.** Rejected: it names a layer the app does not have and mislabels
  three integration adapters as persistence.
- **Follow the target list literally** (add `domain`, `mapper`, `util`). Rejected: it would
  create empty or single-purpose packages that assert architecture the code does not
  contain — the same dishonesty as `repository/`, multiplied.

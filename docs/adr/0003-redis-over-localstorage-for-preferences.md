# 3. Last-used-queue preference lives in Redis, keyed by user, not in the browser

- **Status:** Accepted
- **Date decided:** pre-dates this record (the Redis store already existed); made per-user
  in Phase 4 (2026-07-22)
- **Date recorded:** 2026-07-23, during the Phase 6 documentation pass

## Context

The UI remembers which queue you were last looking at and re-selects it on your next
visit. That preference has to live somewhere. Two obvious homes:

1. **The browser** — `localStorage` on the Angular side. Zero backend involvement, no
   round trip, works offline.
2. **The server** — a small keyed store the backend owns.

For a tool that is fundamentally a *server-side* broker inspector — one whose whole reason
to exist is the state of a shared broker, not the state of one person's tab — the second
fits the grain of the app better. The preference is about "which queue does this operator
care about", which is a fact about the operator, not about the machine they happen to be
sitting at.

Phase 4 sharpened this. Once authentication existed, the preference could be keyed by the
**authenticated user** (read from the JWT principal server-side) rather than by a device.
`localStorage` cannot do that — it is per-browser-profile by construction, and it would
have tied the preference to a laptop instead of to a login. Two people sharing a browser
would share a preference; one person on two machines would have two.

Redis was already in the shared dev infrastructure (`../infrastructure`) for exactly this
kind of ephemeral keyed state, so the store cost nothing new to stand up.

## Decision

Store the last-used-queue preference **server-side in Redis, keyed by the authenticated
username**, reached through `integration/cache/RedisQueueRepository` behind
`QueuePreferenceService`.

- The endpoints (`GET`/`POST /api/queues/last-used`) take **no user id** — the username
  comes from the security `Principal`, so a caller can only ever read or write its own
  preference.
- Absence of a preference is represented as an **empty 200, not a 404** — a first-time
  user is the normal case, not an error.

## Consequences

**Accepted costs:**

- **A Redis dependency for one small feature.** The app pulls in Redis and a repository
  layer to remember a single string per user. On its own that is heavy; it is justified
  because the infrastructure already exists and because keying by identity is the whole
  point (see the Phase 4 sharpening above).
- **A non-obvious absence contract.** "No preference yet" is a 200 with an empty body, not
  a 404 or a JSON null. Every caller has to handle absence in the *success* path. This bit
  the frontend once already: a first fix swallowed the empty case as if it were an error
  (`QueueControllerTest.testGetLastUsedQueue_NoPreferenceReturnsOkWithEmptyBody` now pins
  it).
- **The preference does not survive a broker/queue rename.** A stored queue name that no
  longer exists on the broker is silently ignored on restore rather than shown as broken —
  correct behaviour, but it means a stale preference just quietly does nothing.

**Why it is still right:** the preference is a fact about the *user*, and Phase 4's
identity is server-side. Putting it in the browser would have made it a fact about the
*device*, which is the wrong thing to remember for a shared-broker tool behind a login.

## Alternatives considered

- **`localStorage`.** Rejected: per-device, cannot key by authenticated identity, and would
  have made the Phase 4 "real user flows into preferences" outcome impossible without a
  rewrite.
- **A relational table.** Rejected for now: the preference is a single volatile key-value
  per user with no relational shape and no durability requirement — Redis is the honest
  fit. A DB arrives with the first real domain model (see the in-memory-users note in
  [0005](0005-jwt-bearer-over-cookie-session.md)).

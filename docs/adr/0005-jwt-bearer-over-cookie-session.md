# 5. Authentication is a stateless JWT bearer token, not a cookie session

- **Status:** Accepted
- **Date decided:** 2026-07-22 (Phase 4, authentication & authorization)
- **Date recorded:** 2026-07-23, during the Phase 6 documentation pass

## Context

Phase 4 added authentication. Until then the whole `/api/**` surface was permitted
unauthenticated, and the frontend hardcoded `userId = 'jason'`. Two shapes of session were
on the table:

1. **A cookie session** — server-side session state, a session cookie, the traditional
   Spring Security form-login model.
2. **A stateless bearer token** — a signed JWT the client sends in the `Authorization`
   header on every request, with no server-side session at all.

The app is a single-page Angular client talking to a JSON API on a separate origin
(`:4200` → `:8080`). That is the setup bearer tokens are made for, and it is the setup
cookies are most awkward for: cross-origin cookies drag in `SameSite`, `allowCredentials`,
and a CSRF story.

## Decision

Use **stateless JWT bearer authentication**, HS256, via the Spring Security
**resource-server** starter (which supplies both the encoder and decoder — no `jjwt` or
hand-rolled crypto).

- Users are an **in-memory `InMemoryUserDetailsManager`** (`admin`/ADMIN, `jason`/USER,
  BCrypt) — a database is deferred.
- `POST /api/auth/login` exchanges credentials for a token; every other request carries it
  as a bearer header.
- Role gating on the destructive endpoints is **method security**
  (`@PreAuthorize("hasRole('ADMIN')")` on the controller), not path matchers — because
  `move-to-dlq` sits mid-path and Spring's `PathPattern` forbids `**` in the middle of a
  pattern.
- The token's `roles` claim maps to `ROLE_`-prefixed authorities so `hasRole` works.

## Consequences

**Accepted costs:**

- **A throwaway dev secret and dev passwords are committed** in `application.properties`,
  env-overridable (`MESSAGE_INSPECTOR_JWT_SECRET`, `..._ADMIN_PASSWORD`,
  `..._USER_PASSWORD`). This is a conscious choice so a fresh clone boots with no setup —
  and it **must be overridden before any real deployment**. The properties file says so at
  the point of definition.
- **In-memory users — no persistence, no self-service.** Accounts are two hardcoded
  entries. A real deployment needs a user store; that is the first thing that would bring a
  database into the app (and with it, the `domain/` and `mapper/` packages that
  [0004](0004-layered-package-structure.md) deliberately does not yet have).
- **No refresh tokens.** A token expires and the user logs in again. Acceptable for a tool;
  a longer-lived session story is future work.

**The CSRF tripwire that did not fire.** Bearer auth was chosen partly *because* it keeps
CSRF a non-issue: the token rides in a header the browser does not attach automatically,
so there is no cross-site request forgery vector, so CSRF protection can stay disabled
**safely** and CORS `allowCredentials` is `false`. A cookie session would have re-opened
all of that. This is recorded because the earlier code carried a comment warning that
disabling CSRF was dangerous — true for cookies, moot for bearer tokens. **If auth ever
moves to cookies, CSRF must be re-enabled and `allowCredentials` revisited.**

**Why it is still right:** stateless bearer tokens are the natural fit for a cross-origin
SPA + JSON API, they sidestep the CSRF and cross-origin-cookie complexity entirely, and the
resource-server starter meant no bespoke token code.

## Alternatives considered

- **Cookie session.** Rejected: cross-origin cookie friction (`SameSite`,
  `allowCredentials`) and a mandatory CSRF story, for no benefit in a SPA-to-API topology.
- **A third-party IdP / OAuth2 authorization server.** Rejected as disproportionate for a
  portfolio tool with two users; the resource-server half (validating tokens we issue
  ourselves) is all this needs.
- **`jjwt` or hand-rolled JWT.** Rejected: the resource-server starter already brings a
  vetted encoder and decoder — adding another JWT library would be redundant surface.

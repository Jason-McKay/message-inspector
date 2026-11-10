# Testing notes

Moved out of `CLAUDE.md` on 2026-10-31, word for word, so it no longer loads into every
session. `CLAUDE.md` keeps each rule as a line or two and points here for the reason. Where the
text below says "this file", it means `CLAUDE.md` as it stood when the passage was written.

- Several tests use **Testcontainers and need Docker running**. The surefire `argLine`
  in `pom.xml` pins `-Dapi.version=1.44`; do not remove it. docker-java's default Docker
  API version (1.32) is below the 1.40 minimum enforced by Docker Engine 29+, which fails
  with the misleading "Could not find a valid Docker environment".
- **`./mvnw test` does not run `MessageServiceIT` or `McpServerIT`** — surefire's includes
  do not match `*IT`. That is deliberate: `mvn test` stays fast and unit-only. **Use
  `./mvnw verify`** to run them via failsafe. (Passing an explicit `-Dtest=...` filter to surefire also drags
  it in, which is not what you want — it will run without failsafe's setup.)
- `MessageServiceIT` is **self-contained**: it starts its own Artemis container
  (`apache/activemq-artemis:2.31.2`, matching the dev stack), seeds its own message, and
  drains both queues before each test. It needs Docker but not the shared infra, and it
  does not touch the dev broker. `McpServerIT` follows the same pattern: its own Artemis
  container (exposing 8161 too, for Jolokia), the app on a random port, and a real MCP SDK
  client with real JWTs. These two are the only automated tests that open a JMS
  connection — everything else at the message level is Mockito-based.
- `SecurityConfigTest` exercises the real filter chain (CORS preflight + a 401 on an
  unauthenticated request). It is **broker-independent** — the old
  `testApiIsCurrentlyPermittedWithoutAuthentication`, which hit the live Jolokia path and
  needed the shared infra, was replaced in Phase 4 by `testApiRequiresAuthentication`
  (security rejects before any controller/broker call). The whole `./mvnw test` suite now
  passes with the shared infra down, given Docker is up for the Testcontainers tests.
- **Verify JMS changes by hand.** Boot the app, produce with
  `MSYS_NO_PATHCONV=1 docker exec dev-artemis sh -c 'exec /opt/activemq-artemis/bin/artemis
  producer --url tcp://localhost:61616 --destination queue://DLQ --message-count 3
  --user "$ARTEMIS_USER" --password "$ARTEMIS_PASSWORD"'` (single quotes: the container's own
  env supplies the login, so it never appears on the host), then exercise browse / get-by-id /
  delete / resend / move-to-dlq.
  This pass has caught real bugs the suite could not see. Clean up afterwards: purge the
  DLQ and delete any `DLQ_test` or `ActiveMQQueue[DLQ]` addresses it leaves behind.
- **`spring-boot:run` in the background outlives its Maven wrapper.** Killing the wrapper
  task leaves the forked JVM holding :8080, and the next run fails with "Port 8080 was
  already in use". Kill the `java` process by port, not the wrapper.

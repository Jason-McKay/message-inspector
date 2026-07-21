# 1. Two broker access paths: JMS for messages, Jolokia for queue metadata

- **Status:** Accepted
- **Date decided:** pre-dates this record (the split already existed)
- **Date recorded:** 2026-07-21, during the Phase 3 refactor, while the reasoning was
  still recoverable

## Context

Message Inspector needs two quite different things from an ActiveMQ Artemis broker:

1. **Message-level operations** — browse a queue, fetch one message by ID, republish it,
   selectively consume it, move it to a DLQ.
2. **Queue-level metadata** — which queues/addresses exist, and how many messages each
   holds, in order to populate the UI's queue picker.

JMS gives us the first directly. `JmsTemplate`, `QueueBrowser` and a message selector
cover browse, get-by-id and selective consume, and those are exactly the operations the
tool exists to perform.

JMS does **not** give us the second. The JMS API has no concept of "list the queues on
this broker" — a JMS client addresses destinations it already knows the names of.
Artemis exposes that inventory over **JMX** instead, as broker MBean attributes such as
`AddressNames`.

Reaching JMX would normally mean opening an RMI connector, which is awkward to expose,
awkward to secure, and awkward to call from a Spring web app. Artemis ships its
management console with a **Jolokia** bridge, which exposes the same MBeans over plain
HTTP+JSON on the console port (`:8161`). That is already enabled in any deployment where
someone can use the web console, needs no extra port, and is a trivial `RestTemplate`
call.

An attempt at doing queue discovery over JMS exists in the codebase
(`JmsQueueRepository.fetchQueuesWithArtemis`). It does not work and never did — it calls
`session.createBrowser(null)`, which throws, and returns an empty list. It stands as
evidence of the dead end rather than as a working alternative.

## Decision

Use **both** transports, split strictly by concern:

- **JMS (`JmsTemplate`)** → everything at the *message* level → `integration/artemis`
- **Jolokia HTTP (`jolokiaRestTemplate`)** → everything at the *queue/address* level →
  `integration/jolokia`

The two are independent: separate clients, separate configuration, separate credentials.
Neither is a fallback for the other.

## Consequences

**Accepted costs:**

- **Two sets of credentials and two failure modes.** `ARTEMIS_USER`/`ARTEMIS_PASSWORD`
  govern JMS; `JOLOKIA_USER`/`JOLOKIA_PASSWORD` govern Jolokia. The broker can be
  reachable on one and not the other — queue discovery can fail while message operations
  work, and vice versa. Anyone debugging "the broker is down" must establish *which* path
  is down.
- **A dependency on the management console being enabled.** If a deployment disables the
  console, queue discovery stops working even though messaging is fine.
- **Coupling to the broker name.** `JolokiaRepository` builds MBean URLs by
  string-formatting `jolokia.brokerName` into an ObjectName. A broker renamed in
  `broker.xml` breaks discovery with a 404 from Jolokia, not an obvious error.
- **A leaked envelope.** `QueueController` currently returns Jolokia's raw
  `request`/`value`/`timestamp`/`status` wrapper to the browser. That is an integration
  detail reaching the client, and it is a known wart — see the plan's known gaps. It is a
  consequence of this decision that was never cleaned up, not part of the decision itself.

**Why it is still right:** the alternative is exposing and securing JMX/RMI purely to
list queue names, for a tool whose whole job is broker introspection. The Jolokia bridge
is the path the Artemis console itself uses.

## Alternatives considered

- **JMS only.** Rejected: the API cannot enumerate destinations. The dead
  `fetchQueuesWithArtemis` is what this looks like when attempted.
- **JMX/RMI directly.** Rejected: extra port, awkward auth, poor fit with a Spring MVC
  app, no benefit over the Jolokia bridge that fronts the same MBeans.
- **Jolokia only.** Rejected: Artemis management operations can move and remove messages,
  but browsing payloads and republishing is far more natural over JMS, and message
  selectors are a JMS feature the tool leans on heavily.

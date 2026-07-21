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

Both were written retrospectively, on 2026-07-21, while the reasoning was still
recoverable. That is worth stating plainly: they are reconstructions, and the "date
decided" line in each says when the decision was actually taken.

Decisions worth recording that are **not yet written up**: the layered package structure
adopted in Phase 3 (why `integration/` rather than `repository/`), and Redis over
`localStorage` for last-used-queue state.

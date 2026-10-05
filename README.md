# csp-booking-api

> booking bounded context: service API

Part of the **Cinesync Platform** distributed system — team `cinesync-platform`, Group 1.
Governance and documentation live in [`csp-docs`](https://github.com/code-corhuila/csp-docs).

## Purpose

`csp-booking-api` is the service of the **booking** domain: temporary seat holds, reservations and their
lifecycle (`HELD`, `CONFIRMED`, `EXPIRED`). It is the only writer of the `booking` schema and the only
publisher of booking events, which it writes to the outbox table in the same transaction as the change.
Catalog owns movies, rooms and showtimes; Auth owns identity; Ticketing and Concessions consume the events.

The repository currently holds the **base scaffold only**. The service is built in small steps; each step is a
Pull Request that leaves the repository coherent.

## Stack

| Item | Decision | Record |
|---|---|---|
| Language and build | Java 21, Spring Boot 3.x, Maven, modules `booking-core`, `booking-adapters` and `booking-app` | [ADR-012](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-012-booking-java-spring-boot-maven.md) |
| Architecture | Hexagonal: adapters, then application, then domain (the dependency rule) | [Hexagonal architecture guide](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/hexagonal-architecture.md) |
| Database | PostgreSQL schema `booking`, owned and migrated by `csp-booking-db` with Flyway | [ADR-013](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-013-booking-postgresql-flyway.md) |
| Events | The API writes `booking.outbox_events`; `csp-worker` publishes them with read-only access | [ADR-014](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-014-booking-outbox-relay-read-only.md) |
| Contract | OpenAPI of the booking service | [`booking-service.yaml`](https://github.com/code-corhuila/csp-docs/blob/main/07-api/contracts/openapi/booking-service.yaml) |

## Rules of this repository

- **No migration here.** The schema and its migrations live only in `csp-booking-db`. This service declares no
  migration library and runs no migration at startup (Norma 5.2.1).
- **The domain depends on nothing.** `booking-core` does not declare Spring or a database driver, so a framework
  annotation in the domain does not compile.
- **Only the approved contract is implemented.** A route that is not in `booking-service.yaml` is not added.
- **The API never connects to RabbitMQ.** It writes events to the outbox table only.

## Related repositories

| Repository | Relation |
|---|---|
| `csp-booking-db` | Owns the `booking` schema, its roles and its migrations |
| `csp-infra-postgres` | Defines the PostgreSQL instance and creates the login user `booking_app` |
| `csp-worker` | Publishes the booking outbox and schedules the hold expiration |
| `csp-api-gateway` | Single entrance of the system; routes `/api/v1/booking` here |
| `csp-docs` | Governance, contracts, data model and ADRs |

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `csp-docs`.

## Pull Requests and commits

- Commits follow Conventional Commits: `<type>(<scope>): <description>`, in English, lowercase and imperative.
- A Pull Request has at most **400 changed lines** (additions plus deletions) and one logical goal.
- Every Pull Request targets the permanent branch that matches its prefix, according to the diagram above.

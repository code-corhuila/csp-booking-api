# csp-booking-api

> booking bounded context: service API

Part of the **Cinesync Platform** distributed system — team `cinesync-platform`, Group 1.
Governance and documentation live in [`csp-docs`](https://github.com/code-corhuila/csp-docs).

## Purpose

`csp-booking-api` is the service of the **booking** domain: temporary seat holds, reservations and their
lifecycle (`HELD`, `CONFIRMED`, `EXPIRED`). It is the only writer of the `booking` schema and the only
publisher of booking events, which it writes to the outbox table in the same transaction as the change.
Catalog owns movies, rooms and showtimes; Auth owns identity; Ticketing and Concessions consume the events.

The service is built in small steps; each step is a Pull Request that leaves the repository coherent. The first one
implements **HU-BOOKING-001**: the temporary hold of seats and the two reads of the reservations of the caller.

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

## Build, test and run

Requirements: Java 21 and Maven 3.9 (or Docker).

```bash
mvn -B verify                              # compile and run every test
mvn -B -pl booking-core test               # only the domain tests (no Spring, no database)
mvn -B -pl booking-app -am package -DskipTests
java -jar booking-app/target/booking-app-0.1.0.jar   # starts on PORT, 8083 by default
```

With Docker, from the root of the repository:

```bash
docker build -f deploy/Dockerfile -t csp-booking-api .
docker run --rm -e PORT=8083 -p 8083:8083 csp-booking-api
```

The log ends with `Started BookingApplication` when the service is up. In the platform,
`csp-infra` includes `deploy/compose.yml`, which exposes the port on the `platform` network without publishing it:
only the gateway reaches the service. Copy `.env.example` to `.env` for local values and never commit `.env`.

## API

The routes are served under `/api/v1/booking`, the base `booking-service.yaml` opens with
(`server.servlet.context-path`), and every answer carries the `X-Correlation-Id` of the request.

| Route | Answer |
|---|---|
| `GET /health` | Liveness, independent of PostgreSQL |
| `GET /health/ready` | Readiness: `503` while the database cannot be reached |
| `POST /holds` | Creates the hold: `201` with the Location, `200` when the key replays, then `400`/`401`/`409`/`422`/`500`/`503` |
| `GET /reservations/{reservationId}` | One reservation of the caller (`403` for another user's, `404` when it does not exist) |
| `GET /reservations` | Page of the caller, newest first: `page`, `limit`, `status`, `createdBefore` |

```bash
curl http://localhost:8083/api/v1/booking/health
curl -X POST http://localhost:8083/api/v1/booking/holds \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -H 'Idempotency-Key: 7f0b2f0e-9d5c-4a6a-9a1b-3f0c9d8e7b6a' \
  -d '{"showtimeId":"<uuid>","seatLabels":["A1"],"movieTitle":"Movie","roomName":"Room 1"}'
```

The token is a JWT signed with RS256 whose `sub` is the id of the user; `csp-infra` `dev-token.sh` hands one out for
local work (ADR-020). Every error is the envelope of the contract: `error`, `message`, the `details` that name the
fields that broke a validation, and `traceId`.

## Internal operations

Not part of the public contract and never routed by the gateway: only `csp-worker` calls them, with a service token.
A client token receives `403`.

| Route | Answer |
|---|---|
| `POST /internal/maintenance/expire-holds` | Expires the HELD reservations past their time (HU-BOOKING-002): `200` `{"expired", "remaining"}`, `401`/`403` |

- **Authentication:** the same RS256 validation as every route. The `sub` of the token must be one of `SERVICE_SUBJECTS`
  (`csp-worker` by default); `X-Correlation-Id` is required and travels to the event.
- **Limits (Norma 5.3.10):** one call expires at most 100 holds, oldest first; `remaining` tells the worker whether
  another run is needed. The interval between runs belongs to `csp-worker`.
- **Idempotent:** every statement keeps a `status = 'HELD'` guard, so a repeated or overlapping sweep changes nothing,
  and a confirmation that wins the race is never overwritten.
- **What expiring does, in one transaction:** `reservation` and `seat_hold` become `EXPIRED`, the `seat_hold_item`
  rows become `RELEASED` (that is what frees the seats in `uk_seat_hold_item_active_seat`), and `ReservationExpired`
  is written to the outbox.
- **Without the sweep running,** held seats are never released: check that `csp-worker` is up and that its
  `SERVICE_TOKEN` carries `sub=csp-worker`.

## Integration tests

`JdbcHoldRepositoryTest` runs only when `TEST_DATABASE_URL` is set, for example
`jdbc:postgresql://localhost:5432/csp?user=postgres&password=postgres`, over a database where the migrations of
`csp-booking-db` were applied. CI does this on every Pull Request (it needs the `DB_REPO_TOKEN` secret to read that
repository).

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

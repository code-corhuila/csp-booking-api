# csp-booking-api

> booking bounded context: service API

Part of the **Cinesync Platform** distributed system — team `cinesync-platform`, Group 1.
Governance and documentation live in [`csp-docs`](https://github.com/code-corhuila/csp-docs).

## Purpose

`csp-booking-api` is the service of the **booking** domain: temporary seat holds, reservations and their
lifecycle (`HELD`, `CONFIRMED`, `EXPIRED`). It is the only writer of the `booking` schema and the only
publisher of booking events, which it writes to the outbox table in the same transaction as the change.
Catalog owns movies, rooms and showtimes; Auth owns identity; Ticketing and Concessions consume the events.

The three stories of Cut 2 are implemented: **HU-BOOKING-001** (the temporary hold of seats and the two reads of the
reservations of the caller), **HU-BOOKING-002** (the internal operation that expires the holds that were not confirmed)
and **HU-BOOKING-003** (the confirmation of a reservation). What is still missing is listed in [What is missing](#what-is-missing).

## Stack

| Item | Decision | Record |
|---|---|---|
| Language and build | Java 21, Spring Boot 3.x, Maven, modules `booking-core`, `booking-adapters` and `booking-app` | [ADR-012](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-012-booking-java-spring-boot-maven.md) |
| Architecture | Hexagonal: adapters, then application, then domain (the dependency rule) | [Hexagonal architecture guide](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/hexagonal-architecture.md) |
| Database | PostgreSQL schema `booking`, owned and migrated by `csp-booking-db` with Flyway | [ADR-013](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-013-booking-postgresql-flyway.md) |
| Events | The API writes `booking.outbox_event`; `csp-worker` publishes them with read-only access | [ADR-014](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-014-booking-outbox-relay-read-only.md) |
| Contract | OpenAPI of the booking service | [`booking-service.yaml`](https://github.com/code-corhuila/csp-docs/blob/main/07-api/contracts/openapi/booking-service.yaml) |

## Rules of this repository

- **No migration here.** The schema and its migrations live only in `csp-booking-db`. This service declares no
  migration library and runs no migration at startup (Norma 5.2.1).
- **The domain depends on nothing.** `booking-core` does not declare Spring or a database driver, so a framework
  annotation in the domain does not compile.
- **Only the approved contract is implemented.** A route that is not in `booking-service.yaml` is not added.
- **The API never connects to RabbitMQ.** It writes events to the outbox table only.

## Build, test and run

Requirements: Java 21 and Maven 3.9 (or Docker). `mvn -B verify` needs no database: the tests that do are skipped
unless `TEST_DATABASE_URL` is set (see [Integration tests](#integration-tests)).

```bash
mvn -B verify                              # compile and run every test
mvn -B -pl booking-core test               # only the domain tests (no Spring, no database)
mvn -B -pl booking-app -am package -DskipTests
```

### Configuration

The service **refuses to start without the public key** that validates the tokens (Norma 5.3.7). Every variable it
reads is listed in `.env.example`; copy it to `.env` for local values and never commit `.env`.

| Variable | Default | What it is |
|---|---|---|
| `JWT_PUBLIC_KEY` or `JWT_PUBLIC_KEY_FILE` | none, **required** | The PEM of the public key, or the path of the file that holds it. Set one of the two |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/csp` | The database of this domain |
| `SPRING_DATASOURCE_USERNAME` | `booking_app` | The login user of the service, never the administrator |
| `SPRING_DATASOURCE_PASSWORD` | `change-me` | Set it to the value `csp-infra-postgres` gave to `booking_app` |
| `SERVICE_SUBJECTS` | `csp-worker` | The `sub` of the service tokens allowed on the internal operations (ADR-020) |
| `PORT` | `8083` | The listening port |

### Run it

```bash
export JWT_PUBLIC_KEY_FILE=/path/to/public.pem          # or JWT_PUBLIC_KEY with the PEM itself
java -jar booking-app/target/booking-app-0.1.0.jar      # starts on PORT, 8083 by default
```

With Docker, from the root of the repository (the image reads the key from a mounted file):

```bash
docker build -f deploy/Dockerfile -t csp-booking-api .
docker run --rm -p 8083:8083 -e JWT_PUBLIC_KEY_FILE=/run/jwt/public.pem \
  -v /path/to/keys:/run/jwt:ro csp-booking-api
```

The log ends with `Started BookingApplication` (about 20 seconds in a container). The service starts **without
PostgreSQL**: `GET /health` answers `200`, `GET /health/ready` answers `503` until the database is reachable, and a
route without a token answers `401`. In the platform, `csp-infra-postgres` (the platform composition and the
PostgreSQL instance this service needs) includes `deploy/compose.yml`, which exposes the port on the `platform`
network without publishing it, and `./scripts/dev-keys.sh` creates the development key pair and the tokens.

### Where the data is

The schema is `booking`, in the database `csp` of the single PostgreSQL instance of `csp-infra-postgres` (default URL
above, host `postgres` and port `5432` inside the `platform` network). The service logs in as `booking_app`; its
password is `BOOKING_APP_PASSWORD` in the `env/dev.env` of `csp-infra-postgres`, and the tables and roles come from
`csp-booking-db`. To look at the rows, connect to that instance with a client of your choice as `booking_app` (or as the
administrator of the platform) and query `booking.reservation`, `booking.seat_hold`, `booking.seat_hold_item` and
`booking.outbox_event`.

## API

The routes are served under `/api/v1/booking`, the base `booking-service.yaml` opens with
(`server.servlet.context-path`), and every answer carries the `X-Correlation-Id` of the request.

| Route | Answer |
|---|---|
| `GET /health` | Liveness, independent of PostgreSQL |
| `GET /health/ready` | Readiness: `503` while the database cannot be reached |
| `POST /holds` | Creates the hold: `201` with the Location, `200` when the key replays, then `400`/`401`/`409`/`422`/`500`/`503` |
| `POST /reservations/{reservationId}/confirm` | Confirms a `HELD` reservation of the caller: `200` with the reservation, `422 INVALID_STATUS_TRANSITION` when it is expired or already confirmed, `403` for another user's, `404` when it does not exist |
| `GET /reservations/{reservationId}` | One reservation of the caller (`403` for another user's, `404` when it does not exist) |
| `GET /reservations` | Page of the caller, newest first: `page`, `limit`, `status`, `createdBefore` |

```bash
curl http://localhost:8083/api/v1/booking/health
curl -X POST http://localhost:8083/api/v1/booking/holds \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -H 'Idempotency-Key: 7f0b2f0e-9d5c-4a6a-9a1b-3f0c9d8e7b6a' \
  -d '{"showtimeId":"<uuid>","seatLabels":["A1"],"movieTitle":"Movie","roomName":"Room 1"}'

curl -X POST http://localhost:8083/api/v1/booking/reservations/<reservationId>/confirm \
  -H 'Authorization: Bearer <token>' \
  -H 'Idempotency-Key: 0c9a3c3e-5f0b-4b8e-8d3a-2a6d1f4e9b71'
```

The token is a JWT signed with RS256 with an `exp` and a `sub` that is **the UUID of the user**; `dev-token.sh <uuid>` of
`csp-infra-postgres` hands one out for local work (ADR-020), and a `sub` that is not a UUID is a `401`. Every error is
the envelope of the contract: `error`, `message`, the `details` that name the fields that broke a validation, and
`traceId`.

**Idempotency.** `POST /holds` stores its `Idempotency-Key` with the hold, so repeating the request answers `200` with
the original reservation (Norma 5.3.8), and the same key with another body is a `409`. `POST /reservations/{id}/confirm`
requires the header and checks its shape (16 to 100 characters) but does not store it: a reservation is confirmed once
and `BookingConfirmed` is written once, because the transition is guarded, so a retry after the first success answers
`422`, the same answer as for an expired reservation. Read the reservation to know which one it was.

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
`csp-booking-db` were applied. CI does this on every Pull Request and fails when the `DB_DEPLOY_KEY` secret (the private half of a read-only deploy key of that repository) is
missing, so a green run means the tests ran. The schema is pinned to a `csp-booking-db` commit (`ref` in `ci.yml`):
to test a newer schema, move that pin in a Pull Request of its own.

## What is missing

- **No gateway.** Nothing routes `/api/v1/booking` yet and the service has no CORS configuration, so a browser cannot
  call it directly; the portals expect the gateway of Annex F.
- **No Catalog snapshot.** The service runs without `CATALOG_BASE_URL` (Cut 2): `movieTitle` and `roomName` come in the
  request of the hold, `showtimeStartsAt` of `BookingConfirmed` is `null` and `totalAmount` is `0` (ADR-020).
- **No replay of a confirmation.** See the idempotency note above.
- **No release of a hold by the client.** The contract 3.1.0 has no such route; a hold ends by confirmation or expiration.
- **The outbox has no purge** and nothing deletes from it (`csp-booking-db#18`).
- **No list for an administrator.** The routes only read the reservations of the caller.

## Related repositories

| Repository | Relation |
|---|---|
| `csp-booking-db` | Owns the `booking` schema, its roles and its migrations |
| `csp-infra-postgres` | Defines the PostgreSQL instance and creates the login user `booking_app` |
| `csp-worker` | Publishes the booking outbox and schedules the hold expiration |
| `csp-api-gateway` | Planned single entrance of the system (it will route `/api/v1/booking` here). It does not exist yet: its repository only holds the seeded README, so today a client reaches this service directly on its port |
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
A branch named `qa/...` cannot be created while the branch `qa` exists (Git refuses the reference), so the promotion
branches of this repository are named `qa-promote/<repo>-<description>` (ADR-021).

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `csp-docs`.

## Pull Requests and commits

- Commits follow Conventional Commits: `<type>(<scope>): <description>`, in English, lowercase and imperative.
- A Pull Request has at most **400 changed lines** (additions plus deletions) and one logical goal.
- Every Pull Request targets the permanent branch that matches its prefix, according to the diagram above.

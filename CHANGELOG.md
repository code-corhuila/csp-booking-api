# Changelog

All notable changes of `csp-booking-api` are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the versions follow
[Semantic Versioning](https://semver.org/). A release is a `release/<version>` branch cut from `main` and filled with the commits of `qa`,
re-applied with `git cherry-pick -x` (numerals 6.2.3, 10 and 11 of the course norm); it reaches `main` by pull request, never by merging `qa`,
and is tagged `v<version>` once it is merged.

## [Unreleased]

### Fixed

- The hold keeps the start time of the showtime (HU-BOOKING-001, ADR-020 section 1, rest of TD-040): `Reservation.showtimeStartsAt` is copied from the availability answer of Catalog when the hold is created, written to `seat_hold.showtime_starts_at` (the column already existed), conserved by every transition and published as `showtimeStartsAt` in `BookingConfirmed` (`events.md`), which was always `null`. It is `null` only for a hold written before this change. The HTTP `Reservation` of `booking-service.yaml` does not declare it, so the response is unchanged.

- The hold takes the title, the room and the amount from Catalog (HU-BOOKING-001, ADR-020 section 1, TD-040, found by the end-to-end test of `csp-workflow`): `POST /holds` and `POST /internal/workflow/holds` copy `movieTitle` and `roomName` from the availability answer of Catalog and charge `totalAmount` as its `price` times the number of seats, instead of trusting the request. `movieTitle` and `roomName` in the body are optional and ignored, as `booking-service.yaml` says, so the workflow, which does not know them, no longer gets `400 VALIDATION_ERROR` and its saga `book-seats` no longer ends `COMPENSATED` at `hold-seats`; they no longer take part in the request hash, so a replay with another title is a replay. `CreateHoldInput` loses both fields. `showtimeStartsAt` is still null (rest of TD-040).

### Added

- Every call to an `/internal/` route writes one structured line (`InternalCallAuditFilter`): `sub` (as the token claims it), `route`, `status`, `authenticated`,
  `tokenSha256` (a one-way digest, never the token) and the `traceId` of the MDC (STRIDE E, `csp-docs#285`). `ForgedWorkflowTokenAuthzTest` checks that a workflow
  token signed with another key or with HS256 is `401 INVALID_TOKEN` and a call without token `401 UNAUTHORIZED`.

### Changed

- The access token of a user is rejected with `401 INVALID_TOKEN` when it has no `iat` or when `exp` minus `iat` is longer than 900 seconds plus the 30 s tolerance
  (ADR-054, STRIDE S, `csp-docs#284`): Booking cannot read the revoked-token list of Auth, so the cap keeps the window at 15 minutes. Service credentials are not capped.
- `seatLabels` of a hold takes at most 10 entries (`400 VALIDATION_ERROR` naming the field) and a user can have at most 5 `HELD` reservations, set by
  `MAX_ACTIVE_HOLDS_PER_USER` (`422 BUSINESS_RULE_VIOLATION` beyond it; STRIDE D, DEC-008, `csp-docs#283`). The repository applies the limit in the
  transaction of the hold, after the idempotency key and under a lock of the user, so a replay at the limit still answers its reservation.
- `POST /holds` and `POST /internal/workflow/holds` read the layout of the showtime from Catalog and refuse with `422 BUSINESS_RULE_VIOLATION`,
  holding nothing, a seat that is not in it (STRIDE T, `csp-docs#289`). Catalog is the authority: without `CATALOG_BASE_URL` or with
  Catalog down the hold answers `503`, and an unknown showtime `404`. TLS towards Catalog is still pending its ADR.
- The service credentials (the tokens of the subjects of `SERVICE_SUBJECTS` and `WORKFLOW_SUBJECTS`) must carry `iss` `csp-auth` and an `aud` that
  contains `csp-internal`: a token of a service subject without them, or with other values, answers `401` (a user's access token has `aud` `csp-api`
  and is never a service credential); a valid credential of a subject that is not allowed on the route answers `403`. A token issued before the
  change, without the claims, is refused, so the credentials of `csp-worker` and `csp-workflow` must be reissued first (ADR-056,
  `csp-booking-api#69`).

### Fixed

- The platform compose of `booking-api` now passes `SERVICE_SUBJECTS` (default `csp-worker`) and `WORKFLOW_SUBJECTS` (default empty); before, they could not be
  set from the platform, so `/internal/workflow/` stayed closed there whatever the platform declared (ADR-035, `csp-booking-api#69`).
- The platform compose of `booking-api` now passes `CATALOG_BASE_URL` and `CATALOG_SERVICE_TOKEN_FILE` and mounts the service credential
  (`CATALOG_SERVICE_TOKEN_HOST_FILE`, read-only); before, the routes that read Catalog answered `503` in the platform (ADR-020).
- The client routes now check the `permissions` claim of the access token (`booking:create` on `POST /holds` and on the confirmation,
  `booking:read:self` on the two reads of reservations) and answer `403 FORBIDDEN` without it, and `exp` is accepted up to 30 seconds late
  (Norma 5.3.7 and 5.6.2; `csp-booking-api#67`). The listing of every user (`booking:read:all`) has no route yet.

### Added

- Client of the Catalog availability operation (`GET /showtimes/{showtimeId}/availability`): the output port `ShowtimeCatalog` and the adapter
  `HttpShowtimeCatalog`, with the service credential read from `CATALOG_SERVICE_TOKEN_FILE` (re-read every 30 s), timeouts of 2 s and 3 s,
  `404` as a showtime that is not available, timeout, connection error or `5xx` as an unavailable Catalog, `401` or `403` as a configuration
  fault, and no layout invented when `seatLayout` is missing (ADR-020, ADR-046, `catalog-service.yaml` 1.9.0). No route uses it yet
  (`csp-booking-api#62`, HU-CATALOG-005).
- `GET /showtimes/{showtimeId}/seats`: the seat map for any authenticated role, the layout of Catalog with `AVAILABLE`, `HELD` or `CONFIRMED` per seat
  (`seat_hold_item`, the same fact that makes a second hold of the seat fail; an overdue hold stays `HELD` until the sweep), `Cache-Control: no-store`,
  `404` / `503` / `500` mapped from Catalog (ADR-046, `booking-service.yaml` 3.3.0; `csp-booking-api#62`, HU-CATALOG-005).
- `WORKFLOW_SUBJECTS`: the service subjects allowed on `/internal/workflow/`, separate from `SERVICE_SUBJECTS` so the workflow service
  never reaches the maintenance operations and the worker never reaches the workflow ones. Empty keeps the family closed (ADR-035, proposed).
- `POST /internal/workflow/holds` and `POST /internal/workflow/reservations/{id}/confirm`, for the workflow service: the hold for the user
  of the body, and a confirmation that is idempotent by state (ADR-035, proposed).
- `POST /internal/workflow/reservations/{id}/release`: undoes a hold on demand, with the effect and the `ReservationExpired` event of
  the expiration sweep and without waiting for the deadline (ADR-035, proposed).

## [2.0.0] - 2026-10-08

MVP 2 (Cut 2). Stories HU-BOOKING-001 ([#8](https://github.com/code-corhuila/csp-booking-api/issues/8)), HU-BOOKING-002
([#9](https://github.com/code-corhuila/csp-booking-api/issues/9)) and HU-BOOKING-003
([#10](https://github.com/code-corhuila/csp-booking-api/issues/10)): a client holds seats of a showtime in one transaction, an
expired hold releases them, and a held reservation is confirmed.

### Added

- `POST /holds`: holds the seats of a showtime in one PostgreSQL transaction; if one seat is not available nothing is held. The
  hold, its seats, the idempotency key and the `ReservationHeld` event are stored together (transactional outbox, DEC-001, DEC-002).
  ([#23](https://github.com/code-corhuila/csp-booking-api/pull/23), [#26](https://github.com/code-corhuila/csp-booking-api/pull/26),
  [#27](https://github.com/code-corhuila/csp-booking-api/pull/27))
- `Idempotency-Key` on `POST /holds`: the same key and body answers the stored reservation, the same key with another body
  answers `409`. ([#27](https://github.com/code-corhuila/csp-booking-api/pull/27))
- Reads of the reservations of the caller behind the contract. ([#28](https://github.com/code-corhuila/csp-booking-api/pull/28))
- Bearer RS256 validation with a closed algorithm list and the `X-Correlation-Id` header carried through the request.
  ([#18](https://github.com/code-corhuila/csp-booking-api/pull/18))
- `/health` without dependencies and `/health/ready` that checks only PostgreSQL (DEC-005).
  ([#21](https://github.com/code-corhuila/csp-booking-api/pull/21))
- Internal sweep that expires the overdue holds, bounded by batch and idempotent: it moves the reservation and the hold to
  `EXPIRED`, releases the seats and stores `ReservationExpired` (DEC-004). Only a service token (`SERVICE_SUBJECTS`) can call it.
  ([#29](https://github.com/code-corhuila/csp-booking-api/pull/29))
- Confirmation of a held reservation: the domain decides the transition, the repository guards it in SQL, and `BookingConfirmed` is
  stored in the same transaction. The key is required and checked; a retry after success answers `422`, as the contract states.
  ([#38](https://github.com/code-corhuila/csp-booking-api/pull/38))
- The integration tests run in CI against PostgreSQL, with the schema of `csp-booking-db`.
  ([#32](https://github.com/code-corhuila/csp-booking-api/pull/32))

### Changed

- Domain errors are split by cause, the access denial keeps its context, and the authenticated identity enters through a port
  declared by `booking-core`. ([#41](https://github.com/code-corhuila/csp-booking-api/pull/41),
  [#44](https://github.com/code-corhuila/csp-booking-api/pull/44))
- README describes the confirmation, the configuration and what is missing.
  ([#49](https://github.com/code-corhuila/csp-booking-api/pull/49))

### Fixed

- Each reservation seat now stores the hold of its reservation, which the composite key added in `csp-booking-db` requires.
  ([#46](https://github.com/code-corhuila/csp-booking-api/pull/46))
- A write that cannot open its database transaction answers `503` instead of `500`.
  ([#48](https://github.com/code-corhuila/csp-booking-api/pull/48))

### Known limits

- No gateway: nothing routes `/api/v1/booking` yet and the service has no CORS configuration, so a browser cannot call it directly.
- No Catalog snapshot: without `CATALOG_BASE_URL` the request of the hold carries `movieTitle` and `roomName`, `totalAmount` is `0`
  and `showtimeStartsAt` of `BookingConfirmed` is `null` (DEC-006, ADR-020).
- The confirmation does not replay the first answer: a retry after success answers `422`.
- A client cannot release a hold: contract 3.1.0 has no such route.
- The outbox has no purge ([csp-booking-db#18](https://github.com/code-corhuila/csp-booking-db/issues/18)).
- No list of reservations for an administrator: the routes read only the reservations of the caller.
- The relay of the outbox runs in `csp-worker`, which is off by default.

## [0.1.0] - 2026-10-05

### Added

- Governance files, the three-module Maven build (`booking-core`, `booking-adapters`, `booking-app`), the reservation aggregate
  with its typed domain errors, the CI workflow, the `Dockerfile` and the service compose file.

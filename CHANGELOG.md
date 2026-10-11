# Changelog

All notable changes of `csp-booking-api` are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the versions follow
[Semantic Versioning](https://semver.org/). A release is a `release/<version>` branch cut from `main` and filled with the commits of `qa`,
re-applied with `git cherry-pick -x` (numerals 6.2.3, 10 and 11 of the course norm); it reaches `main` by pull request, never by merging `qa`,
and is tagged `v<version>` once it is merged.

## [Unreleased]

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

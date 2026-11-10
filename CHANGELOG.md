# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-11-10

### Added

- `tech/`: 29 beginner notes, one per technology, with line-by-line walkthroughs of the main code,
  the tests, the build files and the scripts, and an index with a reading order.

### Fixed

- `PhaseTimingsTest`: the negative-phase test now uses a correct total, so it tests that rule alone.

## [1.0.0] - 2026-11-10

### Added

- SeatWave's read endpoints of Module 1 (`GET /events`, `GET /events/1/seats?section=A`, `GET /health`)
  on the JDK HTTP server, over plain HTTP and over HTTPS (TLS 1.3 only), on 127.0.0.1 only, with a
  `Server-Timing` header on every response.
- Throwaway TEST certificates made by the JDK's keytool at build time into `build/tls`: a test CA and
  three server certificates it signs (valid, expired, wrong name), with a random keystore password.
- A delay link on the loopback address that adds a fixed one-way delay and, optionally, the TCP
  handshake's round trip, with a connection limit and connection counters.
- The Java timing client: the JDK `HttpClient`, a new connection against a reused one, HTTP/1.1 and
  HTTP/2 asked for, the protocol answered recorded, and the connections each scenario opened counted.
- `scripts/curl-timings.sh`: `curl -w` with every phase, a dry run by default, a confirmation, an
  allow-list of targets, a cap of 3 runs per public site, and an audit line.
- The timing report: a parser for curl's lines and a table of median runs with five phases each, and
  the invariant that every row comes from at least 3 runs whose phases sum to the total.
- The TLS failure scenarios: an expired certificate, a wrong name and a TLS 1.2-only client, each
  refused by a client that trusts only the test CA.
- Lab 3's page model: round trips per protocol, slow start, packet loss with fast and tail recovery,
  and head-of-line blocking counted for HTTP/1.1, HTTP/2 and HTTP/3.
- Tests with JUnit Jupiter and AssertJ, every one against servers it starts on the loopback address.
- Build with a pinned Gradle wrapper, dependency lock files, `-Xlint:all -Werror`, Spotless with
  google-java-format, a JaCoCo line-coverage floor of 0.80 and an XML coverage report.
- Optional Docker Compose file that runs the build in a JDK 25 container.

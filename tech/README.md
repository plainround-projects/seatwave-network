# Technology notes for seatwave-network

These notes teach every technology this repository uses, from zero, through this repository's own files. They assume you have never seen a terminal or Java. Each file follows the same nine headings: what it is, why this repository uses it, where it appears, how it works underneath, a walkthrough of this repository's use, try it yourself, mistakes people make, check yourself, and further reading.

The repository is the hands-on project of Module 5, "How bytes travel", of a system design course. It serves a ticket-booking case called SeatWave over HTTP and HTTPS on the loopback address, puts a delay link in front of it to stand for distance, times each phase of a request with `curl` and with Java, models page loads over HTTP/1.1, HTTP/2 and HTTP/3 with packet loss, and shows the TLS failures a client must refuse. It is Java 25 with Gradle, a small shell script, and no framework.

## The files

**The language, its network library and its data formats**

| File | Role |
|---|---|
| `java.md` | Java 25 from zero: class, package, method, exception, records, enums, sealed interfaces, `switch` with patterns, generics, lambdas, streams, `Optional`, immutable collections, try-with-resources, `Duration`, virtual threads and the small concurrency tools, Javadoc, and the minimum Java version of each feature. |
| `java-networking-and-tls.md` | The network ideas, with this code as the examples: IP address, port, loopback, TCP and its handshake, sockets, HTTP, the JDK's HTTP server and client, TLS, certificates, key stores and trust stores, `SSLContext`, why a certificate is refused, timeouts on every call, and what JDK 25 does and does not support (HTTP/2, HTTP/3). |
| `result4j.md` | The result4j 1.2.0 library: a `Result` that is either a value or an error, and why expected failures are values here and exceptions are kept for bugs. |
| `java-properties.md` | The `.properties` format and all seven properties files under `config/`, key by key. |
| `targets-files.md` | The two targets files under `config/`: their columns, the rule that a public site may only be its home page, and how the script and the report read them. |

**The code walkthroughs**

| File | Role |
|---|---|
| `java-server-and-link-walkthrough.md` | The `server` and `link` packages, line by line: the JSON bodies, the JDK web server over HTTP and HTTPS, the `serve` command, and the delay link with its queues and virtual threads. |
| `java-timing-walkthrough.md` | The `timing` package, line by line: the Java timing client, a new client per run, warm-up, and the connection count. |
| `java-tls-walkthrough.md` | The `tls` package, line by line: the server and client `SSLContext`, the trust store with one CA, and the expired, wrong-name and old-version handshakes. |
| `java-report-walkthrough.md` | The `report` package, line by line: parsing a `curl -w` line, the phases, the median rule and the two tables. |
| `java-model-and-config-walkthrough.md` | The `model` and `config` packages, line by line: the page model with its round-trip arithmetic, and the settings reader that has no defaults. |

**The build tool and its files**

| File | Role |
|---|---|
| `gradle.md` | Gradle: the build tool, `build.gradle.kts` and the tasks `serve`, `timing`, `tlsFailures`, `report`, `pageModel` and `testCertificates`. |
| `gradle-wrapper.md` | The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) that downloads the right Gradle. |
| `posix-shell.md` | The `gradlew` script, a POSIX shell script. |
| `windows-batch.md` | The `gradlew.bat` script, a Windows batch file. |
| `toml-version-catalog.md` | `gradle/libs.versions.toml`, the one place for dependency versions. |
| `dependency-locking.md` | `gradle.lockfile` and `settings-gradle.lockfile`, which pin exact versions. |
| `spotless-google-java-format.md` | The formatter that the build enforces. |
| `jacoco.md` | The line-coverage tool and its 80 percent floor. |

**The measuring tools**

| File | Role |
|---|---|
| `bash-curl-timings.md` | `scripts/curl-timings.sh`: the Bash script that times each target with `curl -w`, with its guardrails. |
| `curl-and-openssl.md` | `curl` and `openssl s_client`, the two command-line tools that measure and inspect a connection. |
| `keytool-test-certificates.md` | `keytool` and the `testCertificates` task that make the throwaway TEST certificate authority and server certificates in `build/tls`. |

**The tests**

| File | Role |
|---|---|
| `junit.md` | JUnit: how the test engine finds and runs the tests. |
| `assertj.md` | AssertJ: the fluent assertions used in the tests. |
| `tests-report-walkthrough.md` | The tests of the `report` package, line by line. |
| `tests-tls-and-timing-walkthrough.md` | The tests of the `tls` and `timing` packages, line by line. |
| `tests-other-packages-walkthrough.md` | The tests of the `server`, `link`, `model` and `config` packages, line by line. |

**Everything else**

| File | Role |
|---|---|
| `docker-compose.md` | `compose.yaml`, the optional way to run the build in a container. |
| `editorconfig.md` | `.editorconfig`, the editor settings. |
| `git.md` | Git: the version control that tracks the files. |

There is no `awaitility.md` and no `instancio.md`: no test in this repository uses Awaitility or Instancio.

## Reading order

1. **The language first.** `java.md`. Do not skip it: every walkthrough relies on it. Then `java-networking-and-tls.md`, because the whole repository is about a network, and `result4j.md`.
2. **The data formats.** `java-properties.md` and `targets-files.md`: the settings the programs read and the list the script times.
3. **The build tool.** `gradle.md`, `gradle-wrapper.md`, `posix-shell.md`, `windows-batch.md`, `toml-version-catalog.md`, `dependency-locking.md`, `spotless-google-java-format.md`, `jacoco.md`.
4. **The measuring tools.** `curl-and-openssl.md`, `keytool-test-certificates.md`, `bash-curl-timings.md`.
5. **The code walkthroughs.** `java-server-and-link-walkthrough.md` first, because everything else talks to the server and the link. Then `java-timing-walkthrough.md`, `java-tls-walkthrough.md`, `java-report-walkthrough.md` and `java-model-and-config-walkthrough.md`.
6. **The tests.** `junit.md`, `assertj.md`, then `tests-report-walkthrough.md`, `tests-tls-and-timing-walkthrough.md` and `tests-other-packages-walkthrough.md`.
7. **The rest.** `docker-compose.md`, `editorconfig.md`, `git.md`.

## If you have ten minutes

1. `java-networking-and-tls.md`: read "How it works underneath", for the TCP handshake, the HTTP request and response as text, the TLS handshake, why a certificate is refused, and what JDK 25 supports (HTTP/1.1 and HTTP/2 in the client, only HTTP/1.1 in the server, no HTTP/3).
2. `java-server-and-link-walkthrough.md`: read "How it works underneath" and the `DelayLink.java` section: how one machine pretends to be 80 ms away by holding every chunk of bytes in a queue with a due time.
3. `java-model-and-config-walkthrough.md`: read "How it works underneath" for the round-trip arithmetic: 720 ms for HTTP/1.1 on one connection against 320 ms for HTTP/3, from segments, windows and setup round trips.

## How to run what the notes describe

Every command in these notes was run in this repository, on Java 25.0.2 and Gradle through `./gradlew`, on 10 November 2026, on a Mac with an ARM processor (`aarch64`). Timings differ on every run and on every machine: compare the shape, not the digits. Every command stays on the loopback address. The notes never run the script against the public targets file.

```text
./gradlew -q pageModel --args='config/round-trips.properties'
./gradlew -q tlsFailures --args='config/tls-failures.properties'
./gradlew -q timing --args='config/timing.properties'
```

The first prints the page model with no loss (`720` ms for HTTP/1.1 on one connection, `320` for six connections, `400` for HTTP/2 and `320` for HTTP/3 on new connections). The second prints four handshakes: one accepted and three refused, with the reason of each. The third prints the keep-alive table: about 83 ms for a reused request and 250 ms for a new HTTPS one at 80 ms round-trip time.

To serve SeatWave for `curl`, run `./gradlew -q serve --args='config/serve.properties'` in one terminal, and use a second terminal for the `curl` commands in `java-server-and-link-walkthrough.md`. It stops by itself after 300 seconds.

## How these notes were checked

Each walkthrough quotes the source line by line, copied by a script and not retyped. The command `python3 tools/techcov.py <report.json> seatwave-network`, run from the folder that holds all the repositories, counts the non-blank, non-comment lines of every source, script and configuration file and checks that each appears in these notes. The notes of `src/main/java/` and `config/` quote every counted line. Everything stated about the JDK, the HTTP client and server, TLS, `Properties` and result4j 1.2.0 was checked on this machine, by running a small program or by `javap` on the compiled class, or is marked as not checked.

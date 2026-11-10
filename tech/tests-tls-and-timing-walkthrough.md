# Tests of the `tls` and `timing` packages, file by file

## What it is

This file walks through four test files: `TestTls` (a helper, not a test), `TlsFailuresTest` (8 tests), `ReuseProbeTest` (3 tests) and `TimingSettingsTest` (3 tests). Together they prove that the TLS handshake accepts the valid certificate and refuses the three bad ones, that the Java timing client opens exactly one connection per run and reuses it for the second request, and that the settings files are read strictly.

Unlike the `report` tests (`tests-report-walkthrough.md`), these are **integration tests**: they start real servers on the loopback address, open real TCP connections and run a real TLS handshake. **TLS** is the protocol that encrypts a connection and proves the server's identity with a certificate (`keytool-test-certificates.md` explains certificates, key stores and trust stores from zero). A **handshake** is the opening exchange in which the two sides agree on a version and keys.

## Why this repository uses it

Failure mode 4 of the lesson is "TLS that is allowed to fail open". A client that accepts an expired certificate or a certificate for another name is not protected at all. The tests turn the lesson's three refusals into checks that run on every build, and the timing tests prove that what the client measures (a new connection against a reused one) is really what happened.

## Where it appears

| Path | What it does |
| --- | --- |
| `src/test/java/io/plainround/seatwave/network/tls/TestTls.java` | The helper: finds the certificates the build made and builds the server and client TLS contexts. Holds no tests. |
| `src/test/java/io/plainround/seatwave/network/tls/TlsFailuresTest.java` | Four handshakes (valid, expired, wrong name, old version), two bad-file refusals, the settings reader and the settings checks. |
| `src/test/java/io/plainround/seatwave/network/timing/ReuseProbeTest.java` | The timing probe: one connection per run, errors are not timed, zero runs refused. |
| `src/test/java/io/plainround/seatwave/network/timing/TimingSettingsTest.java` | The timing settings file: read, refused with every problem listed, and a full run of two scenarios. |

## How it works underneath

1. Before any test runs, Gradle runs the task `testCertificates` and passes the folder `build/tls` as the system property `seatwave.tls.directory` (`gradle.md`).
2. A test that needs TLS asks `TestTls` for the folder, and builds a **server context** from `seatwave-valid.p12` (a key store with the server's private key and its certificate chain) and a **client context** from `seatwave-test-ca.pem` (a trust store with the test authority only). An `SSLContext` is the JDK object that holds the keys and the trust decisions for one side of TLS.
3. The test starts a `SeatWaveServer` on **port 0**. A port is the number that tells the operating system which program on the machine should get an incoming connection. Port 0 means "pick any free port", so two tests never fight over a port, and the test asks the server which port it got.
4. The test connects with the JDK `HttpClient`, which does the handshake and sends the request. The result is either an answer or an exception.
5. The test closes the server in a `try` block (`try (SeatWaveServer server = ...)`). Anything opened in the round brackets of `try` is closed automatically at the end of the block, even if a check fails.

**How these tests stay deterministic.** Network tests are the classic source of flaky tests: a test that passes today and fails tomorrow with no code change. These stay stable for four reasons.

- **No fixed ports.** Every server uses port 0.
- **They assert outcomes, not speed.** The TLS tests check whether the handshake was accepted or refused and the reason text. The timing tests check how many connections were opened, a count, never "it took under 10 ms".
- **Everything is on the loopback address,** so no other machine, DNS server or firewall can change the result, and nothing can leave the computer.
- **Fresh certificates, relative to today.** The build makes the "expired" certificate expired by two days and the "valid" one valid for 30 days, every day, so the outcome does not depend on the date the test runs.

## Walkthrough of this repository's use

### `TestTls`: the helper

```java
package io.plainround.seatwave.network.tls;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;

/** The TEST certificates the build writes before the tests run, and contexts made from them. */
public final class TestTls {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private TestTls() {}

```

- `public final class TestTls` is `public` so tests in other packages can use it, and `final` so nobody extends it.
- `private static final Duration TIMEOUT = Duration.ofSeconds(5);` is the longest any TLS test waits for an answer. A timeout turns "hangs forever" into a clear failure.
- `private TestTls() {}` is a private constructor with an empty body, so the class cannot be instantiated: it is only a bag of static helper methods.
- The comment above the class says what it is: "The TEST certificates the build writes before the tests run, and contexts made from them."

```java
  /** The folder the {@code testCertificates} task wrote, passed in by the build. */
  public static Path directory() {
    return Path.of(System.getProperty("seatwave.tls.directory"));
  }
```

`System.getProperty("seatwave.tls.directory")` reads the system property that Gradle sets. `Path.of(...)` turns the text into a path object. If the test runs outside Gradle the property is missing and `Path.of(null)` throws, which is the right failure: the certificates do not exist either.

```java
  /** A server context with the valid TEST certificate. */
  public static SSLContext validServer() throws IOException, GeneralSecurityException {
    return TlsMaterial.serverContext(
        directory().resolve("seatwave-valid.p12"), directory().resolve("store.password"));
  }
```

`validServer()` builds a server context. `directory().resolve("seatwave-valid.p12")` is the path of the key store inside the folder, and `resolve("store.password")` is the file holding the random password the build wrote. `TlsMaterial.serverContext` reads the password file, loads the key store and returns the `SSLContext`. `throws IOException, GeneralSecurityException` says the two kinds of failure: a file cannot be read, or the key store cannot be opened.

```java
  /** A client context that trusts only the TEST CA. */
  public static SSLContext client() throws IOException, GeneralSecurityException {
    return TlsMaterial.clientContext(directory().resolve("seatwave-test-ca.pem"));
  }
```

`client()` builds a client context from `seatwave-test-ca.pem`. It trusts **only** the test authority: the JDK's own list of public authorities is not used, so a certificate signed by anyone else is refused.

```java
  /** The lab's settings: a TLS 1.3 server and an old client that offers TLS 1.2 only. */
  public static TlsSettings settings() {
    return new TlsSettings(directory(), List.of("TLSv1.3"), List.of("TLSv1.2"), TIMEOUT);
  }
}
```

`settings()` makes the lab's `TlsSettings`: the certificate folder, the versions the server accepts (`List.of("TLSv1.3")`), the versions the old client offers (`List.of("TLSv1.2")`) and the timeout. So the server speaks TLS 1.3 only, and the "old client" can speak TLS 1.2 only.

### `TlsFailuresTest`: the imports

```java
package io.plainround.seatwave.network.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsFailuresTest {
```

`Properties` is the JDK's key-value text-settings object. `@TempDir` (an annotation from `org.junit.jupiter.api.io`) gives a test a fresh empty folder (`junit.md`). `Result` is the success-or-error type. `SettingsReader` reads typed values from settings (the tests below show what it checks).

### The valid certificate is accepted

```java
  @Test
  void acceptsTheValidCertificateOverTls13() throws Exception {
    HandshakeOutcome outcome = new TlsFailures(TestTls.settings()).attempt(TlsScenario.VALID);

    assertThat(outcome.isAccepted()).isTrue();
    assertThat(outcome.detail()).startsWith("200 over TLSv1.3 with TLS_");
  }
```

- `new TlsFailures(TestTls.settings()).attempt(TlsScenario.VALID)` starts a TLS server with the valid certificate on a free port, sends `GET /health` with a client that trusts the test authority, and returns a `HandshakeOutcome`.
- `outcome.isAccepted()` must be `true`.
- `outcome.detail()` must start with `200 over TLSv1.3 with TLS_`: the status code, the TLS version that was agreed, and the name of the cipher suite (the README shows `TLS_AES_256_GCM_SHA384`). The test checks only the start, so a different cipher in a future JDK does not break it.
- Why it matters: this proves the setup works at all. If the valid certificate were refused, the three refusals below would prove nothing, because everything would be refused.

### The expired certificate is refused

```java
  @Test
  void refusesAnExpiredCertificate() throws Exception {
    HandshakeOutcome outcome = new TlsFailures(TestTls.settings()).attempt(TlsScenario.EXPIRED);

    assertThat(outcome.isAccepted()).isFalse();
    assertThat(outcome.detail()).contains("certificate_expired");
  }
```

The server presents a certificate that ended two days before it was made. The client checks the dates, so it refuses before any HTTP is sent. `outcome.isAccepted()` is `false`, and the detail contains `certificate_expired`, the name of the TLS alert (the README shows the full text: `(certificate_expired) PKIX path validation failed: ... validity check failed`). `PKIX` is the standard rule set for validating a chain of certificates.

### The certificate for another name is refused

```java
  @Test
  void refusesACertificateIssuedForAnotherName() throws Exception {
    HandshakeOutcome outcome = new TlsFailures(TestTls.settings()).attempt(TlsScenario.WRONG_NAME);

    assertThat(outcome.isAccepted()).isFalse();
    assertThat(outcome.detail()).contains("No subject alternative names matching IP address");
  }
```

The certificate is signed by the trusted authority and in date, but it names only `seatwave.test`. The client connects to the address `127.0.0.1`, which the certificate does not list, so it refuses. The detail contains `No subject alternative names matching IP address`. The README shows the complete message: `No subject alternative names matching IP address 127.0.0.1 found`. This is the check that stops a server from presenting someone else's valid certificate.

### A client that is too old is refused

```java
  @Test
  void refusesAClientThatOffersOnlyAnOlderVersion() throws Exception {
    HandshakeOutcome outcome = new TlsFailures(TestTls.settings()).attempt(TlsScenario.OLD_VERSION);

    assertThat(outcome.isAccepted()).isFalse();
    assertThat(outcome.detail()).contains("protocol_version");
  }
```

The certificate is the valid one, but the client is limited to TLS 1.2 and the server accepts TLS 1.3 only. They share no version, so the handshake fails with the TLS alert `protocol_version`. The detail contains that word. This is the third refusal of the lesson: a server may refuse old versions that are known to be weak.

### Bad files are refused

```java
  @Test
  void refusesATrustFileThatIsNotACertificate(@TempDir Path folder) throws Exception {
    Path notACertificate =
        Files.writeString(folder.resolve("ca.pem"), "not a certificate", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> TlsMaterial.clientContext(notACertificate))
        .isInstanceOf(java.security.cert.CertificateException.class);
  }
```

- `Files.writeString(folder.resolve("ca.pem"), "not a certificate", StandardCharsets.UTF_8)` writes a text file into the temporary folder. It is a `.pem` in name only.
- `TlsMaterial.clientContext(notACertificate)` must throw `java.security.cert.CertificateException`: the trust file is not a certificate. A client must never start with a trust file it cannot read, and it must never fall back to trusting everything.
- The `() ->` is a lambda that `assertThatThrownBy` runs to catch the exception (`assertj.md`).

```java
  @Test
  void refusesAnEmptyPasswordFile(@TempDir Path folder) throws Exception {
    Path empty = Files.writeString(folder.resolve("store.password"), "\n");

    assertThatThrownBy(() ->
            TlsMaterial.serverContext(TestTls.directory().resolve("seatwave-valid.p12"), empty))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("empty");
  }
```

The password file contains only a line break. The helper reads the file, strips whitespace and finds an empty password, so `serverContext` throws an `IOException` whose message contains `empty`. The real key store of the build is used (`TestTls.directory().resolve("seatwave-valid.p12")`); only the password file is replaced, so the test proves it is the empty password that is refused.

### The settings reader lists every problem

```java
  @Test
  void readsTheSettingsAndListsEveryProblem() {
    Properties good = new Properties();
    good.setProperty("tls.directory", "build/tls");
    good.setProperty("tls.server.protocols", "TLSv1.3");
    good.setProperty("tls.old.client.protocols", "TLSv1.2");
    good.setProperty("client.timeout.millis", "5000");
    Properties bad = new Properties();
    bad.putAll(good);
    bad.setProperty("tls.server.protocols", "SSLv3");
    bad.remove("client.timeout.millis");

    TlsSettings read = TlsSettings.from(new SettingsReader(good)).discardError().orElseThrow();
    Result<TlsSettings, List<String>> refused = TlsSettings.from(new SettingsReader(bad));

    assertThat(read.timeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(read.certificateAuthority()).hasFileName("seatwave-test-ca.pem");
    assertThat(((Result.Error<TlsSettings, List<String>>) refused).error())
        .containsExactly(
            "tls.server.protocols has SSLv3, but allows only TLSv1.3, TLSv1.2",
            "client.timeout.millis is missing");
  }
```

- `good` is a `Properties` object with the four keys `tls.directory`, `tls.server.protocols`, `tls.old.client.protocols` and `client.timeout.millis`. `bad` is a copy with `SSLv3` as the server version (not allowed) and the timeout key removed.
- `TlsSettings.from(new SettingsReader(good))` returns a `Result`. `.discardError().orElseThrow()` turns a success into the settings, or throws (`result4j.md`).
- The checks: the timeout is 5 seconds (`5000` milliseconds), and the certificate authority path has the file name `seatwave-test-ca.pem`.
- For `bad`, the error list must contain **exactly** these two messages in this order: `tls.server.protocols has SSLv3, but allows only TLSv1.3, TLSv1.2` and `client.timeout.millis is missing`. So a person with two mistakes in a settings file sees both at once, not one at a time. The cast `(Result.Error<TlsSettings, List<String>>) refused` takes the error list out of the result.

### Impossible settings in code are refused too

```java
  @Test
  void refusesUnknownOrEmptyVersionsInCode() {
    Path folder = Path.of("build/tls");
    Duration timeout = Duration.ofSeconds(1);

    assertThatThrownBy(() -> new TlsSettings(folder, List.of(), List.of("TLSv1.2"), timeout))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new TlsSettings(folder, List.of("TLSv1.0"), List.of("TLSv1.2"), timeout))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

Settings built directly in code (not from a file) are checked by the constructor of `TlsSettings`: an empty list of server versions, or the version `TLSv1.0`, throws `IllegalArgumentException`. The settings file and the code both refuse the same things, so no path creates a server that accepts a weak version.

### `ReuseProbeTest`: one connection per run

```java
package io.plainround.seatwave.network.timing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.network.link.DelayLink;
import io.plainround.seatwave.network.link.HandshakeDelay;
import io.plainround.seatwave.network.link.LinkSettings;
import io.plainround.seatwave.network.report.ReuseSample;
import io.plainround.seatwave.network.server.SeatWaveServer;
import io.plainround.seatwave.network.tls.TestTls;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReuseProbeTest {

  private static final TimingSettings.Timeouts TIMEOUTS =
      new TimingSettings.Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
  private static final int RUNS = 3;

```

`TimingSettings.Timeouts` holds the connect timeout and the request timeout, 5 seconds each. `RUNS = 3` is how many runs the probe makes.

```java
  @Test
  void opensOneConnectionPerRunAndReusesItForTheSecondRequest() throws Exception {
    try (SeatWaveServer server =
            SeatWaveServer.startTls(0, TestTls.validServer(), List.of("TLSv1.3"));
        DelayLink link =
            DelayLink.start(
                0, server.port(), new LinkSettings(Duration.ZERO, HandshakeDelay.NONE, 8))) {
      ReuseProbe probe = new ReuseProbe(TestTls.client(), TIMEOUTS);
      URI uri = URI.create("https://127.0.0.1:" + link.port() + "/events/1/seats?section=A");

      List<ReuseSample> samples = probe.measure(uri, HttpClient.Version.HTTP_2, RUNS);

      // Two requests per run, but one connection per run: the second request reused it.
      assertThat(samples).hasSize(RUNS);
      assertThat(link.connectionsAccepted()).isEqualTo(RUNS);
      assertThat(samples).allSatisfy(sample -> assertThat(sample.protocol()).isEqualTo("HTTP/1.1"));
    }
  }
```

- The `try` opens two things that close automatically: a TLS `SeatWaveServer` on a free port that accepts TLS 1.3, and a `DelayLink` in front of it. The link is a forwarder on the loopback address (`DelayLink.start(0, server.port(), settings)`: it listens on a free port and passes the bytes to the server's port). Here its delay is `Duration.ZERO`, so it adds no time. Its role in this test is to **count the connections it accepts**: `link.connectionsAccepted()`. The limit of 8 connections is the third value of `LinkSettings`.
- `new ReuseProbe(TestTls.client(), TIMEOUTS)` is the client under test. `probe.measure(uri, HttpClient.Version.HTTP_2, RUNS)` makes three runs: each run builds a new `HttpClient`, sends one request on a new connection, then one on the same connection. The client asks for HTTP/2.
- The comment says the key claim: "Two requests per run, but one connection per run: the second request reused it." The test checks that there are three samples (`hasSize(RUNS)`), that the link accepted exactly 3 connections (not 6), and that every sample reports the protocol `HTTP/1.1`, because the JDK server answers HTTP/1.1 even when asked for HTTP/2.
- The count of connections is exact and does not depend on speed. That makes it a good basis for a test of a timing program: the test cannot measure time reliably, but it can prove the thing that the timing is about.

```java
  @Test
  void stopsRatherThanTimeAnErrorResponse() throws Exception {
    try (SeatWaveServer server = SeatWaveServer.startPlain(0)) {
      ReuseProbe probe = new ReuseProbe(TestTls.client(), TIMEOUTS);
      URI missing = server.baseUri().resolve("events/9/seats?section=A");

      assertThatThrownBy(() -> probe.measure(missing, HttpClient.Version.HTTP_1_1, 1))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("answered 404");
    }
  }
```

The server is plain HTTP and the URL is an event that does not exist (`events/9/seats?section=A`), so the answer is 404. The probe must stop rather than time an error page: it throws an `IOException` whose message contains `answered 404`. A timing of a "not found" page would be a wrong number that looks like a measurement.

```java
  @Test
  void refusesZeroRuns() throws Exception {
    ReuseProbe probe = new ReuseProbe(TestTls.client(), TIMEOUTS);

    assertThatThrownBy(
            () -> probe.measure(URI.create("http://127.0.0.1:1/"), HttpClient.Version.HTTP_1_1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

`measure` with 0 runs throws `IllegalArgumentException`. The URL `http://127.0.0.1:1/` is never contacted: the check on the number of runs comes first, so the test needs no server.

### `TimingSettingsTest`: the settings file

```java
package io.plainround.seatwave.network.timing;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import io.plainround.seatwave.network.link.HandshakeDelay;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class TimingSettingsTest {

```

`HandshakeDelay` is the choice of whether a link charges the round trip of the TCP handshake (`ARCHITECTURE.md`). `HttpClient.Version` is the JDK's list of HTTP versions.

```java
  @Test
  void readsTheProjectsTimingSettings() throws Exception {
    TimingSettings settings =
        TimingSettings.from(
                new SettingsReader(SettingsReader.load(Path.of("config/timing.properties"))))
            .discardError()
            .orElseThrow();

    assertThat(settings.versions())
        .containsExactly(HttpClient.Version.HTTP_1_1, HttpClient.Version.HTTP_2);
    assertThat(settings.scenarios())
        .extracting(Scenario::name)
        .containsExactly("simple", "local-tls", "distant-http", "distant-tls");
    Scenario distant = settings.scenarios().getLast();
    assertThat(distant.scheme()).isEqualTo(Scheme.HTTPS);
    assertThat(distant.oneWayDelay()).isEqualTo(Duration.ofMillis(40));
    assertThat(distant.handshakeDelay()).isEqualTo(HandshakeDelay.ONE_ROUND_TRIP);
  }
```

The test reads the real file `config/timing.properties` (Gradle runs tests from the project folder). It checks the lab's choices: the versions asked for are `HTTP_1_1` and `HTTP_2` in that order; the four scenarios are `simple`, `local-tls`, `distant-http` and `distant-tls`; and the last scenario, `distant-tls`, uses HTTPS, a one-way delay of 40 ms and the handshake rule `ONE_ROUND_TRIP`. `settings.scenarios().getLast()` is the last element of the list. If someone edits the settings file in a way that changes the lab, this test says so.

```java
  @Test
  void listsEveryInvalidKey() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/timing.properties"));
    properties.setProperty("probe.path", "/../etc/passwd");
    properties.setProperty("probe.runs", "2");
    properties.setProperty("scenario.simple.scheme", "ftp");

    Result<TimingSettings, List<String>> read = TimingSettings.from(new SettingsReader(properties));

    assertThat(((Result.Error<TimingSettings, List<String>>) read).error())
        .containsExactly(
            "probe.path must match /[a-z0-9/]*(\\?[a-z]+=[A-Z0-9]+)?, but was /../etc/passwd",
            "probe.runs must be from 3 to 1000, but was 2",
            "scenario.simple.scheme has an unknown value: ftp");
  }
```

The test loads the real file, then makes three mistakes in the copy in memory: a path with `..` (a way to climb out of a folder), a run count of 2 (the minimum is 3, the same rule as the report's "at least 3 runs") and the scheme `ftp`. `TimingSettings.from(...)` must return **all three** problems, in the order of the keys: the path must match a pattern (`/[a-z0-9/]*(\?[a-z]+=[A-Z0-9]+)?`), `probe.runs must be from 3 to 1000, but was 2`, and `scenario.simple.scheme has an unknown value: ftp`. The path rule means the program can only ask the server for SeatWave paths.

```java
  @Test
  void runsEveryScenarioOnThisMachineAndCountsConnections() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/timing.properties"));
    properties.setProperty("tls.directory", System.getProperty("seatwave.tls.directory"));
    properties.setProperty("probe.warmup.runs", "0");
    properties.setProperty("scenario.names", "simple,local-tls");
    TimingSettings settings =
        TimingSettings.from(new SettingsReader(properties)).discardError().orElseThrow();

    String table = TimingCli.run(settings);

    assertThat(table)
        .contains("every request: GET /events/1/seats?section=A")
        .containsPattern("simple +HTTP_1_1 +HTTP/1.1 +7 .* 7\\n")
        .containsPattern("local-tls +HTTP_2 +HTTP/1.1 +7 .* 7\\n");
  }
}
```

This is the test that runs the Java timing client end to end on this machine.

- It loads `config/timing.properties` and changes three keys in memory. `tls.directory` becomes the build's certificate folder (the absolute path from the system property). `probe.warmup.runs` becomes `0`, so no warm-up runs are made, which keeps the test short. `scenario.names` becomes `simple,local-tls`: only the two scenarios that do not use the delay link, so nothing sleeps.
- `TimingCli.run(settings)` runs the whole lab and returns the table as a text, with no printing and no `System.exit`. That is why this method of the command-line class is tested while its `main` is not (`jacoco.md`).
- The table is checked by shape, not by numbers: it contains the line `every request: GET /events/1/seats?section=A`, and `containsPattern` checks that the row of `simple` with `HTTP_1_1` and the row of `local-tls` with `HTTP_2` each show `HTTP/1.1` as the answered version, 7 runs, and end with `7` connections opened. In a regular expression `+` means "one or more", `.*` means "anything", and `\\n` is a line break. The decimals in the middle are free to change from run to run; the counts must be exact.
- The `HTTP/1.1` in the `HTTP_2` row is the honest result: the JDK server does not offer HTTP/2, so the client falls back.

## Try it yourself

All ran in a scratch copy on 10 November 2026.

1. Run the TLS and timing tests:

```
./gradlew --offline cleanTest test --tests '*tls*' --tests '*timing*'
```

   Expected: 14 lines ending in `PASSED` (8 `TlsFailuresTest`, 3 `ReuseProbeTest`, 3 `TimingSettingsTest`) and `BUILD SUCCESSFUL`.

2. See the outcomes of the four handshakes from the command line:

```
./gradlew --offline -q tlsFailures --args='config/tls-failures.properties'
```

   Result: the line `Server accepts TLSv1.3; the client trusts only the TEST CA and checks dates and names.` and four rows. `valid        accepted  200 over TLSv1.3 with TLS_AES_256_GCM_SHA384`, then `expired      refused   (certificate_expired) PKIX path validation failed: ...`, `wrong_name   refused   (certificate_unknown) No subject alternative names matching IP address 127.0.0.1 found` and `old_version  refused   (protocol_version) Received fatal alert: protocol_version`. The alert for the wrong name is `certificate_unknown`, so the test checks the text after it.

3. Make a test notice a wrong expectation, in a scratch copy only. Change `"certificate_expired"` to `"certificate_unknown"` in `TlsFailuresTest` and run `./gradlew --offline test --tests '*TlsFailuresTest'`. Expected: `refusesAnExpiredCertificate() FAILED`. Undo the edit.

4. See how the tests find the certificates (the wiring, not a run):

```
grep -n "seatwave.tls.directory" build.gradle.kts src/test/java/io/plainround/seatwave/network/tls/TestTls.java src/test/java/io/plainround/seatwave/network/timing/TimingSettingsTest.java
```

   Expected: one line in each of the three files.

## Mistakes people make

- **A fixed port in a test.** Two builds on one machine then collide. Port 0 avoids it.
- **Asserting an upper bound on time.** It fails on a busy laptop. These tests assert counts and outcomes.
- **Turning off certificate checks to make a TLS test pass.** The client here trusts the test authority only, and the refusals are the point.
- **Not closing a server.** A leaked server keeps its port and its threads. `try ( ... )` closes them.
- **Committing the test certificates.** They are made by the build on each machine (`keytool-test-certificates.md`).
- **Checking the whole text of a JDK error.** The tests check a stable word of it (`certificate_expired`, `protocol_version`), not the full sentence, so a JDK update that rewords the rest does not break them.

## Check yourself

1. *Why do the TLS tests use port 0?* The operating system picks a free port, so tests never collide.
2. *Which three handshakes must be refused, and why?* Expired (dates are checked), wrong name (the name must match the address) and old version (no shared TLS version).
3. *How does `ReuseProbeTest` prove reuse without measuring time?* The link counts the connections it accepted: 3 for 3 runs of 2 requests each.
4. *Why does `TimingSettingsTest` check the table with a pattern?* The decimals change from run to run; the number of runs and of connections must be exact.
5. *Why does `TestTls` hold no tests?* It is a helper that several test classes share.

## Further reading

- Java `HttpClient` (JDK 25): <https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html>
- JSSE reference guide (TLS in Java): <https://docs.oracle.com/en/java/javase/25/security/java-secure-socket-extension-jsse-reference-guide.html>
- try-with-resources: <https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html#jls-14.20.3>

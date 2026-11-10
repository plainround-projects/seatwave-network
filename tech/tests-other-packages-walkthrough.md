# Tests of the `config`, `link`, `model` and `server` packages, file by file

## What it is

This file walks through five test classes: `SettingsReaderTest` (5 tests, package `config`), `DelayLinkTest` (5, package `link`), `PageLoadModelTest` (8, package `model`), `SeatMapJsonTest` (3, package `server`) and `SeatWaveServerTest` (9, package `server`). That is 30 of the 62 tests. The other 32 are in `tests-report-walkthrough.md` (18) and `tests-tls-and-timing-walkthrough.md` (14).

An **automated test** runs a piece of the program and checks the result (`junit.md` and `assertj.md` explain the tools). Two kinds appear here. A **unit test** checks one piece with no network (`SettingsReaderTest`, `PageLoadModelTest`, `SeatMapJsonTest`). An **integration test** starts real parts and lets them talk over a real TCP connection on the loopback address, the address `127.0.0.1` that always means "this computer" (`DelayLinkTest`, `SeatWaveServerTest`).

## Why this repository uses it

Each package carries one promise of the lesson. `config` promises that a mistake in a settings file is reported completely and never fixed silently. `server` promises that SeatWave answers exactly the bytes Module 1 measured (36,894 bytes) with a `Server-Timing` header. `link` promises that the delay link adds at least the delay it was told to add and counts every connection. `model` promises that the page model's round-trip counts follow its stated rules and give the same answer for the same seed.

## Where it appears

| Path | What it tests |
| --- | --- |
| `src/test/java/io/plainround/seatwave/network/config/SettingsReaderTest.java` | `SettingsReader`: every kind of value, every kind of problem, the file loader. |
| `src/test/java/io/plainround/seatwave/network/link/DelayLinkTest.java` | `DelayLink`: the delay, the handshake charge, the connection limit, a dead upstream, the settings. |
| `src/test/java/io/plainround/seatwave/network/model/PageLoadModelTest.java` | `PageLoadModel`: round trips, head-of-line blocking, loss, seeds, the printed assumptions, the settings. |
| `src/test/java/io/plainround/seatwave/network/server/SeatMapJsonTest.java` | `SeatMapJson`: the seat map and the event list, byte for byte. |
| `src/test/java/io/plainround/seatwave/network/server/SeatWaveServerTest.java` | `SeatWaveServer`: status codes, headers, `HEAD`, refusals, loopback only, HTTPS. |

## How it works underneath

For the integration tests, in order:

1. The test starts a `SeatWaveServer` on **port 0**. A **port** is the number that tells the operating system which program should receive an incoming connection. Port 0 means "choose any free port", and the test asks the server which one it got (`server.port()`).
2. A `DelayLink`, if the test needs one, listens on a second free port and forwards every byte to the server's port after holding it for a fixed delay.
3. The test sends a request with the JDK `HttpClient` and gets an answer or an exception.
4. The `try ( ... )` block closes the link and the server at its end, even when a check fails.

**How these tests stay deterministic.** A test of a network program is deterministic when it gives the same result on every run and every machine. Four habits do that here.

- **Free ports, loopback only.** No two tests share a port, and no byte leaves the machine.
- **Lower bounds for time, exact values for everything else.** The link test says "at least 40 ms", never "40 ms". The elapsed time of a real exchange has noise; a bound on one side only cannot fail on a slow machine, because noise only adds.
- **Counts, not durations.** `link.connectionsAccepted()` and `connectionsRefused()` are exact whatever the speed.
- **A seed for chance.** The page model draws random losses from a generator that starts from a fixed seed, so "random" results repeat exactly.

## Walkthrough of this repository's use

### `SettingsReaderTest`: the imports and the first test

```java
package io.plainround.seatwave.network.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.network.model.Protocol;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsReaderTest {

  private static final int TEN = 10;
```

`SettingsReader` reads typed values from a `Properties` object, which is the JDK's key-value store for text settings (a file of lines like `rtt.millis=80`). The imports show what the test needs: `Protocol` (an enum from the model package, used as a sample of "a list of names"), `Files` and `Path` to write a file, `Duration` for time spans, and `@TempDir` for a temporary folder. `private static final int TEN = 10;` is the upper limit used in several checks. A named constant is clearer than writing `10` many times.

```java
  @Test
  void readsEveryKindOfValue() {
    SettingsReader reader =
        reader(
            "count",
            "7",
            "rates",
            "200, 320",
            "wait.millis",
            "40",
            "share",
            "0.05",
            "modes",
            "http2,http3");

    assertThat(reader.intBetween("count", 1, TEN)).isEqualTo(7);
    assertThat(reader.intListBetween("rates", 1, Integer.MAX_VALUE)).containsExactly(200, 320);
    assertThat(reader.millisBetween("wait.millis", 0, Integer.MAX_VALUE))
        .isEqualTo(Duration.ofMillis(40));
    assertThat(reader.decimalBetween("share", 0, 1)).isEqualTo(0.05);
    assertThat(reader.enumList("modes", Protocol.class))
        .containsExactly(Protocol.HTTP2, Protocol.HTTP3);
    assertThat(reader.problems()).isEmpty();
  }
```

- The helper `reader(...)` (at the end of the file) takes keys and values alternately and builds a reader. Here: `count` is `7`, `rates` is `200, 320`, `wait.millis` is `40`, `share` is `0.05` and `modes` is `http2,http3`.
- `intBetween("count", 1, TEN)` reads a whole number that must be from 1 to 10: 7. `intListBetween("rates", 1, Integer.MAX_VALUE)` reads a comma-separated list of whole numbers (spaces around items are allowed): 200 and 320, in order (`containsExactly`). `millisBetween("wait.millis", 0, Integer.MAX_VALUE)` reads milliseconds as a `Duration`: 40 ms. `decimalBetween("share", 0, 1)` reads a decimal number: 0.05. `enumList("modes", Protocol.class)` turns the words `http2,http3` into the enum values `Protocol.HTTP2` and `Protocol.HTTP3`, ignoring the letter case.
- The last line: `reader.problems()` is empty. Valid input produces no complaint.

### Every problem in one run

```java
  @Test
  void reportsEveryProblemInOneRun() {
    SettingsReader reader =
        reader(
            "count",
            "70",
            "word",
            "seven",
            "rates",
            "200,,320",
            "share",
            "2",
            "modes",
            "green",
            "list",
            "1,x",
            "big",
            "1,99",
            "decimal",
            "half");

    reader.intBetween("count", 1, TEN);
    reader.intBetween("word", 1, TEN);
    reader.intBetween("absent", 1, TEN);
    reader.intListBetween("rates", 1, TEN);
    reader.intListBetween("list", 1, TEN);
    reader.intListBetween("big", 1, TEN);
    reader.decimalBetween("share", 0, 1);
    reader.decimalBetween("decimal", 0, 1);
    reader.enumList("modes", Protocol.class);

    assertThat(reader.problems())
        .containsExactly(
            "count must be from 1 to 10, but was 70",
            "word must be a whole number, but was seven",
            "absent is missing",
            "rates has an empty item",
            "list must list whole numbers, but one was x",
            "every value of big must be from 1 to 10, but one was 99",
            "share must be from 0.0 to 1.0, but was 2",
            "decimal must be a decimal number, but was half",
            "modes has an unknown value: green");
  }
```

This is the central test of the reader. The settings hold eight wrong values on purpose, and the code reads nine keys (the ninth key, `absent`, is missing). The test expects **exactly** nine messages, in order:

| Key read | Value | Message |
| --- | --- | --- |
| `count` | `70` | `count must be from 1 to 10, but was 70` |
| `word` | `seven` | `word must be a whole number, but was seven` |
| `absent` | (missing) | `absent is missing` |
| `rates` | `200,,320` | `rates has an empty item` |
| `list` | `1,x` | `list must list whole numbers, but one was x` |
| `big` | `1,99` | `every value of big must be from 1 to 10, but one was 99` |
| `share` | `2` | `share must be from 0.0 to 1.0, but was 2` |
| `decimal` | `half` | `decimal must be a decimal number, but was half` |
| `modes` | `green` | `modes has an unknown value: green` |

Why it matters: there are no defaults in this repository. A missing or wrong key is a problem, never a silent fallback, and a learner sees all problems at once instead of fixing one and running again. `containsExactly` also fixes the **order** (the order in which the keys were read), so the output is stable.

### Words, patterns and single constants

```java
  @Test
  void readsWordsPatternsAndSingleConstants() {
    SettingsReader reader =
        reader("versions", "TLSv1.3, TLSv1.2", "path", "/events", "mode", "http3", "bad", "/x y");

    assertThat(reader.wordsIn("versions", List.of("TLSv1.3", "TLSv1.2")))
        .containsExactly("TLSv1.3", "TLSv1.2");
    assertThat(reader.textMatching("path", java.util.regex.Pattern.compile("/[a-z]+")))
        .isEqualTo("/events");
    assertThat(reader.enumValue("mode", Protocol.class, Protocol.HTTP2)).isEqualTo(Protocol.HTTP3);
    assertThat(reader.problems()).isEmpty();
    assertThat(reader.textMatching("bad", java.util.regex.Pattern.compile("/[a-z]+"))).isEmpty();
    assertThat(reader.wordsIn("versions", List.of("TLSv1.3"))).isEmpty();
    assertThat(reader.enumValue("versions", Protocol.class, Protocol.HTTP2))
        .isEqualTo(Protocol.HTTP2);
    assertThat(reader.textMatching("absent", java.util.regex.Pattern.compile("x"))).isEmpty();
    assertThat(reader.problems()).hasSize(4);
  }
```

- `wordsIn("versions", List.of("TLSv1.3", "TLSv1.2"))` reads a list of words that must each be on an allowed list: `TLSv1.3, TLSv1.2` is accepted. `textMatching("path", Pattern.compile("/[a-z]+"))` reads one text that must match a regular expression (a pattern for text; here a slash and one or more lower-case letters): `/events` matches. `enumValue("mode", Protocol.class, Protocol.HTTP2)` reads one enum value and takes a fallback to return when the value is bad: `http3` gives `Protocol.HTTP3`.
- Then four invalid cases, each returning a harmless stand-in and adding one problem: the text `/x y` does not match the pattern (an empty text comes back), a word list with `TLSv1.2` where only `TLSv1.3` is allowed (an empty list), the value `TLSv1.3, TLSv1.2` read as a `Protocol` (the fallback `HTTP2`), and an absent key with a pattern (empty text). `hasSize(4)` checks that exactly four problems were recorded, one for each.
- The comment in the code of the reader says why: "When a key is invalid, the method records the problem and returns a harmless stand-in value; the caller must check `problems()` before using any value."
- The fully qualified name `java.util.regex.Pattern` is written out in the test instead of being imported, which is allowed.

### Two constants where one is expected

```java
  @Test
  void refusesTwoConstantsWhereOneIsExpected() {
    SettingsReader reader = reader("mode", "http2,http3");

    assertThat(reader.enumValue("mode", Protocol.class, Protocol.HTTP2)).isEqualTo(Protocol.HTTP2);
    assertThat(reader.problems()).containsExactly("mode takes one value, but has 2");
  }
```

`mode` is `http2,http3`: two names where one is expected. The reader returns the fallback `HTTP2` and records `mode takes one value, but has 2`. This stops a person who writes two values from silently getting the first one.

### Loading a file as UTF-8

```java
  @Test
  void loadsAFileAsUtf8(@TempDir Path folder) throws Exception {
    Path file = folder.resolve("settings.properties");
    Files.writeString(file, "# a comment\nname=Arena Night\n", StandardCharsets.UTF_8);

    Properties loaded = SettingsReader.load(file);

    assertThat(new SettingsReader(loaded).text("name")).isEqualTo("Arena Night");
  }
```

- `@TempDir Path folder` is a fresh folder that JUnit deletes after the test (`junit.md`).
- `Files.writeString(file, "# a comment\nname=Arena Night\n", StandardCharsets.UTF_8)` writes a settings file of two lines: a comment (a line that starts with `#` is ignored) and `name=Arena Night`. `\n` is the line break.
- `SettingsReader.load(file)` reads the file as UTF-8 text into a `Properties` object. The test then reads `name` back with `text("name")` and expects `Arena Night`. This proves that the comment is ignored, a space inside a value is kept, and the file is read with a stated character set, not the machine's default.

### The helper

```java
  private static SettingsReader reader(String... keysAndValues) {
    Properties properties = new Properties();
    List<String> pairs = List.of(keysAndValues);
    for (int pairIndex = 0; pairIndex < pairs.size(); pairIndex += 2) {
      properties.setProperty(pairs.get(pairIndex), pairs.get(pairIndex + 1));
    }
    return new SettingsReader(properties);
  }
}
```

`reader(String... keysAndValues)` takes any number of texts (`String...` is a **varargs** parameter: the caller lists them separated by commas). It makes a `Properties` object and sets one property for every pair (`pairIndex` 0, 2, 4, ...; the key is at `pairIndex` and the value at `pairIndex + 1`). It returns a `SettingsReader` on it. The loop variable is named `pairIndex` and increases by 2.

### `DelayLinkTest`: the constants and a delayed request

```java
package io.plainround.seatwave.network.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.network.server.SeatWaveServer;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DelayLinkTest {

  private static final Duration ONE_WAY = Duration.ofMillis(20);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final int SOCKET_TIMEOUT_MILLIS = 5_000;

```

`DelayLink` is a forwarder on the loopback address: it listens on one port, connects to the real server's port, and holds every chunk of bytes for a one-way delay before passing it on (`ARCHITECTURE.md`). The constants: `ONE_WAY` is 20 ms (so a round trip is 40 ms), `TIMEOUT` is 5 seconds for the HTTP client, and `SOCKET_TIMEOUT_MILLIS` is 5,000 for raw sockets.

```java
  @Test
  void addsAtLeastOneRoundTripToARequestAndCountsTheConnection() throws Exception {
    try (SeatWaveServer server = SeatWaveServer.startPlain(0);
        DelayLink link =
            DelayLink.start(0, server.port(), new LinkSettings(ONE_WAY, HandshakeDelay.NONE, 4));
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
      long startNanos = System.nanoTime();

      HttpResponse<byte[]> response =
          client.send(request(link), HttpResponse.BodyHandlers.ofByteArray());

      Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).hasSize(36_894);
      // A lower bound only: the link must have held the request and the reply 20 ms each.
      assertThat(elapsed).isGreaterThanOrEqualTo(ONE_WAY.multipliedBy(2));
      assertThat(link.connectionsAccepted()).isEqualTo(1);
    }
  }
```

- `try ( ... )` opens three resources: a plain `SeatWaveServer` on a free port, a `DelayLink` in front of it (`DelayLink.start(0, server.port(), new LinkSettings(ONE_WAY, HandshakeDelay.NONE, 4))`: a free port, the server's port, a 20 ms delay, no extra handshake charge and room for 4 connections) and an `HttpClient`. All three are closed at the end of the block.
- `System.nanoTime()` is the JDK's stopwatch: a count of nanoseconds that is only meaningful as a difference of two readings. The test reads it before and after `client.send(...)`.
- `request(link)` is the helper at the end of the file, which builds a `GET` for the seat map at the link's port.
- The checks: the status is 200, the body has 36,894 bytes, and the elapsed time is at least `ONE_WAY.multipliedBy(2)`, which is 40 ms: the request waited 20 ms on the way in, and the reply 20 ms on the way out. The comment says "A lower bound only". And `link.connectionsAccepted()` is exactly 1.
- Why only a lower bound: a busy machine can make the exchange slower than 40 ms, but nothing can make it faster than the delays the link added. A test with an upper bound would fail on a slow build machine without any defect.

### The handshake's round trip is charged to the first bytes

```java
  @Test
  void chargesTheHandshakesRoundTripToTheFirstBytesOfANewConnection() throws Exception {
    try (SeatWaveServer server = SeatWaveServer.startPlain(0);
        DelayLink link =
            DelayLink.start(
                0, server.port(), new LinkSettings(ONE_WAY, HandshakeDelay.ONE_ROUND_TRIP, 4));
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
      long startNanos = System.nanoTime();

      client.send(request(link), HttpResponse.BodyHandlers.discarding());

      // The handshake's round trip plus the request's: at least two round trips of 40 ms.
      assertThat(Duration.ofNanos(System.nanoTime() - startNanos))
          .isGreaterThanOrEqualTo(ONE_WAY.multipliedBy(4));
    }
  }
```

With `HandshakeDelay.ONE_ROUND_TRIP` the link holds the first bytes of a new connection one extra round trip, standing for the TCP handshake that the operating system finished at once on the loopback address (`bash-curl-timings.md` and `curl-and-openssl.md` explain the handshake). The comment: "The handshake's round trip plus the request's: at least two round trips of 40 ms." A request on a new connection now takes at least 4 times the one-way delay: `ONE_WAY.multipliedBy(4)` is 80 ms. `client.send(request(link), HttpResponse.BodyHandlers.discarding())` throws the body away, because the test cares about the time only.

### A connection over the limit is closed at once

```java
  @Test
  void closesAConnectionOverTheLimitAtOnce() throws Exception {
    try (SeatWaveServer server = SeatWaveServer.startPlain(0);
        DelayLink link =
            DelayLink.start(
                0, server.port(), new LinkSettings(Duration.ZERO, HandshakeDelay.NONE, 1));
        Socket first = connect(link);
        Socket second = connect(link)) {
      InputStream secondIn = second.getInputStream();

      // The link closes the second connection without a byte: the read sees the end at once.
      assertThat(secondIn.read()).isEqualTo(-1);
      assertThat(link.connectionsRefused()).isEqualTo(1);
      assertThat(first.isConnected()).isTrue();
    }
  }
```

- The link's limit is 1 connection (the third value, `1`, of `LinkSettings`) and the delay is `Duration.ZERO`.
- `connect(link)` (helper) opens a raw TCP **socket** to the link's port. A socket is the program's end of a connection. The first connection is accepted. The second is over the limit.
- `secondIn.read()` returns the next byte of the connection, or `-1` if the other side has closed it. The link closes the second connection without sending a byte, so the read returns `-1` at once. The comment says so: "The link closes the second connection without a byte: the read sees the end at once."
- The checks: `link.connectionsRefused()` is exactly 1, and the first socket is still connected. The sockets are in the `try ( ... )` list so they close at the end.
- Why it matters: a link that accepted unlimited connections would turn a mistake into a flood. This proves the limit.

### A dead upstream ends the client's connection

```java
  @Test
  void endsTheClientsConnectionWhenTheUpstreamIsNotListening() throws Exception {
    int deadPort;
    try (ServerSocket reserved = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      deadPort = reserved.getLocalPort();
    }
    try (DelayLink link =
            DelayLink.start(0, deadPort, new LinkSettings(Duration.ZERO, HandshakeDelay.NONE, 1));
        Socket client = connect(link)) {

      assertThat(client.getInputStream().read()).isEqualTo(-1);
      assertThat(link.connectionsFailed()).isEqualTo(1);
    }
  }
```

- `new ServerSocket(0, 0, InetAddress.getLoopbackAddress())` opens a server socket on a free port; the test reads its port number and then closes it by leaving the `try`. The port is now known to be free and not listening: a **dead** upstream.
- A link in front of that port accepts the client, tries to connect to the dead port, fails, and closes the client's connection. The client's read returns `-1`, and `link.connectionsFailed()` is 1.
- Why it matters: when the server behind a proxy is down, the client must see the connection end, not hang until a timeout.

### The settings of a link

```java
  @Test
  void refusesSettingsThatAreNotALink() {
    assertThatThrownBy(() -> new LinkSettings(Duration.ofMillis(-1), HandshakeDelay.NONE, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LinkSettings(Duration.ofSeconds(11), HandshakeDelay.NONE, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LinkSettings(Duration.ZERO, HandshakeDelay.NONE, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new LinkSettings(ONE_WAY, HandshakeDelay.NONE, 1).roundTrip())
        .isEqualTo(Duration.ofMillis(40));
  }
```

`LinkSettings` refuses a delay below zero (`Duration.ofMillis(-1)`), a delay above 10 seconds (`Duration.ofSeconds(11)`) and a limit of 0 connections, each with `IllegalArgumentException`. The last line checks a valid setting: `roundTrip()` of a 20 ms one-way delay is 40 ms.

### The two helpers

```java
  private static HttpRequest request(DelayLink link) {
    return HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + link.port() + "/events/1/seats?section=A"))
        .timeout(TIMEOUT)
        .build();
  }

  private static Socket connect(DelayLink link) throws Exception {
    Socket socket = new Socket(InetAddress.getLoopbackAddress(), link.port());
    socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
    return socket;
  }
}
```

`request(link)` builds a `GET` request for `http://127.0.0.1:<link port>/events/1/seats?section=A` with the timeout. `connect(link)` opens a socket on the loopback address and sets a read timeout of 5 seconds, so a test that goes wrong fails with an error instead of waiting forever.

### `PageLoadModelTest`: the setup

```java
package io.plainround.seatwave.network.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import io.plainround.seatwave.network.report.ConnectionKind;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class PageLoadModelTest {

  private static final double ROUND_TRIP = 80;
```

The page model (Lab 3) is a calculation, not a measurement: it counts round trips for a page of six responses over four protocols (`Protocol.HTTP1_ONE_CONNECTION`, `HTTP1_SIX_CONNECTIONS`, `HTTP2`, `HTTP3`), with and without packet loss. `ROUND_TRIP = 80` is the round-trip time in milliseconds used by the settings files. The settings come from the real files in `config/`, which Gradle's test task reads from the project folder.

### Round trips with no loss

```java
  @Test
  void countsTheRoundTripsOfANewConnectionWithNoLoss() throws Exception {
    PageLoadModel model = new PageLoadModel(settings("config/round-trips.properties"));

    // Setup (TCP 1 + TLS 1.3 1, or QUIC 1) plus the rounds of slow start the page needs. On one
    // HTTP/1.1 connection the five small replies grow the window only to 20 segments, so the
    // 26-segment seat map still needs two rounds.
    assertThat(pageRoundTrips(model, Protocol.HTTP1_ONE_CONNECTION, ConnectionKind.NEW))
        .isEqualTo(9);
    assertThat(pageRoundTrips(model, Protocol.HTTP1_SIX_CONNECTIONS, ConnectionKind.NEW))
        .isEqualTo(4);
    assertThat(pageRoundTrips(model, Protocol.HTTP2, ConnectionKind.NEW)).isEqualTo(5);
    assertThat(pageRoundTrips(model, Protocol.HTTP3, ConnectionKind.NEW)).isEqualTo(4);
  }
```

`config/round-trips.properties` describes the page (five small responses of 2,000 bytes and the 36,894-byte seat map), 1,460-byte segments, an initial window of 10 segments, a new connection that needs TCP and one TLS round trip, and `loss.percent=0`. A **segment** is one packet's worth of data; the **window** is how many segments a sender may have in flight before it waits for an acknowledgement, and it grows after each round trip (slow start).

The comment in the test explains the counts: "Setup (TCP 1 + TLS 1.3 1, or QUIC 1) plus the rounds of slow start the page needs. On one HTTP/1.1 connection the five small replies grow the window only to 20 segments, so the 26-segment seat map still needs two rounds." The seat map is 36,894 divided by 1,460, which rounds up to 26 segments.

`pageRoundTrips` (helper) is the page's median time divided by 80 ms. The expected values: 9 for one HTTP/1.1 connection, 4 for six connections, 5 for HTTP/2 and 4 for HTTP/3. They match the README's first table (`MODEL, not a measurement`): 720, 320, 400 and 320 ms for the new connection.

### A reused connection

```java
  @Test
  void needsOneRoundTripOnAWarmReusedConnectionUnlessRequestsWaitForEachOther() throws Exception {
    PageLoadModel model = new PageLoadModel(settings("config/round-trips.properties"));

    assertThat(pageRoundTrips(model, Protocol.HTTP1_ONE_CONNECTION, ConnectionKind.REUSED))
        .isEqualTo(6);
    assertThat(pageRoundTrips(model, Protocol.HTTP2, ConnectionKind.REUSED)).isEqualTo(1);
    assertThat(model.setupRoundTrips(Protocol.HTTP3, ConnectionKind.REUSED)).isZero();
  }
```

On a reused connection there is no setup. HTTP/2 puts every request on the same connection at once, so the page needs 1 round trip. One HTTP/1.1 connection must send the six requests one after another, each after the last reply: 6 round trips. And `setupRoundTrips(Protocol.HTTP3, ConnectionKind.REUSED)` is zero. The test name says "unless requests wait for each other".

### Head-of-line blocking

```java
  @Test
  void findsHeadOfLineBlockingOnlyWhereOneOrderedStreamCarriesEveryResponse() throws Exception {
    PageLoadModel model = new PageLoadModel(settings("config/page-model.properties"));

    assertThat(model.summarize(Protocol.HTTP2, ConnectionKind.NEW).heldBackPercent()).isPositive();
    assertThat(model.summarize(Protocol.HTTP3, ConnectionKind.NEW).heldBackPercent()).isZero();
    assertThat(
            model.summarize(Protocol.HTTP1_SIX_CONNECTIONS, ConnectionKind.NEW).heldBackPercent())
        .isZero();
    assertThat(model.summarize(Protocol.HTTP1_ONE_CONNECTION, ConnectionKind.NEW).heldBackPercent())
        .isZero();
  }
```

**Head-of-line blocking** is when one lost packet holds up data behind it that has already arrived. It happens where one ordered stream carries every response: all of HTTP/2 over one TCP connection. The test reads `config/page-model.properties` (2 percent loss, 10,000 trials) and checks that the share of pages held back is above zero for HTTP/2 (`isPositive()`), and zero for HTTP/3 (each QUIC stream recovers by itself), for six HTTP/1.1 connections and for one HTTP/1.1 connection (which never has two requests in flight). The README's second table shows 20.1 percent for HTTP/2.

### A lossy page is never faster than a lossless one

```java
  @Test
  void neverMakesALossyPageFasterThanALosslessOne() throws Exception {
    PageLoadModel lossless = new PageLoadModel(settings("config/round-trips.properties"));
    PageLoadModel lossy = new PageLoadModel(settings("config/page-model.properties"));

    for (Protocol protocol : Protocol.values()) {
      double floor = lossless.summarize(protocol, ConnectionKind.NEW).page().p50();
      PageLoadModel.Summary summary = lossy.summarize(protocol, ConnectionKind.NEW);

      assertThat(summary.page().p50()).as(protocol.label()).isGreaterThanOrEqualTo(floor);
      assertThat(summary.page().p95()).as(protocol.label()).isGreaterThan(floor);
    }
  }
```

For each of the four protocols the test takes the lossless median (the floor) and the lossy summary. The lossy median must be at least the floor, and the lossy 95th percentile (the time under which 95 percent of the pages finish) must be strictly greater. `.as(protocol.label())` puts the protocol's name in front of a failure message so you know which loop turn failed. This is a sanity check of the model: loss can only add time.

### The same seed gives the same answer

```java
  @Test
  void givesTheSameAnswerForTheSameSeed() throws Exception {
    PageLoadModel model = new PageLoadModel(settings("config/page-model.properties"));

    PageLoadModel.PageTimes first =
        model.simulate(Protocol.HTTP2, ConnectionKind.NEW, new SplittableRandom(1));
    PageLoadModel.PageTimes second =
        model.simulate(Protocol.HTTP2, ConnectionKind.NEW, new SplittableRandom(1));

    assertThat(first).isEqualTo(second);
  }
```

`new SplittableRandom(1)` is a random number generator started from the seed 1. Two simulations with the same seed must produce equal results (`isEqualTo` on a record compares every field). Without this property a table in the README could not be reproduced. The settings files fix `random.seed=20261110` for the same reason.

### The printed assumptions

```java
  @Test
  void printsTheAssumptionsAboveTheTable() throws Exception {
    String text = PageModelReport.render(settings("config/round-trips.properties"));

    assertThat(text)
        .startsWith("MODEL, not a measurement. Distance 8,000 km")
        .contains("speed-of-light floor in fiber is 80.0 ms")
        .contains("HTTP/1.1, 6 connections  new                  2       320");
  }
```

`PageModelReport.render(settings)` makes the report text. It must start with `MODEL, not a measurement. Distance 8,000 km`, say that the speed-of-light floor in fiber is `80.0 ms`, and contain the table row `HTTP/1.1, 6 connections  new                  2       320` (the protocol, the kind of connection, the setup round trips and the page median). The model prints its assumptions above the table so no reader can mistake it for a measurement.

### A one-response page

```java
  @Test
  void treatsAOneResponsePageAsItsOwnSmallResponse() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/round-trips.properties"));
    properties.setProperty("response.bytes", "1000");
    PageLoadModel model = new PageLoadModel(read(properties));

    PageLoadModel.PageTimes times =
        model.simulate(Protocol.HTTP3, ConnectionKind.NEW, new SplittableRandom(1));

    assertThat(times.smallResponsesMillis())
        .isEqualTo(times.pageMillis())
        .isEqualTo(2 * ROUND_TRIP);
  }
```

The test changes `response.bytes` to the single value `1000` in memory. HTTP/3 on a new connection then needs one round trip for setup and one for the request: `2 * ROUND_TRIP` is 160 ms. With one response the "small responses" time equals the page time, which the test asserts with a chain of `isEqualTo` on `smallResponsesMillis()`: the answer must equal both `pageMillis()` and 160.

### Invalid settings are listed, impossible values are refused

```java
  @Test
  void listsInvalidSettingsAndRefusesImpossibleValues() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/page-model.properties"));
    properties.setProperty("loss.percent", "60");
    properties.setProperty("protocols", "http4");

    Result<ModelSettings, List<String>> read = ModelSettings.from(new SettingsReader(properties));

    assertThat(((Result.Error<ModelSettings, List<String>>) read).error())
        .containsExactly(
            "loss.percent must be from 0.0 to 50.0, but was 60",
            "protocols has an unknown value: http4");
    assertThatThrownBy(() -> new ModelSettings.Windows(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ModelSettings.Loss(51, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ModelSettings.Trials(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

Two mistakes in the copy of `config/page-model.properties`: `loss.percent` of 60 (the limit is 50) and the protocol `http4`. `ModelSettings.from(...)` must report both, in order: `loss.percent must be from 0.0 to 50.0, but was 60` and `protocols has an unknown value: http4`. Then three record constructors must refuse impossible numbers directly: `Windows(0, 1)` (a window needs at least one segment), `Loss(51, 0, 0)` (51 percent is over the limit) and `Trials(0, 1)` (zero trials).

### The helpers

```java
  private static long pageRoundTrips(PageLoadModel model, Protocol protocol, ConnectionKind kind) {
    return Math.round(model.summarize(protocol, kind).page().p50() / ROUND_TRIP);
  }

  private static ModelSettings settings(String file) throws Exception {
    return read(SettingsReader.load(Path.of(file)));
  }

  private static ModelSettings read(Properties properties) {
    return ModelSettings.from(new SettingsReader(properties)).discardError().orElseThrow();
  }
}
```

`pageRoundTrips` rounds the page median to whole round trips. `settings(file)` loads a file with `SettingsReader.load(Path.of(file))` and builds `ModelSettings`. `read(properties)` calls `ModelSettings.from(...)` and turns a success into the settings, or throws. `.discardError().orElseThrow()` is the result type's way to say "I expect success here" (`result4j.md`).

### `SeatMapJsonTest`: module 1's bytes

```java
package io.plainround.seatwave.network.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SeatMapJsonTest {

  // Module 1's sample output: curl ... | wc -c printed 36894 for section A.
  private static final int MODULE_ONE_SEAT_MAP_BYTES = 36_894;

  @Test
  void buildsModuleOnesSeatMapByteForByte() {
    String seats = new String(SeatMapJson.seats("A"), StandardCharsets.UTF_8);

    assertThat(seats.getBytes(StandardCharsets.UTF_8)).hasSize(MODULE_ONE_SEAT_MAP_BYTES);
    assertThat(seats)
        .startsWith(
            "[{\"seat\":\"A-1\",\"state\":\"available\"},{\"seat\":\"A-10\",\"state\":\"available\"},"
                + "{\"seat\":\"A-100\",\"state\":\"available\"},{\"seat\":\"A-1000\"")
        .endsWith("{\"seat\":\"A-999\",\"state\":\"available\"}]");
  }
```

- `MODULE_ONE_SEAT_MAP_BYTES = 36_894` is the size that Module 1 measured with `curl ... | wc -c`. The comment says so.
- `SeatMapJson.seats("A")` returns the byte array of the seat map for section A. The test turns it into text and back to count the UTF-8 bytes, which must be 36,894.
- It checks the beginning and the end of the text. The expected start is `[{"seat":"A-1","state":"available"},{"seat":"A-10","state":"available"},{"seat":"A-100","state":"available"},{"seat":"A-1000"` (seats are sorted as text, so `A-10` comes before `A-2`). The end is `{"seat":"A-999","state":"available"}]`. In Java source each quote inside a string is written `\"`.
- Why it matters: the whole lesson compares times for a response of exactly this size. A body of another size would make every number in the README mean something else.

```java
  @Test
  void buildsModuleOnesEventList() {
    assertThat(new String(SeatMapJson.events(), StandardCharsets.UTF_8))
        .isEqualTo("[{\"eventId\":1,\"name\":\"Arena Night\",\"startsAt\":1796761251783}]");
  }
```

The event list is one event: `[{"eventId":1,"name":"Arena Night","startsAt":1796761251783}]`. The `startsAt` value is a fixed number (milliseconds since 1 January 1970), not "now", so the bytes never change.

```java
  @Test
  void acceptsOnlyOneCapitalLetterAsASection() {
    assertThat(SeatMapJson.isValidSection("Z")).isTrue();
    assertThat(SeatMapJson.isValidSection("a")).isFalse();
    assertThat(SeatMapJson.isValidSection("AB")).isFalse();
    assertThat(SeatMapJson.isValidSection("")).isFalse();
    assertThatThrownBy(() -> SeatMapJson.seats("A' OR 1=1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

A **section** is one capital letter. `isValidSection("Z")` is true; `"a"` (lower case), `"AB"` (two letters) and `""` are false. And `SeatMapJson.seats("A' OR 1=1")` throws `IllegalArgumentException`: text that looks like an attempt to inject a database query is refused before any work. (This server has no database; the check keeps the habit and keeps the path safe.)

### `SeatWaveServerTest`: one server for the whole class

```java
package io.plainround.seatwave.network.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.network.tls.TestTls;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SeatWaveServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static SeatWaveServer server;
  private static HttpClient client;

  @BeforeAll
  static void start() throws Exception {
    server = SeatWaveServer.startPlain(0);
    client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @AfterAll
  static void stop() {
    client.close();
    server.close();
  }
```

`@BeforeAll` runs once before the first test and `@AfterAll` once after the last (`junit.md`). `static` fields hold the server and the client because those two methods run when no test object exists yet. `SeatWaveServer.startPlain(0)` starts a plain HTTP server on a free port. `HttpClient.newBuilder().connectTimeout(TIMEOUT).build()` makes a client with a 5-second connect timeout. In `stop`, the client and the server are closed.

```java
  @Test
  void servesTheSeatMapWithItsServerTime() throws Exception {
    HttpResponse<byte[]> response = get("events/1/seats?section=A");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).hasSize(36_894);
    assertThat(response.headers().firstValue("Server-Timing"))
        .hasValueSatisfying(value -> assertThat(value).matches("app;dur=\\d+\\.\\d{2}"));
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
  }
```

`get("events/1/seats?section=A")` (helper at the end of the file) sends a `GET` and returns the response with the body as bytes. The checks: status 200; body of 36,894 bytes; a `Server-Timing` header that matches the pattern `app;dur=\d+\.\d{2}` (the time the handler spent, in milliseconds with two decimals, such as `app;dur=0.43`); and `Content-Type` exactly `application/json`. The `Server-Timing` header is what lets the lab tell server time from network time.

```java
  @Test
  void servesTheEventListAndHealth() throws Exception {
    assertThat(get("events").statusCode()).isEqualTo(200);
    assertThat(new String(get("health").body(), java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo("{\"status\":\"up\"}");
  }
```

`GET /events` returns 200, and `GET /health` returns exactly `{"status":"up"}`.

```java
  @Test
  void answersHeadWithTheLengthAndNoBody() throws Exception {
    HttpRequest head =
        HttpRequest.newBuilder(server.baseUri().resolve("events/1/seats?section=B"))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .timeout(TIMEOUT)
            .build();

    HttpResponse<byte[]> response = client.send(head, HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().firstValue("Content-Length")).hasValue("36894");
  }
```

A `HEAD` request asks for the headers of a response without its body. The test builds it with `.method("HEAD", HttpRequest.BodyPublishers.noBody())`. The server must answer 200, send an empty body and still announce `Content-Length: 36894`. So a client can learn the size without downloading it.

```java
  @Test
  void refusesABadSectionBeforeAnyWork() throws Exception {
    for (String query : List.of("section=a", "section=AB", "section=", "seat=A", "")) {
      HttpResponse<byte[]> response = get("events/1/seats" + (query.isEmpty() ? "" : "?" + query));

      assertThat(response.statusCode()).as(query).isEqualTo(400);
      assertThat(response.headers().firstValue("Content-Type"))
          .hasValue("application/problem+json");
    }
  }
```

Five bad queries (`section=a`, `section=AB`, `section=`, `seat=A` and none at all) must each get status 400 with the content type `application/problem+json` (a standard format for HTTP error answers). `.as(query)` puts the bad query in the failure message. "Refuses a bad section before any work" means the check comes first, before the seat map is built.

```java
  @Test
  void answers404ForAnUnknownEventOrPath() throws Exception {
    assertThat(get("events/2/seats?section=A").statusCode()).isEqualTo(404);
    assertThat(get("holds").statusCode()).isEqualTo(404);
    assertThat(get("events?page=2").statusCode()).isEqualTo(404);
  }
```

Three paths that do not exist must give 404: an event number other than 1, the path `holds` (SeatWave's other operations are not in this lab) and `events?page=2` (a query the list does not take).

```java
  @Test
  void refusesAWriteWith405AndSaysWhatIsAllowed() throws Exception {
    HttpRequest post =
        HttpRequest.newBuilder(server.baseUri().resolve("events"))
            .POST(HttpRequest.BodyPublishers.ofString("x"))
            .timeout(TIMEOUT)
            .build();

    HttpResponse<byte[]> response = client.send(post, HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isEqualTo(405);
    assertThat(response.headers().firstValue("Allow")).hasValue("GET, HEAD");
  }
```

A `POST` (a request that sends data, which this server does not accept) must get 405 (method not allowed), and the answer's `Allow` header must be `GET, HEAD`: it tells the client what is allowed. This server is read-only.

```java
  @Test
  void listensOnTheLoopbackAddressOnly() {
    assertThat(server.baseUri().getHost()).isEqualTo("127.0.0.1");
    assertThat(server.baseUri().getScheme()).isEqualTo("http");
  }
```

`server.baseUri()` is `http://127.0.0.1:<port>/`. The test checks the host is `127.0.0.1` and the scheme is `http`. This is the check that nothing is exposed outside this machine.

```java
  @Test
  void speaksHttp11OverTlsEvenWhenTheClientAsksForHttp2() throws Exception {
    try (SeatWaveServer tls =
            SeatWaveServer.startTls(0, TestTls.validServer(), List.of("TLSv1.3"));
        HttpClient tlsClient =
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .sslContext(TestTls.client())
                .connectTimeout(TIMEOUT)
                .build()) {
      HttpRequest request =
          HttpRequest.newBuilder(tls.baseUri().resolve("health")).timeout(TIMEOUT).build();

      HttpResponse<String> response = tlsClient.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(tls.baseUri().getScheme()).isEqualTo("https");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
      assertThat(response.sslSession())
          .hasValueSatisfying(session -> assertThat(session.getProtocol()).isEqualTo("TLSv1.3"));
    }
  }
```

- `SeatWaveServer.startTls(0, TestTls.validServer(), List.of("TLSv1.3"))` starts an HTTPS server with the valid test certificate (`tests-tls-and-timing-walkthrough.md`). The client asks for HTTP/2 (`.version(HttpClient.Version.HTTP_2)`) and trusts only the test authority (`.sslContext(TestTls.client())`).
- The checks: the scheme is `https`; status 200; `response.version()` is `HTTP_1_1`, because the JDK server does not offer HTTP/2 and the client falls back; and `response.sslSession()` (an `Optional`) holds a session whose protocol is `TLSv1.3`. The README states the same limit: "The JDK server does not offer HTTP/2".

```java
  @Test
  void refusesToStartTlsWithNoVersion() {
    assertThatThrownBy(() -> SeatWaveServer.startTls(0, TestTls.validServer(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

`startTls` with an empty list of TLS versions throws `IllegalArgumentException`: a TLS server that accepts no version cannot work, and the error appears at start-up.

```java
  private static HttpResponse<byte[]> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(server.baseUri().resolve(URI.create(path))).timeout(TIMEOUT).build();
    return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
  }
}
```

The helper `get(path)` resolves the path against the server's base URI, builds a `GET` with a timeout and sends it, returning the body as bytes. Every test that calls it reuses the one server and the one client of the class.

## Try it yourself

All ran in a scratch copy on 10 November 2026.

1. Run the five classes:

```
./gradlew --offline cleanTest test --tests '*SettingsReaderTest' --tests '*DelayLinkTest' --tests '*PageLoadModelTest' --tests '*SeatMapJsonTest' --tests '*SeatWaveServerTest'
```

   Expected: 30 lines ending in `PASSED` (5, 5, 8, 3 and 9) and `BUILD SUCCESSFUL`.

2. See the page model's numbers that the tests check:

```
./gradlew --offline -q pageModel --args='config/round-trips.properties'
```

   Result: the line `MODEL, not a measurement. Distance 8,000 km: the speed-of-light floor in fiber is 80.0 ms round trip; the model uses 80.0 ms.`, the assumptions lines, and the table, whose first row is `HTTP/1.1, 1 connection   new                  2       720       720          9.0        560        560          720          720          0.0`.

3. Count the bytes of the seat map from the running server by hand (after `./gradlew -q serve --args='config/serve.properties'` in another terminal):

```
curl -s 'http://127.0.0.1:8080/events/1/seats?section=A' | wc -c
```

   Result: `36894` (I ran it with the README's ports, against a server started from the scratch copy).

4. Change the limit of the link in a scratch copy: in `DelayLinkTest.closesAConnectionOverTheLimitAtOnce` change the third argument `1` of `LinkSettings` to `2` and run the class. Result: `DelayLinkTest > closesAConnectionOverTheLimitAtOnce() FAILED` with `java.net.SocketTimeoutException at DelayLinkTest.java:72` and `5 tests completed, 1 failed`: the second connection is then accepted, so the read does not return `-1`; it waits for the 5-second socket timeout and throws. Undo the edit.

## Mistakes people make

- **An upper bound on elapsed time.** It fails on a loaded machine. The link test checks a lower bound only.
- **A fixed port.** Another program may hold it. Port 0 avoids that.
- **A shared server whose state changes.** `SeatWaveServerTest` shares one server, which is safe because the server keeps no state between requests.
- **Testing only the happy path.** A third of the tests here are about refusals (400, 404, 405, a closed connection, a bad setting).
- **Random numbers without a seed.** The same page model would give different numbers on each run.
- **Not closing sockets.** The sockets and the link are in the `try ( ... )` list so they always close.

## Check yourself

1. *Why does `DelayLinkTest` assert "at least 40 ms" and not "40 ms"?* Noise can only add time. A lower bound cannot fail on a slow machine; an exact value would.
2. *What proves the link's connection limit?* The second socket reads `-1` at once, and `connectionsRefused()` is 1.
3. *Why must `reportsEveryProblemInOneRun` use `containsExactly`?* The messages must be exactly these, in the order the keys were read.
4. *What does `HEAD` return?* The headers, including `Content-Length`, and no body.
5. *Why does the model test use a seed?* A fixed seed makes the "random" losses repeat, so the numbers are reproducible.

## Further reading

- JUnit 6.1.3 user guide: <https://docs.junit.org/6.1.3/overview.html>
- AssertJ documentation: <https://assertj.github.io/doc/>
- `java.net.http` package (JDK 25): <https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/package-summary.html>
- `java.util.Properties` (JDK 25): <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html>
- `SplittableRandom` (JDK 25): <https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/SplittableRandom.html>

# The `model` and `config` packages, line by line

## What it is

This file reads two packages, one line at a time. The `model` package (five files: `Protocol.java`, `ModelSettings.java`, `PageLoadModel.java`, `PageModelReport.java`, `PageModelCli.java`) is the **page model**: arithmetic that counts how many round trips a page load needs over HTTP/1.1, HTTP/2 and HTTP/3, and what packet loss does to each. The `config` package has one file, `SettingsReader.java`, the class every command uses to read its settings file.

A **round trip** is a message going to the other side and the answer coming back. Its length is the **round-trip time** (RTT): 80 ms for a fan 8,000 km away. A **model** is a simplified calculation of how something behaves. This one is not a measurement: no packet is sent. It is arithmetic on a timeline, and every input is an assumption printed in the report.

## Why this repository uses it

Module 5 asks what HTTP/2 and HTTP/3 change. The JDK 25 server and client cannot do HTTP/3, and a real lossy link is a different lab. A small model can still show why the protocols differ: the number of round trips before the first request leaves, how many round trips the data needs, and **head-of-line blocking**, where one lost packet holds back packets that have already arrived. The model uses `java.util.SplittableRandom` with a fixed seed, so two runs print the same table.

`SettingsReader` is in the same part of the notes because every command (`serve`, `timing`, `tlsFailures`, `pageModel`) reads its numbers from a `.properties` file through it, with no defaults in code.

## Where it appears

| File | What it does |
|---|---|
| `model/Protocol.java` | The four ways a browser can fetch one page. |
| `model/ModelSettings.java` | The checked inputs of the model, read from `config/page-model*.properties` and `config/round-trips.properties`. |
| `model/PageLoadModel.java` | The model: setup round trips, slow start, loss and delivery order. |
| `model/PageModelReport.java` | Writes the assumptions and the table. |
| `model/PageModelCli.java` | The `pageModel` command. |
| `config/SettingsReader.java` | Reads typed values from a properties file and collects problems. |

## How it works underneath

**What the model counts.** Take the page of `config/page-model.properties`: six responses, five of 2,000 bytes and one of 36,894 bytes (the seat map), a round-trip time of 80 ms, 1,460-byte segments, and a congestion window of 10 segments for a new connection and 64 for a reused one.

- **Segments.** A response is sent in **segments**: pieces of at most 1,460 bytes (a full Ethernet packet of 1,500 bytes less 40 bytes of IP and TCP headers). 2,000 bytes is `ceil(2000 / 1460) = 2` segments. 36,894 bytes is `ceil(36894 / 1460) = 26` segments (25.27 rounded up). Altogether the page is `5 x 2 + 26 = 36` segments.
- **Slow start.** A TCP sender does not send everything at once, to avoid flooding the path. It sends one **window** of segments, waits one round trip for the acknowledgements, and the window grows by one segment for each segment acknowledged (so it doubles when every segment is acknowledged). A new connection starts at 10 segments (RFC 6928). A reused connection has already grown, here to 64.
- **Setup.** A new TCP connection costs one round trip, then TLS 1.3 costs one more. A new QUIC connection (HTTP/3) costs one round trip for both. A reused connection costs none.
- **One way.** After the setup, the request needs half a round trip (40 ms) to reach the server, and each segment needs half a round trip to come back.

**The arithmetic, with no loss** (`config/round-trips.properties`; every value in milliseconds after the click):

| Case | Arithmetic | Page time |
|---|---|---|
| HTTP/1.1, one connection, new | The first request arrives at `2 x 80 + 40 = 200`. Each of the five small responses arrives 40 ms after its request and the next request arrives 40 ms after that: 240, 320, 400, 480, 560. The big response starts at 600 with window 20, sends 20 segments (arrive 640), then 6 more one round trip later (arrive 720). | 720 (9.0 round trips) |
| HTTP/1.1, six connections, new | Every request arrives at 200 on its own connection. A small response needs one round: 240. The big one sends 10 segments (240), then 16 (320). | 320 (4.0) |
| HTTP/2, new | One connection, all requests at once: 36 segments in rounds of 10 (arrive 240), 20 (arrive 320) and 6 (arrive 400). The responses are interleaved a segment at a time, so the last small response finishes in round 2 (320). | 400 (5.0) |
| HTTP/3, new | Setup is one round trip, so the request arrives at `80 + 40 = 120`. The same three rounds: 160, 240, 320. | 320 (4.0) |
| HTTP/1.1, one connection, reused | The request arrives at 40; the responses complete at 80, 160, 240, 320, 400; the big one needs one round with window 74: 480. | 480 (6.0) |
| HTTP/1.1 six connections, HTTP/2, HTTP/3, reused | One round trip: the request goes (40), the 36 segments or fewer fit the window of 64 and come back (80). | 80 (1.0) |

These match the `loss.percent=0` run in "Try it yourself".

**Loss.** Each packet is lost independently with the probability `loss.percent`. A lost packet is repaired late:

- If at least three packets were sent after it in the same round, the receiver sees three duplicate acknowledgements and the sender retransmits at once: about one round trip late (`fast.recovery.millis=80`). QUIC's packet threshold is also 3.
- Otherwise (the lost packet is near the end of a round, so fewer than three packets follow it), the sender has to wait for a timeout: about two round trips (`tail.recovery.millis=160`).
- If the retransmission is lost too, the code adds another tail delay.

**Head-of-line blocking.** TCP gives bytes to the application only in order. With HTTP/2, all responses share one TCP connection, so a lost packet of one response holds back later packets that already arrived, even those of other responses. HTTP/3 uses QUIC, which keeps each stream in order but separately, so a lost packet holds back only its own stream. The `held back %` column counts the share of simulated pages where a packet that had arrived waited for a lost packet of another response. The HTTP/2 row shows 20.1 percent on the five-small-and-one-big page. HTTP/3 and the HTTP/1.1 rows show 0.0, because they separate the responses (by stream or by connection). With six seat maps it is 95.9 percent for HTTP/2: the page has 156 segments, and the chance that at least one of them is lost is `1 - 0.98^156 = 95.7` percent.

**Simulating many pages.** The model plays the page 10,000 times with a seeded random source, sorts the page times, and reports the **p50** (the median) and the **p95** (the time that 95 out of 100 pages beat). The same seed is used for every protocol, so every protocol meets the same luck, and the gaps between rows are the protocol's own. Two runs give identical numbers.

**What this is not.** It ignores bandwidth limits (the fan's link is never the limit), it does not shrink the window after a loss, and it does not fetch the document first (which would add a round trip to every protocol alike). The Javadoc of `PageLoadModel` lists each simplification.

**How a settings file is read.** `SettingsReader.load(path)` opens the file as UTF-8 and loads it into a `java.util.Properties` object (see `java-properties.md`). Then each `reader.xxx("key", ...)` call returns a typed value. When a key is missing or invalid, the reader writes one line into its list of problems and returns a harmless stand-in. The caller checks `problems()` before using any value, so one run reports every mistake. There are no defaults: an absent key is a problem, never a silent fallback.

## Walkthrough of this repository's use

### `model/Protocol.java`

```java
package io.plainround.seatwave.network.model;

/** The four ways a browser can fetch one page, as the page model compares them. */
public enum Protocol {
  /** HTTP/1.1 on one keep-alive connection: one request at a time, each after the last reply. */
  HTTP1_ONE_CONNECTION("HTTP/1.1, 1 connection"),
  /** HTTP/1.1 on six connections in parallel, one request each: what browsers do. */
  HTTP1_SIX_CONNECTIONS("HTTP/1.1, 6 connections"),
  /** HTTP/2: every request at once as streams on one TCP connection, delivered in TCP order. */
  HTTP2("HTTP/2"),
  /** HTTP/3: every request at once as streams on one QUIC connection, each stream in its order. */
  HTTP3("HTTP/3");
```

- An enum of the four ways to fetch a page. Each value carries a label for the report in brackets. The comments say how each behaves: HTTP/1.1 on one connection asks for one response at a time. HTTP/1.1 on six connections is what browsers do. HTTP/2 sends every request at once as streams on one TCP connection, delivered in TCP order. HTTP/3 does the same on one QUIC connection, with each stream in its own order.

```java
  private final String label;

  Protocol(String label) {
    this.label = label;
  }

  /**
   * Returns the name a report prints.
   *
   * @return for example {@code HTTP/1.1, 6 connections}
   */
  public String label() {
    return label;
  }
}
```

- The constructor stores the label, and `label()` returns it.

### `model/ModelSettings.java`

```java
package io.plainround.seatwave.network.model;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import java.util.List;
import java.util.Objects;
```

- Imports: `Result` (result4j), `SettingsReader`, `List` and `Objects`.

```java
/**
 * The inputs of the page model, read from a settings file with no defaults. Every value is an
 * assumption the report prints, not a measurement.
 *
 * @param distanceKm the straight-line distance to the fan, for the speed-of-light floor
 * @param roundTripMillis the round-trip time the model uses
 * @param responseBytes the size of each response of the page, in request order
 * @param segmentBytes the payload of one TCP segment or QUIC packet
 * @param windows the congestion windows of a new and of a reused connection
 * @param tlsRoundTrips the TLS handshake's round trips over TCP: 1 for TLS 1.3, 2 for TLS 1.2
 * @param loss how often a packet is lost and how long each kind of recovery takes
 * @param trials how many pages are simulated per protocol, and the seed of the random losses
 * @param protocols the protocols to compare, in order
 */
public record ModelSettings(
    int distanceKm,
    double roundTripMillis,
    List<Integer> responseBytes,
    int segmentBytes,
    Windows windows,
    int tlsRoundTrips,
    Loss loss,
    Trials trials,
    List<Protocol> protocols) {
```

- The inputs of the model as one record with nine components: the distance in kilometers, the round-trip time in milliseconds, the size of each response in order, the segment size, the congestion windows, the TLS round trips, the loss settings, the trial settings, and the protocols to compare.
- `double` is a decimal number; `int` a whole number; `List<Integer>` a list of whole numbers (a list cannot hold the primitive `int`, so it holds the box type `Integer`). `Windows`, `Loss` and `Trials` are nested records defined at the end of the file.
- The Javadoc says every value is an assumption the report prints, not a measurement.

```java
  private static final int MAX_DISTANCE_KM = 40_000;
  private static final int MAX_ROUND_TRIP_MILLIS = 2_000;
  private static final int MAX_RESPONSE_BYTES = 100_000_000;
  // A segment larger than a jumbo frame's payload is a typo.
  private static final int MAX_SEGMENT_BYTES = 9_000;
  private static final int MAX_WINDOW_SEGMENTS = 100_000;
  private static final int MAX_TLS_ROUND_TRIPS = 2;
  // Half the packets lost is no longer a network a page can load over.
  private static final double MAX_LOSS_PERCENT = 50;
  private static final int MAX_RECOVERY_MILLIS = 10_000;
  private static final int MAX_TRIALS = 1_000_000;
```

- The `MAX_...` constants are the top of each range. 40,000 km is about the circumference of the Earth. 9,000 bytes is the payload of a jumbo frame: a larger segment is a typo. A loss of half the packets "is no longer a network a page can load over".

```java
  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if a value is outside its range
   */
  public ModelSettings {
    Objects.requireNonNull(windows, "windows");
    Objects.requireNonNull(loss, "loss");
    Objects.requireNonNull(trials, "trials");
    responseBytes = List.copyOf(responseBytes);
    protocols = List.copyOf(protocols);
    if (responseBytes.isEmpty() || protocols.isEmpty()) {
      throw new IllegalArgumentException("a page needs responses, and a run needs protocols");
    }
    if (roundTripMillis <= 0 || segmentBytes < 1 || distanceKm < 0) {
      throw new IllegalArgumentException("the round trip and segment must be positive");
    }
    if (tlsRoundTrips < 1 || tlsRoundTrips > MAX_TLS_ROUND_TRIPS) {
      throw new IllegalArgumentException("TLS takes 1 or 2 round trips");
    }
  }
```

- The compact constructor copies the lists so they cannot change later, and refuses an empty page or an empty protocol list, a round-trip time that is not positive, a segment of less than a byte, a negative distance, and a TLS round-trip count outside 1 to 2.
- A `double` compared with `<= 0` handles NaN badly (every comparison with NaN is false); the reader below uses a range check written to refuse NaN.

```java
  /**
   * Reads the settings.
   *
   * @param reader the settings reader
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<ModelSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    int distance = reader.intBetween("distance.km", 0, MAX_DISTANCE_KM);
    double roundTrip = reader.decimalBetween("rtt.millis", 1, MAX_ROUND_TRIP_MILLIS);
    List<Integer> responses = reader.intListBetween("response.bytes", 1, MAX_RESPONSE_BYTES);
    int segment = reader.intBetween("segment.bytes", 1, MAX_SEGMENT_BYTES);
    int initial = reader.intBetween("initial.window.segments", 1, MAX_WINDOW_SEGMENTS);
    int warm = reader.intBetween("warm.window.segments", 1, MAX_WINDOW_SEGMENTS);
    int tls = reader.intBetween("tls.handshake.round.trips", 1, MAX_TLS_ROUND_TRIPS);
    double lossPercent = reader.decimalBetween("loss.percent", 0, MAX_LOSS_PERCENT);
    double fast = reader.decimalBetween("fast.recovery.millis", 0, MAX_RECOVERY_MILLIS);
    double tail = reader.decimalBetween("tail.recovery.millis", 0, MAX_RECOVERY_MILLIS);
    int trialCount = reader.intBetween("trials", 1, MAX_TRIALS);
    int seed = reader.intBetween("random.seed", 0, Integer.MAX_VALUE);
    List<Protocol> protocols = reader.enumList("protocols", Protocol.class);
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new ModelSettings(
            distance,
            roundTrip,
            responses,
            segment,
            new Windows(initial, warm),
            tls,
            new Loss(lossPercent, fast, tail),
            new Trials(trialCount, seed),
            protocols));
  }
```

- `from` reads thirteen keys. Read the key names: `distance.km`, `rtt.millis`, `response.bytes` (a list), `segment.bytes`, `initial.window.segments`, `warm.window.segments`, `tls.handshake.round.trips`, `loss.percent`, `fast.recovery.millis`, `tail.recovery.millis`, `trials`, `random.seed` and `protocols`.
- `reader.decimalBetween("rtt.millis", 1, MAX_ROUND_TRIP_MILLIS)` accepts a decimal number from 1 to 2,000. `reader.intListBetween("response.bytes", 1, MAX_RESPONSE_BYTES)` reads a comma-separated list of whole numbers. `reader.enumList("protocols", Protocol.class)` reads names such as `http1_one_connection` and upper-cases them.
- Before building anything, `if (!reader.problems().isEmpty())` returns `Result.error(...)`. The stand-in values are therefore never used.
- On success, the nested records are built from the values read: `new Windows(initial, warm)`, `new Loss(lossPercent, fast, tail)`, `new Trials(trialCount, seed)`.

```java
  /**
   * The congestion window, in segments, that the first round of data may use.
   *
   * @param initialSegments a new connection's window: 10 under RFC 6928
   * @param warmSegments a reused connection's window, already grown by earlier traffic
   */
  public record Windows(int initialSegments, int warmSegments) {

    /** Checks the fields. */
    public Windows {
      if (initialSegments < 1 || warmSegments < 1) {
        throw new IllegalArgumentException("a window holds at least one segment");
      }
    }
  }
```

- `Windows`: the congestion window in segments for a new connection and for a reused one. RFC 6928 sets the initial window to 10 segments. A reused connection's window has already grown from earlier traffic.

```java
  /**
   * How packets are lost and recovered.
   *
   * @param percent the chance that any one packet is lost, 0 to 50
   * @param fastRecoveryMillis the extra delay of a loss that three later packets reveal
   * @param tailRecoveryMillis the extra delay of a loss with fewer than three packets behind it
   */
  public record Loss(double percent, double fastRecoveryMillis, double tailRecoveryMillis) {

    /** Checks the fields. */
    public Loss {
      if (!(percent >= 0 && percent <= MAX_LOSS_PERCENT)
          || fastRecoveryMillis < 0
          || tailRecoveryMillis < 0) {
        throw new IllegalArgumentException("loss must be 0 to 50 percent, recovery 0 or more");
      }
    }
  }
```

- `Loss`: the chance that one packet is lost (0 to 50 percent) and the two recovery delays in milliseconds.
- `!(percent >= 0 && percent <= MAX_LOSS_PERCENT)` is written with the negation outside so that NaN (for which every comparison is false) is refused.

```java
  /**
   * How many pages to simulate.
   *
   * @param count pages per protocol and connection kind
   * @param seed the seed of the random losses, so two runs print the same numbers
   */
  public record Trials(int count, int seed) {

    /** Checks the fields. */
    public Trials {
      if (count < 1 || count > MAX_TRIALS) {
        throw new IllegalArgumentException("trials must be from 1 to 1,000,000");
      }
    }
  }
```

- `Trials`: how many pages to simulate (1 to 1,000,000), and the seed. A **seed** is the starting value of a pseudo-random generator: the same seed gives the same sequence, so two runs print the same table.

### `model/PageLoadModel.java`

```java
package io.plainround.seatwave.network.model;

import io.plainround.seatwave.network.report.ConnectionKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
```

- `ArrayList`, `Arrays`, `List`, `Objects`; `SplittableRandom` is a fast pseudo-random generator (Java 8); `RandomGenerator` is the interface that all JDK random generators implement (Java 17). `ConnectionKind` comes from the `report` package.

```java
/**
 * A model of one page load: how many round trips each protocol needs, and how a lost packet delays
 * the responses behind it. It is arithmetic on a timeline, not a network measurement; Lab 3 of
 * Module 5 says how to measure the same thing with a real lossy link.
 *
 * <p>The rules, each a stated simplification:
 *
 * <ul>
 *   <li>Setup: a new TCP connection costs one round trip, then TLS costs one (TLS 1.3) or two (TLS
 *       1.2); a new QUIC connection costs one round trip for both. A reused connection costs none.
 *   <li>Slow start: the server sends one window of segments per round trip, and the window grows by
 *       one segment for every segment acknowledged (RFC 5681), so a full round doubles it and a
 *       round of two segments adds two. A new connection starts at the initial window; a reused one
 *       at the warm one. The fan's bandwidth is never the limit, and a loss does not shrink the
 *       window.
 *   <li>Loss: each packet is lost independently. A loss with at least three packets behind it in
 *       the same round is repaired after the fast-recovery delay (three duplicate ACKs, RFC 5681;
 *       the packet threshold of QUIC, RFC 9002); any other loss, and a lost repair, after the
 *       tail-recovery delay.
 *   <li>Delivery: TCP hands bytes to HTTP/1.1 and HTTP/2 only in order, so a lost packet holds back
 *       every later packet of its connection. QUIC hands each stream its own bytes in order, so a
 *       lost packet holds back only its own stream.
 *   <li>The browser asks for all responses at once (HTTP/2, HTTP/3, six connections), or one after
 *       another on one connection. A real page first fetches its document, which adds a round trip
 *       to every protocol alike.
 * </ul>
 *
 * <p>Thread-safe: it holds only immutable settings; each simulation uses its own random source.
 */
public final class PageLoadModel {
```

- The class comment lists the rules of the model: setup, slow start, loss, delivery and how requests are issued. Each is a stated simplification. `<ul>` and `<li>` are HTML tags for a list, used in Javadoc.

```java
  // Three duplicate ACKs trigger a fast retransmit (RFC 5681); QUIC's packet threshold is also 3.
  private static final int PACKETS_BEHIND_FOR_FAST_RECOVERY = 3;
  private static final double PERCENT = 100.0;
  private static final double HALF = 0.5;
  private static final double NINETY_FIFTH = 0.95;

  private final ModelSettings settings;
  private final int largestResponse;
  private final double oneWayMillis;
```

- 3 is the number of packets behind a lost one that triggers a fast retransmit. `PERCENT = 100.0`, `HALF = 0.5` and `NINETY_FIFTH = 0.95` name the numbers used in the percentile and percent calculations.

```java
  /**
   * Creates the model.
   *
   * @param settings the page, the network and the loss assumptions
   */
  public PageLoadModel(ModelSettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
    List<Integer> sizes = settings.responseBytes();
    int largest = 0;
    for (int responseIndex = 1; responseIndex < sizes.size(); responseIndex++) {
      if (sizes.get(responseIndex) > sizes.get(largest)) {
        largest = responseIndex;
      }
    }
    this.largestResponse = largest;
    this.oneWayMillis = settings.roundTripMillis() / 2;
  }
```

- The constructor stores the settings and finds the **largest response**: the loop starts at index 1 and keeps the index of the biggest size. `sizes.get(responseIndex) > sizes.get(largest)` compares two `Integer` values: the `>` operator unboxes them to `int`. For the default page, index 5 (36,894 bytes).
- `oneWayMillis` is half the round-trip time: 40 ms.

```java
  /**
   * Returns the round trips a protocol spends before the first request can leave.
   *
   * @param protocol the protocol
   * @param kind a new or a reused connection
   * @return 0 for a reused connection; 1 for new QUIC; 1 plus the TLS round trips for new TCP
   */
  public int setupRoundTrips(Protocol protocol, ConnectionKind kind) {
    if (kind == ConnectionKind.REUSED) {
      return 0;
    }
    return switch (protocol) {
      case HTTP3 -> 1;
      case HTTP1_ONE_CONNECTION, HTTP1_SIX_CONNECTIONS, HTTP2 -> 1 + settings.tlsRoundTrips();
    };
  }
```

- `setupRoundTrips`: 0 for a reused connection. For a new one, HTTP/3 costs 1 (QUIC combines the transport and the TLS handshake). The three TCP-based protocols cost `1 + tlsRoundTrips`: 2 with TLS 1.3. `case A, B, C ->` is one `switch` arm for several values.

```java
  /**
   * Simulates many pages and summarizes them.
   *
   * @param protocol the protocol
   * @param kind a new or a reused connection
   * @return p50 and p95 of the whole page, of the small responses and of the largest response
   */
  public Summary summarize(Protocol protocol, ConnectionKind kind) {
    int count = settings.trials().count();
    double[] pages = new double[count];
    double[] small = new double[count];
    double[] largest = new double[count];
    int heldBack = 0;
    // The same seed for every protocol: each one meets the same luck, so the gaps are its own.
    SplittableRandom random = new SplittableRandom(settings.trials().seed());
    for (int trialIndex = 0; trialIndex < count; trialIndex++) {
      PageTimes times = simulate(protocol, kind, random);
      pages[trialIndex] = times.pageMillis();
      small[trialIndex] = times.smallResponsesMillis();
      largest[trialIndex] = times.largestResponseMillis();
      heldBack += times.isHeldBackByAnotherResponse() ? 1 : 0;
    }
    return new Summary(
        protocol,
        kind,
        setupRoundTrips(protocol, kind),
        Percentiles.of(pages),
        Percentiles.of(small),
        Percentiles.of(largest),
        PERCENT * heldBack / count);
  }
```

- `summarize` runs `count` pages for one protocol and connection kind and collects three arrays of times: the whole page, the small responses (all but the largest), and the largest.
- `new SplittableRandom(seed)` is created **inside** the method, so every call starts from the same seed. That is what gives every protocol the same luck.
- `heldBack += times.isHeldBackByAnotherResponse() ? 1 : 0;` counts the pages with head-of-line blocking.
- `PERCENT * heldBack / count` is the share in percent. The result is a `Summary` record with `Percentiles` of each array.

```java
  /**
   * Simulates one page.
   *
   * @param protocol the protocol
   * @param kind a new or a reused connection
   * @param random the source of the losses
   * @return when the page, its small responses and its largest response were complete
   */
  public PageTimes simulate(Protocol protocol, ConnectionKind kind, RandomGenerator random) {
    Objects.requireNonNull(random, "random");
    double requestArrives =
        setupRoundTrips(protocol, kind) * settings.roundTripMillis() + oneWayMillis;
    int window =
        kind == ConnectionKind.NEW
            ? settings.windows().initialSegments()
            : settings.windows().warmSegments();
    return switch (protocol) {
      case HTTP1_ONE_CONNECTION -> oneAfterAnother(requestArrives, window, random);
      case HTTP1_SIX_CONNECTIONS -> sideBySide(requestArrives, window, random);
      case HTTP2 ->
          times(
              delivered(send(interleaved(), requestArrives, window, random), Ordering.CONNECTION));
      case HTTP3 ->
          times(delivered(send(interleaved(), requestArrives, window, random), Ordering.STREAM));
    };
  }
```

- `simulate` plays one page. `requestArrives` is the time at which the first request reaches the server: the setup round trips times the round-trip time, plus one way. `window` is the starting congestion window.
- The `switch` expression calls one method per protocol. For HTTP/1.1 on one connection, `oneAfterAnother`. For six connections, `sideBySide`. For HTTP/2 and HTTP/3, the same two steps: `send(interleaved(), ...)` computes when each segment arrives, and `delivered(..., ordering)` computes when each response is complete. The only difference between them is the ordering: `Ordering.CONNECTION` (TCP's) or `Ordering.STREAM` (QUIC's).

```java
  private PageTimes oneAfterAnother(double requestArrives, int window, RandomGenerator random) {
    int responses = settings.responseBytes().size();
    double[] done = new double[responses];
    double nextRequestArrives = requestArrives;
    int currentWindow = window;
    for (int responseIndex = 0; responseIndex < responses; responseIndex++) {
      Sent sent =
          send(segmentsOf(List.of(responseIndex)), nextRequestArrives, currentWindow, random);
      done[responseIndex] = delivered(sent, Ordering.CONNECTION).done()[responseIndex];
      currentWindow = sent.windowAfter();
      // The next request leaves when this reply is complete, and reaches the server later.
      nextRequestArrives = done[responseIndex] + oneWayMillis;
    }
    // One response at a time: a loss can only delay its own response, then everything after it.
    return times(new Delivery(done, false));
  }
```

- `oneAfterAnother` handles responses in order. For each one: `send(segmentsOf(List.of(responseIndex)), ...)` computes the arrival times of just that response's segments from the moment its request arrives. `sent.windowAfter()` carries the grown window over to the next response (the same connection). The next request leaves when this reply is complete and reaches the server one way later: `done + oneWayMillis`.
- The result is wrapped in `new Delivery(done, false)`: with one response at a time, no response waits for another.

```java
  private PageTimes sideBySide(double requestArrives, int window, RandomGenerator random) {
    int responses = settings.responseBytes().size();
    double[] done = new double[responses];
    for (int responseIndex = 0; responseIndex < responses; responseIndex++) {
      Sent sent = send(segmentsOf(List.of(responseIndex)), requestArrives, window, random);
      done[responseIndex] = delivered(sent, Ordering.CONNECTION).done()[responseIndex];
    }
    // Six connections: each loss stays on its own connection.
    return times(new Delivery(done, false));
  }
```

- `sideBySide` sends every response on its own connection at the same time. Each uses the starting window, and a loss stays on its own connection.

```java
  private PageTimes times(Delivery delivery) {
    double[] done = delivery.done();
    double page = Arrays.stream(done).max().orElseThrow();
    double small = 0;
    for (int responseIndex = 0; responseIndex < done.length; responseIndex++) {
      if (responseIndex != largestResponse) {
        small = Math.max(small, done[responseIndex]);
      }
    }
    // A page of one response has no small responses; the largest then stands for them.
    if (done.length == 1) {
      small = done[0];
    }
    return new PageTimes(page, small, done[largestResponse], delivery.isHeldBackByAnother());
  }
```

- `times` turns the done-time of each response into three numbers. `Arrays.stream(done).max().orElseThrow()` is the latest done time: the page. The loop takes the latest of the responses that are not the largest: the small ones. A page of one response has no small ones, so the largest stands for them.

```java
  private List<Integer> segmentsOf(List<Integer> responseIndexes) {
    List<Integer> owners = new ArrayList<>();
    for (int responseIndex : responseIndexes) {
      int count = segmentCount(responseIndex);
      for (int segmentIndex = 0; segmentIndex < count; segmentIndex++) {
        owners.add(responseIndex);
      }
    }
    return owners;
  }
```

- `segmentsOf` lists the owner of every segment of the given responses, in order. For response 5 it is 26 copies of the number 5. This list drives the simulation: position `i` is the `i`-th segment sent, and the value says which response it belongs to.

```java
  private List<Integer> interleaved() {
    // Streams share one connection round-robin, one segment each in turn, as HTTP/2 and HTTP/3
    // servers commonly do when every stream has the same priority.
    int responses = settings.responseBytes().size();
    int[] left = new int[responses];
    int total = 0;
    for (int responseIndex = 0; responseIndex < responses; responseIndex++) {
      left[responseIndex] = segmentCount(responseIndex);
      total += left[responseIndex];
    }
    List<Integer> owners = new ArrayList<>(total);
    while (owners.size() < total) {
      for (int responseIndex = 0; responseIndex < responses; responseIndex++) {
        if (left[responseIndex] > 0) {
          owners.add(responseIndex);
          left[responseIndex]--;
        }
      }
    }
    return owners;
  }
```

- `interleaved` builds the same list for HTTP/2 and HTTP/3: streams share one connection round-robin, **one segment each in turn**, as servers commonly do when every stream has the same priority. The loop `while (owners.size() < total)` goes around all responses and takes one segment from each that still has some left.
- For the default page the owner list starts `0 1 2 3 4 5 0 1 2 3 4 5 5 5 5 ...`: the small responses end after two turns and the big one continues alone.

```java
  private int segmentCount(int responseIndex) {
    int bytes = settings.responseBytes().get(responseIndex);
    return (bytes + settings.segmentBytes() - 1) / settings.segmentBytes();
  }
```

- `segmentCount`: `(bytes + segmentBytes - 1) / segmentBytes` is **ceiling division** with whole numbers. `(2000 + 1459) / 1460 = 2`; `(36894 + 1459) / 1460 = 26`.

```java
  private Sent send(
      List<Integer> owners, double requestArrives, int window, RandomGenerator random) {
    double[] arrivals = new double[owners.size()];
    double roundStarts = requestArrives;
    int currentWindow = window;
    int next = 0;
    while (next < owners.size()) {
      int inRound = Math.min(currentWindow, owners.size() - next);
      for (int positionInRound = 0; positionInRound < inRound; positionInRound++) {
        int packetsBehind = inRound - 1 - positionInRound;
        arrivals[next + positionInRound] =
            roundStarts + oneWayMillis + recoveryDelay(packetsBehind, random);
      }
      next += inRound;
      // The ACKs of this round come back one round trip later and open the next round; each one
      // grows the window by a segment, so the window grows only by what was really sent.
      roundStarts += settings.roundTripMillis();
      currentWindow = (int) Math.min((long) currentWindow + inRound, Integer.MAX_VALUE);
    }
    return new Sent(owners, arrivals, currentWindow);
  }
```

- `send` computes when every segment arrives. It works in **rounds**: `inRound = Math.min(currentWindow, owners.size() - next)` is how many segments go in this round.
- For each segment of the round, `packetsBehind = inRound - 1 - positionInRound` counts how many were sent after it in the same round. The arrival time is `roundStarts + oneWayMillis + recoveryDelay(...)`: the start of the round, one way, and a delay that is 0 unless the packet is lost.
- After the round, `roundStarts += settings.roundTripMillis()`: the acknowledgements come back one round trip later and open the next round. The window grows by the number of segments sent: `(int) Math.min((long) currentWindow + inRound, Integer.MAX_VALUE)`. The `long` cast stops an `int` from overflowing and wrapping to a negative number; the `Math.min` caps the result.
- It returns a `Sent` record: the owners, the arrival times, and the window after the last round.

```java
  private double recoveryDelay(int packetsBehind, RandomGenerator random) {
    double lossChance = settings.loss().percent() / PERCENT;
    double delay = 0;
    boolean isFirstAttempt = true;
    while (random.nextDouble() < lossChance) {
      boolean isRevealedByLaterPackets =
          isFirstAttempt && packetsBehind >= PACKETS_BEHIND_FOR_FAST_RECOVERY;
      delay +=
          isRevealedByLaterPackets
              ? settings.loss().fastRecoveryMillis()
              : settings.loss().tailRecoveryMillis();
      isFirstAttempt = false;
    }
    return delay;
  }
```

- `recoveryDelay`: draw a random number between 0 and 1 (`random.nextDouble()`). While it is below the loss chance (`percent / 100`), the packet is lost again. The first loss is repaired by **fast recovery** if at least 3 packets are behind it, so its delay is `fastRecoveryMillis`. Any other loss (fewer than 3 behind, or a lost retransmission) costs `tailRecoveryMillis`. The delays add up in the loop.
- With 0 percent loss, `nextDouble() < 0` is never true, so the delay is always 0, and the model is deterministic.

```java
  private Delivery delivered(Sent sent, Ordering ordering) {
    int responses = settings.responseBytes().size();
    double[] done = new double[responses];
    double[] streamHandedOver = new double[responses];
    double connectionHandedOver = 0;
    int connectionLatestOwner = -1;
    boolean isHeldBackByAnother = false;
    for (int packetIndex = 0; packetIndex < sent.owners().size(); packetIndex++) {
      int owner = sent.owners().get(packetIndex);
      double arrival = sent.arrivals()[packetIndex];
      double handedOver;
      if (ordering == Ordering.CONNECTION) {
        // Head-of-line blocking: this packet is here, but TCP keeps it until an earlier,
        // later-arriving packet of another response is repaired.
        if (connectionHandedOver > arrival && connectionLatestOwner != owner) {
          isHeldBackByAnother = true;
        }
        if (arrival > connectionHandedOver) {
          connectionHandedOver = arrival;
          connectionLatestOwner = owner;
        }
        handedOver = connectionHandedOver;
      } else {
        streamHandedOver[owner] = Math.max(streamHandedOver[owner], arrival);
        handedOver = streamHandedOver[owner];
      }
      done[owner] = Math.max(done[owner], handedOver);
    }
    return new Delivery(done, isHeldBackByAnother);
  }
```

- `delivered` computes when each response is complete from the arrival times. It walks the segments in the order they were **sent**.
- `Ordering.CONNECTION` (TCP): `connectionHandedOver` is the time at which TCP can hand the byte stream up to the application. A segment cannot be handed up before all segments before it in the byte order. So the hand-over time is the running maximum of arrival times. When `connectionHandedOver > arrival` and the latest owner is another response, this packet arrived but is held back by a lost packet of another response: head-of-line blocking, and the flag is set.
- `Ordering.STREAM` (QUIC): the running maximum is kept **per stream** in `streamHandedOver[owner]`. A lost packet holds back only its own stream.
- `done[owner] = Math.max(done[owner], handedOver)`: a response is complete when its last segment is handed over.

```java
  private enum Ordering {
    CONNECTION,
    STREAM
  }

  private record Sent(List<Integer> owners, double[] arrivals, int windowAfter) {}

  private record Delivery(double[] done, boolean isHeldBackByAnother) {}
```

- A private enum for the two orderings and two private records for the intermediate results: `Sent` (owners, arrival times, window after) and `Delivery` (done times, held-back flag).

```java
  /**
   * When one simulated page was complete, in milliseconds after the fan's click.
   *
   * @param pageMillis the last byte of the last response
   * @param smallResponsesMillis the last byte of every response but the largest
   * @param largestResponseMillis the last byte of the largest response
   * @param isHeldBackByAnotherResponse true if a packet that had arrived waited for a lost packet
   *     of another response: head-of-line blocking at the transport
   */
  public record PageTimes(
      double pageMillis,
      double smallResponsesMillis,
      double largestResponseMillis,
      boolean isHeldBackByAnotherResponse) {}
```

- `PageTimes`: when the whole page, the small responses and the largest response were complete. The `isHeldBackByAnotherResponse` flag is the head-of-line blocking mark.

```java
  /**
   * The 50th and 95th percentile of many simulated pages.
   *
   * @param p50 the median
   * @param p95 the value 95 out of 100 pages beat
   */
  public record Percentiles(double p50, double p95) {

    static Percentiles of(double[] values) {
      double[] sorted = values.clone();
      Arrays.sort(sorted);
      return new Percentiles(nearestRank(sorted, HALF), nearestRank(sorted, NINETY_FIFTH));
    }

    private static double nearestRank(double[] sorted, double fraction) {
      // Nearest rank: the smallest value with at least this fraction of values at or below it.
      int rank = (int) Math.ceil(fraction * sorted.length);
      return sorted[Math.max(rank, 1) - 1];
    }
  }
```

- `Percentiles` holds the p50 and the p95. `of` copies the array (`values.clone()`) and sorts the copy.
- `nearestRank` uses the **nearest-rank method**: `rank = ceil(fraction x n)`, and the answer is the value at position `rank` (counting from 1; the code subtracts 1 for the array). For 10,000 trials the p50 is the 5,000th smallest and the p95 the 9,500th.

```java
  /**
   * The model's answer for one protocol and connection kind.
   *
   * @param protocol the protocol
   * @param kind new or reused connections
   * @param setupRoundTrips round trips before the first request leaves
   * @param page the whole page
   * @param smallResponses every response but the largest
   * @param largestResponse the largest response, the seat map
   * @param heldBackPercent the share of pages with head-of-line blocking between responses
   */
  public record Summary(
      Protocol protocol,
      ConnectionKind kind,
      int setupRoundTrips,
      Percentiles page,
      Percentiles smallResponses,
      Percentiles largestResponse,
      double heldBackPercent) {}
```

- `Summary` is the answer for one protocol and connection kind.

### `model/PageModelReport.java`

```java
package io.plainround.seatwave.network.model;

import io.plainround.seatwave.network.report.ConnectionKind;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Writes the page model's assumptions and its table as plain text. Thread-safe: it holds no state.
```

- `ConnectionKind` is reused from the `report` package: `NEW` and `REUSED`.

```java
public final class PageModelReport {

  // Light in glass fiber covers about 200 km per millisecond, two thirds of its speed in a vacuum.
  private static final double FIBER_KM_PER_MILLI = 200.0;
  private static final String HEADER =
      "protocol                 connection  setup RTTs  page p50  page p95  RTTs at p50"
          + "  small p50  small p95  largest p50  largest p95  held back %";
  private static final String ROW =
      "%-24s %-10s %11d %9.0f %9.0f %12.1f %10.0f %10.0f %12.0f %12.0f %12.1f%n";

  private PageModelReport() {}
```

- `FIBER_KM_PER_MILLI = 200.0`: light in glass fiber covers about 200 km per millisecond, two thirds of its speed in a vacuum. It sets the **floor** for the round-trip time: 8,000 km each way is 16,000 km at 200 km per millisecond, 80 ms.
- `HEADER` and `ROW` are the header text and the format string of the table (see `java-report-walkthrough.md` for the format codes).

```java
  /**
   * Runs the model for every protocol, over new and over reused connections, and writes the table.
   *
   * @param settings the model's inputs
   * @return the text
   */
  public static String render(ModelSettings settings) {
    Objects.requireNonNull(settings, "settings");
    PageLoadModel model = new PageLoadModel(settings);
    StringBuilder text = new StringBuilder(assumptions(settings));
    text.append(HEADER).append(System.lineSeparator());
    for (ConnectionKind kind : List.of(ConnectionKind.NEW, ConnectionKind.REUSED)) {
      for (Protocol protocol : settings.protocols()) {
        PageLoadModel.Summary summary = model.summarize(protocol, kind);
        text.append(
            String.format(
                Locale.ROOT,
                ROW,
                protocol.label(),
                kind.name().toLowerCase(Locale.ROOT),
                summary.setupRoundTrips(),
                summary.page().p50(),
                summary.page().p95(),
                summary.page().p50() / settings.roundTripMillis(),
                summary.smallResponses().p50(),
                summary.smallResponses().p95(),
                summary.largestResponse().p50(),
                summary.largestResponse().p95(),
                summary.heldBackPercent()));
      }
    }
    return text.toString();
  }
```

- `render` runs the model for every protocol over new and then reused connections and writes one row each. `model.summarize(protocol, kind)` returns the numbers. `summary.page().p50() / settings.roundTripMillis()` is the p50 expressed in round trips.

```java
  private static String assumptions(ModelSettings settings) {
    double floorMillis = 2 * settings.distanceKm() / FIBER_KM_PER_MILLI;
    return String.format(
        Locale.ROOT,
        "MODEL, not a measurement. Distance %,d km: the speed-of-light floor in fiber is %.1f ms"
            + " round trip; the model uses %.1f ms.%n"
            + "Page: %d responses of %s bytes; %d-byte segments; window %d segments new, %d"
            + " reused; TLS %d round trip(s) over TCP.%n"
            + "Loss %.1f percent per packet; repair after %.0f ms (3 packets behind) or %.0f ms"
            + " (tail); %,d pages per row, seed %d. Times in ms after the click.%n%n",
        settings.distanceKm(),
        floorMillis,
        settings.roundTripMillis(),
        settings.responseBytes().size(),
        settings.responseBytes().stream()
            .map(String::valueOf)
            .reduce((first, second) -> first + ", " + second)
            .orElseThrow(),
        settings.segmentBytes(),
        settings.windows().initialSegments(),
        settings.windows().warmSegments(),
        settings.tlsRoundTrips(),
        settings.loss().percent(),
        settings.loss().fastRecoveryMillis(),
        settings.loss().tailRecoveryMillis(),
        settings.trials().count(),
        settings.trials().seed());
  }
```

- `assumptions` writes the three heading lines: **"MODEL, not a measurement"**, the distance and the floor (`2 * distanceKm / 200.0`), the page (the response sizes joined with commas by `stream().map(String::valueOf).reduce(...)`), the segment size and windows, the loss and recovery settings, the number of pages and the seed.
- `%,d` prints a whole number with thousands separators (`8,000`). `%.1f` prints one decimal. `%.0f` none.

### `model/PageModelCli.java`

```java
package io.plainround.seatwave.network.model;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
```

- Imports: `Result`, the settings reader, and file classes.

```java
/**
 * Command-line entry point of the page model: round trips per protocol at zero loss, and the effect
 * of packet loss on each protocol. Exit status 2 means bad usage or settings.
 */
public final class PageModelCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;

  private PageModelCli() {}
```

- The exit status 2 means bad usage or settings.

```java
  /**
   * Prints the model's table.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: pageModel <page-model.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (ModelSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<ModelSettings, List<String>>(ModelSettings settings) ->
            System.out.print(PageModelReport.render(settings));
        case Result.Error<ModelSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid setting: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException unreadable) {
      System.err.println("cannot read " + arguments[0] + ": " + unreadable.getMessage());
      System.exit(EXIT_BAD_USAGE);
    }
  }
}
```

- `main` checks that there is one argument, reads the file with `SettingsReader.load`, calls `ModelSettings.from`, and handles the `Result` with the same pattern-matching `switch` as the other commands. A good result prints `PageModelReport.render(settings)`. An error prints `invalid setting:` plus each problem and exits with 2. `catch (IOException unreadable)` handles a file that cannot be read.

### `config/SettingsReader.java`

```java
package io.plainround.seatwave.network.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
```

- `Reader` is a stream of characters. `Properties` is the JDK's class for `.properties` files. `Pattern` is a regular expression.

```java
/**
 * Reads typed values from a settings file and collects one problem line per missing or invalid key,
 * so a learner sees every mistake in one run.
 *
 * <p>There are no defaults: a key that is absent is a problem, never a silent fallback. When a key
 * is invalid, the method records the problem and returns a harmless stand-in value; the caller must
 * check {@link #problems()} before using any value. Not thread-safe: one reader is used by one
 * thread while it builds one settings record.
 */
public final class SettingsReader {

  private final Properties properties;
  private final List<String> problems = new ArrayList<>();

  /**
   * Wraps loaded properties.
   *
   * @param properties the settings, already loaded
   */
  public SettingsReader(Properties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }
```

- The class comment says what it does: reads typed values and collects one problem line per missing or invalid key, so a learner sees every mistake in one run. There are no defaults. When a key is invalid, the method records the problem and returns a harmless stand-in; the caller must check `problems()` first.
- It is **not thread-safe**: one reader is used by one thread while it builds one settings record.
- The fields: the loaded `properties` and a list of problem texts.

```java
  /**
   * Loads a settings file as UTF-8.
   *
   * @param path the file
   * @return the loaded properties
   * @throws IOException if the file cannot be read
   */
  public static Properties load(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }
```

- `load` opens the file as UTF-8 with `Files.newBufferedReader(path, StandardCharsets.UTF_8)` inside a try-with-resources and calls `properties.load(reader)`. Giving it a `Reader` (characters) and not a byte stream matters: the JDK reads a byte stream as ISO-8859-1, which would corrupt non-ASCII text.

```java
  /**
   * Reads a whole number inside a range.
   *
   * @param key the key
   * @param minimum the smallest allowed value
   * @param maximum the largest allowed value
   * @return the value, or the minimum if the key is missing or invalid
   */
  public int intBetween(String key, int minimum, int maximum) {
    String text = text(key);
    if (text.isEmpty()) {
      return minimum;
    }
    try {
      int value = Integer.parseInt(text);
      if (value < minimum || value > maximum) {
        problems.add(
            String.format(
                Locale.ROOT, "%s must be from %d to %d, but was %d", key, minimum, maximum, value));
        return minimum;
      }
      return value;
    } catch (NumberFormatException notANumber) {
      // A typo is an expected failure of a settings file, so it becomes a problem line, not a
      // crash.
      problems.add(key + " must be a whole number, but was " + text);
      return minimum;
    }
  }
```

- `intBetween`: read the text (`text(key)` records "is missing" if absent). If the text is empty, return the minimum as a stand-in. `Integer.parseInt(text)` parses a whole number. A value outside the range records `"<key> must be from <min> to <max>, but was <value>"` and returns the minimum. `catch (NumberFormatException notANumber)` records `"<key> must be a whole number, but was <text>"`.
- A typo is an **expected failure** of a settings file, so it becomes a problem line, not a crash.

```java
  /**
   * Reads a decimal number inside a range.
   *
   * @param key the key
   * @param minimum the smallest allowed value
   * @param maximum the largest allowed value
   * @return the value, or the minimum if the key is missing or invalid
   */
  public double decimalBetween(String key, double minimum, double maximum) {
    String text = text(key);
    if (text.isEmpty()) {
      return minimum;
    }
    try {
      double value = Double.parseDouble(text);
      if (!(value >= minimum && value <= maximum)) {
        problems.add(
            String.format(
                Locale.ROOT, "%s must be from %s to %s, but was %s", key, minimum, maximum, text));
        return minimum;
      }
      return value;
    } catch (NumberFormatException notANumber) {
      problems.add(key + " must be a decimal number, but was " + text);
      return minimum;
    }
  }
```

- `decimalBetween` is the same with `Double.parseDouble`. The check `!(value >= minimum && value <= maximum)` also refuses NaN. The message prints the original text.

```java
  /**
   * Reads a comma-separated list of whole numbers, each inside a range.
   *
   * @param key the key
   * @param minimum the smallest allowed value
   * @param maximum the largest allowed value
   * @return the values in file order, or an empty list if the key is missing or invalid
   */
  public List<Integer> intListBetween(String key, int minimum, int maximum) {
    List<Integer> values = new ArrayList<>();
    for (String item : items(key)) {
      try {
        int value = Integer.parseInt(item);
        if (value < minimum || value > maximum) {
          problems.add(
              String.format(
                  Locale.ROOT,
                  "every value of %s must be from %d to %d, but one was %d",
                  key,
                  minimum,
                  maximum,
                  value));
          return List.of();
        }
        values.add(value);
      } catch (NumberFormatException notANumber) {
        problems.add(key + " must list whole numbers, but one was " + item);
        return List.of();
      }
    }
    return List.copyOf(values);
  }
```

- `intListBetween` reads a comma-separated list through `items(key)`. Each item is parsed and range-checked. At the first bad item, it records the problem and returns an empty list.
- `List.copyOf(values)` returns an unchangeable list.

```java
  /**
   * Reads a duration written in milliseconds, inside a range.
   *
   * @param key the key, which by convention ends in {@code .millis}
   * @param minimumMillis the smallest allowed value
   * @param maximumMillis the largest allowed value
   * @return the duration, or the minimum if the key is missing or invalid
   */
  public Duration millisBetween(String key, int minimumMillis, int maximumMillis) {
    return Duration.ofMillis(intBetween(key, minimumMillis, maximumMillis));
  }
```

- `millisBetween` reads a whole number and returns it as a `Duration` in milliseconds. By convention such keys end in `.millis`.

```java
  /**
   * Reads a comma-separated list of enum constants, written in lower case.
   *
   * @param key the key
   * @param type the enum class
   * @param <E> the enum type
   * @return the constants in file order, or an empty list if the key is missing or invalid
   */
  public <E extends Enum<E>> List<E> enumList(String key, Class<E> type) {
    Objects.requireNonNull(type, "type");
    List<E> values = new ArrayList<>();
    for (String item : items(key)) {
      try {
        values.add(Enum.valueOf(type, item.toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException unknown) {
        problems.add(key + " has an unknown value: " + item);
        return List.of();
      }
    }
    return List.copyOf(values);
  }
```

- `enumList` reads a list of enum constants written in lower case. `<E extends Enum<E>>` is a **bounded type parameter**: `E` must be an enum type. `Class<E> type` is the enum's class object, such as `Protocol.class`. `Enum.valueOf(type, item.toUpperCase(Locale.ROOT))` finds the constant by name, and throws `IllegalArgumentException` if there is none, which is caught and recorded as `"<key> has an unknown value: <item>"`. `Locale.ROOT` avoids a locale bug: in a Turkish locale, `"i".toUpperCase()` is a dotted capital I.

```java
  /**
   * Reads a text value that must match a pattern.
   *
   * @param key the key
   * @param allowed the pattern the whole value must match
   * @return the trimmed value, or an empty string if the key is missing or does not match
   */
  public String textMatching(String key, Pattern allowed) {
    Objects.requireNonNull(allowed, "allowed");
    String text = text(key);
    if (text.isEmpty()) {
      return text;
    }
    if (!allowed.matcher(text).matches()) {
      problems.add(key + " must match " + allowed.pattern() + ", but was " + text);
      return "";
    }
    return text;
  }
```

- `textMatching` reads a text that must match a pattern entirely. A mismatch records the key, the pattern and the value, and returns an empty string.

```java
  /**
   * Reads a comma-separated list of words, each from an allow-list.
   *
   * @param key the key
   * @param allowed the only words accepted, compared exactly
   * @return the words in file order, or an empty list if the key is missing or invalid
   */
  public List<String> wordsIn(String key, List<String> allowed) {
    Objects.requireNonNull(allowed, "allowed");
    List<String> words = items(key);
    for (String word : words) {
      if (!allowed.contains(word)) {
        problems.add(key + " has " + word + ", but allows only " + String.join(", ", allowed));
        return List.of();
      }
    }
    return List.copyOf(words);
  }
```

- `wordsIn` reads a comma-separated list where each word must be in an allow-list. It compares exactly. The message names the allowed words.

```java
  /**
   * Reads one enum constant, written in lower case.
   *
   * @param key the key
   * @param type the enum class
   * @param fallback the harmless stand-in returned when the key is missing or invalid
   * @param <E> the enum type
   * @return the constant, or the fallback if the key is missing or invalid
   */
  public <E extends Enum<E>> E enumValue(String key, Class<E> type, E fallback) {
    Objects.requireNonNull(fallback, "fallback");
    List<E> values = enumList(key, type);
    if (values.size() > 1) {
      problems.add(key + " takes one value, but has " + values.size());
      return fallback;
    }
    return values.isEmpty() ? fallback : values.getFirst();
  }
```

- `enumValue` reads one enum constant. It calls `enumList`, and refuses more than one value. If the list is empty (missing or invalid), it returns the **fallback**: the stand-in. `values.getFirst()` is the first item (Java 21).

```java
  /**
   * Reads a text value.
   *
   * @param key the key
   * @return the trimmed value, or an empty string if the key is missing or blank
   */
  public String text(String key) {
    Objects.requireNonNull(key, "key");
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      problems.add(key + " is missing");
      return "";
    }
    return value.strip();
  }
```

- `text` is the base of every read. `properties.getProperty(key)` returns `null` if the key is absent. A null or blank value records `"<key> is missing"` and returns an empty string. Otherwise `value.strip()` removes spaces from both ends. (`Properties` keeps trailing spaces in a value, which is why `strip()` is needed.)

```java
  /**
   * Returns every problem found so far.
   *
   * @return the problems, in the order they were found; empty when every key was valid
   */
  public List<String> problems() {
    return List.copyOf(problems);
  }
```

- `problems()` returns a copy of the list in the order the problems were found.

```java
  private List<String> items(String key) {
    String text = text(key);
    if (text.isEmpty()) {
      return List.of();
    }
    List<String> items = new ArrayList<>();
    for (String item : text.split(",", -1)) {
      if (item.isBlank()) {
        problems.add(key + " has an empty item");
        return List.of();
      }
      items.add(item.strip());
    }
    return items;
  }
}
```

- `items` splits a comma-separated value. `text.split(",", -1)` keeps empty trailing items (the `-1` is the limit argument), so `a,b,` is reported as `has an empty item` instead of silently dropping the last one. Each item is stripped.

## Try it yourself

All commands run in the repository folder and use no network. The model is deterministic: the same file gives the same table on every run and on every machine.

**1. Round trips with no loss.**

```text
./gradlew -q pageModel --args='config/round-trips.properties'
```

```text
MODEL, not a measurement. Distance 8,000 km: the speed-of-light floor in fiber is 80.0 ms round trip; the model uses 80.0 ms.
Page: 6 responses of 2000, 2000, 2000, 2000, 2000, 36894 bytes; 1460-byte segments; window 10 segments new, 64 reused; TLS 1 round trip(s) over TCP.
Loss 0.0 percent per packet; repair after 80 ms (3 packets behind) or 160 ms (tail); 1 pages per row, seed 20261110. Times in ms after the click.

protocol                 connection  setup RTTs  page p50  page p95  RTTs at p50  small p50  small p95  largest p50  largest p95  held back %
HTTP/1.1, 1 connection   new                  2       720       720          9.0        560        560          720          720          0.0
HTTP/1.1, 6 connections  new                  2       320       320          4.0        240        240          320          320          0.0
HTTP/2                   new                  2       400       400          5.0        320        320          400          400          0.0
HTTP/3                   new                  1       320       320          4.0        240        240          320          320          0.0
HTTP/1.1, 1 connection   reused               0       480       480          6.0        400        400          480          480          0.0
HTTP/1.1, 6 connections  reused               0        80        80          1.0         80         80           80           80          0.0
HTTP/2                   reused               0        80        80          1.0         80         80           80           80          0.0
HTTP/3                   reused               0        80        80          1.0         80         80           80           80          0.0
```

Check three rows against the arithmetic table above: 720, 320 and 400 ms.

**2. The same page with 2 percent packet loss.**

```text
./gradlew -q pageModel --args='config/page-model.properties'
```

```text
protocol                 connection  setup RTTs  page p50  page p95  RTTs at p50  small p50  small p95  largest p50  largest p95  held back %
HTTP/1.1, 1 connection   new                  2       720       960          9.0        560        720          720          960          0.0
HTTP/1.1, 6 connections  new                  2       320       480          4.0        240        400          320          480          0.0
HTTP/2                   new                  2       400       560          5.0        320        400          400          560         20.1
HTTP/3                   new                  1       320       480          4.0        240        320          320          480          0.0
HTTP/1.1, 1 connection   reused               0       560       720          7.0        400        560          560          720          0.0
HTTP/1.1, 6 connections  reused               0       160       240          2.0         80        240           80          240          0.0
HTTP/2                   reused               0       160       240          2.0         80        160          160          240         20.1
HTTP/3                   reused               0       160       240          2.0         80        160           80          240          0.0
```

(The three heading lines are the same as before, except `2.0 percent per packet` and `10,000 pages per row`.) On a reused connection the page has 36 packets, so the chance that at least one is lost is `1 - 0.98^36 = 51.7` percent. That is why the median reused page takes 160 ms, not 80: the median page contains one fast-recovered loss (+80 ms). Look at the `held back %` column: only the HTTP/2 rows are not 0.

**3. Six seat maps.**

```text
./gradlew -q pageModel --args='config/page-model-six-maps.properties'
```

```text
protocol                 connection  setup RTTs  page p50  page p95  RTTs at p50  small p50  small p95  largest p50  largest p95  held back %
HTTP/1.1, 1 connection   new                  2       960      1200         12.0        960       1200          320          480          0.0
HTTP/1.1, 6 connections  new                  2       400       480          5.0        400        480          320          480          0.0
HTTP/2                   new                  2       560       720          7.0        560        720          560          640         95.9
HTTP/3                   new                  1       480       640          6.0        480        640          480          480          0.0
HTTP/1.1, 1 connection   reused               0       720       960          9.0        720        960           80          240          0.0
HTTP/1.1, 6 connections  reused               0       160       320          2.0        160        320           80          240          0.0
HTTP/2                   reused               0       240       320          3.0        240        320          240          320         95.9
HTTP/3                   reused               0       240       320          3.0        240        320          160          240          0.0
```

Here the six responses are of equal size, so the "largest" is the first one (the loop keeps the first of equal values), and the "small" column holds the other five. HTTP/2 is held back in 95.9 percent of the pages, and its page time is worse than HTTP/3's (560 against 480 ms, new connection).

**4. A settings file with four mistakes.** Make a copy with a bad number, a value out of range, a deleted key and an unknown protocol, and run it:

```text
sed -e 's/^rtt.millis=80/rtt.millis=abc/' -e '/^trials=/d' -e 's/^loss.percent=2/loss.percent=75/' -e 's/^protocols=.*/protocols=http1_one_connection,quic/' config/page-model.properties > build/bad-model.properties
./gradlew -q pageModel --args=build/bad-model.properties
```

```text
invalid setting: rtt.millis must be a decimal number, but was abc
invalid setting: loss.percent must be from 0.0 to 50.0, but was 75
invalid setting: trials is missing
invalid setting: protocols has an unknown value: quic
```

One run reports all four. The program exits with status 2. Delete the copy afterwards (`build/` is ignored by git).

## Mistakes people make

- **Mistaking the model for a measurement.** The report's first words are "MODEL, not a measurement", and every input is printed.
- **Giving each protocol different random luck.** The model creates the generator from the same seed for each protocol.
- **Letting an `int` overflow.** The window growth is computed in `long` and capped.
- **Forgetting NaN.** Every comparison with NaN (not a number) is false, so a check like `value < minimum` lets it through. The range checks are written with the negation outside, so NaN fails them.
- **Defaults hidden in code.** `SettingsReader` has none: a missing key is a reported problem.
- **Stopping at the first bad key.** The reader collects every problem, so one run shows all of them.
- **Reading a settings file with the wrong encoding.** `load` passes a UTF-8 `Reader`.
- **Case problems in enum names.** `Locale.ROOT` is used when upper-casing.

## Check yourself

1. How many round trips pass before the first request leaves for HTTP/1.1 over a new HTTPS connection with TLS 1.3, and for HTTP/3? (2 for TCP plus TLS 1.3, 1 for QUIC.)
2. Why does HTTP/2 show head-of-line blocking in the model and HTTP/3 does not? (TCP hands bytes up in order for the whole connection, so one lost packet holds back every later packet. QUIC keeps order per stream, so a loss holds back only its own stream.)
3. For a response of 36,894 bytes and 1,460-byte segments, how many segments, and how does the code compute it? (26; ceiling division `(bytes + segmentBytes - 1) / segmentBytes`.)
4. Why is `SplittableRandom` created inside `summarize` with the same seed? (So every protocol meets the same sequence of losses and two runs print the same numbers.)
5. What does `SettingsReader.intBetween` return for a missing key, and why is that safe? (The minimum, plus a problem line. It is safe because every caller checks `problems()` and returns `Result.error` before building anything from the stand-ins.)

## Further reading

Official documentation, for the JDK this repository pins (Java 25), and for the protocols the model is about:

- `java.util.SplittableRandom`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/SplittableRandom.html
- `java.util.Properties`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html
- TCP congestion control, slow start (RFC 5681): https://www.rfc-editor.org/rfc/rfc5681
- Increasing TCP's initial window (RFC 6928): https://www.rfc-editor.org/rfc/rfc6928
- HTTP/2 (RFC 9113): https://www.rfc-editor.org/rfc/rfc9113
- HTTP/3 (RFC 9114) and QUIC (RFC 9000): https://www.rfc-editor.org/rfc/rfc9114 and https://www.rfc-editor.org/rfc/rfc9000
- QUIC loss detection (RFC 9002): https://www.rfc-editor.org/rfc/rfc9002

# Tests of the `report` package, file by file

## What it is

This file walks through the three test classes of the `report` package: `CurlLineTest`, `PhaseTimingsTest` and `TimingReportTest`. Together they have 18 tests (7, 5 and 6). They prove the central promise of the repository: **every timing in the report is the median of at least 3 runs, and every phase row is one real run whose five phases are each zero or more and sum to its total within 1 ms** (`ARCHITECTURE.md`).

An **automated test** is a method that runs a piece of the program and checks the result, so you do not have to check by eye. The tool that runs them is JUnit (`junit.md`), and the checks are written with AssertJ (`assertj.md`). A test has three steps, called **arrange, act, assert**: build the input, call the code, check the outcome. A **unit test** checks one small piece of code without starting servers or touching the network. All three classes here are unit tests: they run in milliseconds, and the code they check (the `report` package) depends on nothing outside itself.

## Why this repository uses it

The report is the answer to the lesson's question "where does the time go?". A parser that reads one number wrong, or a table that prints a phase that does not belong to the run, would give a wrong answer that still looks right. These tests keep the arithmetic honest.

## Where it appears

| Path | What it tests |
| --- | --- |
| `src/test/java/io/plainround/seatwave/network/report/CurlLineTest.java` | `CurlLine.parse`: one line of `curl -w` output becomes a checked sample, or a stated problem. 7 tests. |
| `src/test/java/io/plainround/seatwave/network/report/PhaseTimingsTest.java` | `PhaseTimings`: the invariant (no negative phase, phases sum to the total within a tolerance). 5 tests. |
| `src/test/java/io/plainround/seatwave/network/report/TimingReportTest.java` | `TargetTiming`, `ReuseTiming`, `ReuseSample`, `CurlSample` and `TimingReport`: the median run, the refusals and the printed tables. 6 tests. |

## How it works underneath

1. Gradle compiles the tests with `-Xlint:all -Werror` like the main code, then starts a JVM that runs them (`gradle.md`).
2. For each `@Test` method JUnit makes a new object of the class and calls the method (`junit.md`).
3. A test passes if it returns, and fails if a check throws.

**How these tests stay deterministic.** A test is *deterministic* when it gives the same answer every time. These tests are, because nothing in them is random, nothing reads the clock and nothing waits for another program. Every input is a fixed text such as a `curl` line with fixed readings. Every expected value is worked out by hand from those readings, and the file explains how below. A test that measured real time could pass on one machine and fail on another; these never do.

## Walkthrough of this repository's use

### `CurlLineTest`: the imports and the two sample lines

```java
package io.plainround.seatwave.network.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CurlLineTest {

  static final String NEW_HTTPS =
      "seatwave-https dns=0.000080 connect=0.000247 tls=0.005957 ttfb=0.007095 total=0.008013"
          + " http=1.1 code=200 size=36894 connects=1 server=app;dur=0.26";
  static final String REUSED_HTTPS =
      "seatwave-https dns=0.000010 connect=0.000000 tls=0.000000 ttfb=0.000776 total=0.001610"
          + " http=1.1 code=200 size=36894 connects=0 server=app;dur=0.25";
```

- `package io.plainround.seatwave.network.report;` puts the test in the same package as the code it tests, so it can use package-private classes.
- `import static org.assertj.core.api.Assertions.assertThat;` is a static import of the AssertJ entry point (`assertj.md`). `Result` is the project's success-or-error type (`result4j.md`). `Duration` is the JDK's span of time. `Test` is the JUnit annotation.
- `class CurlLineTest {` has no `public`: JUnit allows package-private test classes.
- `static final String NEW_HTTPS = "..." + "...";` is one line of `curl` output written in two pieces joined with `+`, because the source line would be too long. The text is `seatwave-https dns=0.000080 connect=0.000247 tls=0.005957 ttfb=0.007095 total=0.008013 http=1.1 code=200 size=36894 connects=1 server=app;dur=0.26`. These are cumulative clock readings in seconds (`curl-and-openssl.md`): a new HTTPS connection to the SeatWave server. `static final` makes it a constant that other test classes can read; `TimingReportTest` does.
- `REUSED_HTTPS` is the same request on a reused connection: `connect=0.000000`, `tls=0.000000` and `connects=0`. The readings are fixed, so every run of the test sees the same line.

### Turning cumulative readings into five phases

```java
  @Test
  void turnsCumulativeReadingsIntoFivePhases() {
    CurlSample sample = parsed(NEW_HTTPS);

    PhaseTimings phases = sample.phases();
    assertThat(phases.dns()).isEqualTo(Duration.ofNanos(80_000));
    assertThat(phases.connect()).isEqualTo(Duration.ofNanos(167_000));
    assertThat(phases.tls()).isEqualTo(Duration.ofNanos(5_710_000));
    assertThat(phases.firstByte()).isEqualTo(Duration.ofNanos(1_138_000));
    assertThat(phases.transfer()).isEqualTo(Duration.ofNanos(918_000));
    assertThat(phases.total()).isEqualTo(Duration.ofNanos(8_013_000));
    assertThat(phases.dominant()).isEqualTo(Phase.TLS);
    assertThat(sample.kind()).isEqualTo(ConnectionKind.NEW);
    assertThat(sample.protocol()).isEqualTo("HTTP/1.1");
    assertThat(sample.serverTiming()).isEqualTo("app;dur=0.26");
  }
```

- **Arrange and act in one:** `parsed(NEW_HTTPS)` calls the helper at the bottom of the file (explained below), which returns the checked sample or fails the test.
- Each phase is the difference of two readings. DNS: 0.000080 s, which is 80,000 ns (`Duration.ofNanos(80_000)`; underscores in Java numbers are only for reading). Connect: 0.000247 minus 0.000080 is 0.000167 s, 167,000 ns. TLS: 0.005957 minus 0.000247 is 0.005710 s, 5,710,000 ns. First byte: 0.007095 minus 0.005957 is 0.001138 s, 1,138,000 ns. Transfer: 0.008013 minus 0.007095 is 0.000918 s, 918,000 ns. Total: 8,013,000 ns.
- `phases.dominant()` is the phase that took longest: `Phase.TLS`, with 5.71 of 8.013 ms.
- `sample.kind()` is `ConnectionKind.NEW` because `connects=1`. `sample.protocol()` is `"HTTP/1.1"`: the parser turns `http=1.1` into that name. `sample.serverTiming()` keeps the header value `app;dur=0.26`.
- Why it matters: if the parser mixed up cumulative and per-phase numbers, the TLS phase would show 5.957 ms and the table would claim a wrong dominant phase.

### A reused connection: zero readings mean a skipped step

```java
  @Test
  void readsZeroHandshakeReadingsOfAReusedConnectionAsSkippedPhases() {
    CurlSample sample = parsed(REUSED_HTTPS);

    assertThat(sample.kind()).isEqualTo(ConnectionKind.REUSED);
    assertThat(sample.phases().connect()).isZero();
    assertThat(sample.phases().tls()).isZero();
    assertThat(sample.phases().firstByte()).isEqualTo(Duration.ofNanos(766_000));
  }
```

On a reused connection `curl` writes 0 for the connect and TLS readings, because those steps did not happen. The parser must not compute `0 - 0.000010` and get a negative phase. Instead the connect and TLS phases are zero (`isZero()`), and the first-byte phase is 0.000776 minus 0.000010, which is 766,000 ns. `sample.kind()` is `REUSED` because `connects=0`.

### The `Server-Timing` header with spaces

```java
  @Test
  void keepsAServerTimingHeaderWithSpaces() {
    String line =
        "example-com dns=0.002 connect=0.020 tls=0.045 ttfb=0.071 total=0.072 http=2 code=200"
            + " size=528 connects=1 server=cdn-cache; desc=HIT, edge; dur=1";

    assertThat(parsed(line).serverTiming()).isEqualTo("cdn-cache; desc=HIT, edge; dur=1");
    assertThat(parsed(line).protocol()).isEqualTo("HTTP/2");
  }
```

A `Server-Timing` header can contain spaces and commas, for example `cdn-cache; desc=HIT, edge; dur=1`. The script puts that header last on the line (`bash-curl-timings.md`), and the parser takes everything after ` server=` as its value. The test builds a line from a public-site shape (`example-com`, `http=2`) and checks that the whole header survives, and that `http=2` becomes `HTTP/2`. `parsed(line)` is called twice, which is fine: it has no side effects.

### Out-of-order readings are rejected

```java
  @Test
  void rejectsReadingsThatAreOutOfOrder() {
    // The first byte cannot come before the TLS handshake ended: a phase would be negative.
    String line = NEW_HTTPS.replace("ttfb=0.007095", "ttfb=0.001000");

    assertThat(problem(line)).isInstanceOf(CurlProblem.Rejected.class);
    assertThat(problem(line).describe()).contains("a phase is negative");
  }
```

The comment says why: "The first byte cannot come before the TLS handshake ended: a phase would be negative." `NEW_HTTPS.replace("ttfb=0.007095", "ttfb=0.001000")` makes a copy of the line with the first-byte reading earlier than the TLS reading. The parser must refuse it. The test checks two things: the problem is a `CurlProblem.Rejected` (not "malformed"), and its description contains `a phase is negative`. This protects the invariant: a negative phase can never reach the table.

### A failed or unsuccessful request is rejected

```java
  @Test
  void rejectsAFailedOrUnsuccessfulRequest() {
    assertThat(problem(NEW_HTTPS.replace("code=200", "code=404")).describe())
        .contains("status 404 is not 2xx");
    assertThat(problem(NEW_HTTPS.replace("http=1.1", "http=0")).describe())
        .contains("the request failed");
    assertThat(problem(NEW_HTTPS.replace("size=36894", "size=-1")).describe())
        .contains("the size is negative");
  }
```

- `code=404` means the server answered with an error. The timing of an error page is not the timing of the seat map, so the parser says `status 404 is not 2xx`. A 2xx status is any 200 to 299.
- `http=0` is what `curl` prints when the request failed: no HTTP version. The description contains `the request failed`.
- `size=-1` cannot be a size. The description contains `the size is negative`.

### Malformed lines are refused

```java
  @Test
  void refusesMalformedLines() {
    assertThat(problem(NEW_HTTPS.replace(" server=app;dur=0.26", "")).describe())
        .contains("no server= field");
    assertThat(problem(NEW_HTTPS.replace("code=200", "code200")).describe())
        .contains("invalid or repeated field code200");
    assertThat(problem(NEW_HTTPS.replace("seatwave-https", "SeatWave")).describe())
        .contains("invalid target name");
    assertThat(problem(NEW_HTTPS.replace(" connects=1", "")).describe())
        .contains("no connects= field");
    assertThat(problem(NEW_HTTPS.replace("total=0.008013", "total=fast")).describe())
        .contains("a number is invalid");
    assertThat(problem(NEW_HTTPS.replace("dns=0.000080", "dns=0.000080 dns=1")).describe())
        .contains("invalid or repeated field dns=1");
  }
```

Each assertion damages the line in a different way, and each must produce its own clear message:

- the `server=` field removed: `no server= field`;
- `code=200` changed to `code200` (no equals sign): `invalid or repeated field code200`;
- the target name `SeatWave` (capital letters are not allowed): `invalid target name`;
- ` connects=1` removed: `no connects= field`;
- `total=fast`, text instead of a number: `a number is invalid`;
- `dns=0.000080 dns=1`, the same key twice: `invalid or repeated field dns=1`.

The point: a bad line is a stated problem, never an exception that stops the whole report and never a silently wrong number.

### A long malformed line is cut in the report

```java
  @Test
  void cutsALongMalformedLineInTheReport() {
    String longLine = "x".repeat(200);

    assertThat(problem(longLine).describe()).hasSizeLessThan(140);
  }
```

A line of 200 letters `x` is malformed. The description of the problem includes a shortened copy of the line, so one broken line cannot flood the report. `hasSizeLessThan(140)` checks that the description is shorter than 140 characters. `"x".repeat(200)` builds the text.

### The two helpers

```java
  static CurlSample parsed(String line) {
    return switch (CurlLine.parse(line)) {
      case Result.Success<CurlSample, CurlProblem>(CurlSample sample) -> sample;
      case Result.Error<CurlSample, CurlProblem>(CurlProblem problem) ->
          throw new AssertionError(problem.describe());
    };
  }

  private static CurlProblem problem(String line) {
    return switch (CurlLine.parse(line)) {
      case Result.Success<CurlSample, CurlProblem>(CurlSample sample) ->
          throw new AssertionError("parsed, but should not: " + sample);
      case Result.Error<CurlSample, CurlProblem>(CurlProblem problem) -> problem;
    };
  }
}
```

- `parsed` calls `CurlLine.parse(line)` and looks at the result with a `switch` over the two cases. `case Result.Success<CurlSample, CurlProblem>(CurlSample sample) -> sample;` is a **record pattern**: it matches a success and takes its value out as `sample`. In the error case it throws `AssertionError(problem.describe())`, which fails the test with the description of the problem, so a bad test line says why it is bad.
- `problem` is the mirror: it returns the problem, and fails the test with `parsed, but should not` if the line was accepted.
- `parsed` is not `private` (it is `static` only) because `TimingReportTest` uses it.

### `PhaseTimingsTest`: the invariant

```java
package io.plainround.seatwave.network.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PhaseTimingsTest {

  private static final Duration TEN = Duration.ofMillis(10);
  private static final Duration ZERO = Duration.ZERO;

  @Test
  void acceptsPhasesThatSumToTheTotal() {
    PhaseTimings timings = new PhaseTimings(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(50));

    assertThat(timings.of(Phase.TRANSFER)).isEqualTo(TEN);
  }
```

`PhaseTimings` is a record of five phases (DNS, connect, TLS, first byte, transfer) and a total. The test class has two constants: `TEN` is a `Duration` of 10 ms and `ZERO` is no time. In the first test, five phases of 10 ms each and a total of 50 ms are accepted by the constructor, and `timings.of(Phase.TRANSFER)` returns the transfer phase, 10 ms.

```java
  @Test
  void refusesPhasesThatDoNotSumToTheTotal() {
    Result<PhaseTimings, String> refused =
        PhaseTimings.of(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(52));

    assertThat(((Result.Error<PhaseTimings, String>) refused).error())
        .isEqualTo("the phases sum to 50000000 ns, but the total is 52000000 ns");
    assertThatThrownBy(() -> new PhaseTimings(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(52)))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- Five phases of 10 ms sum to 50 ms, but the total says 52 ms. `PhaseTimings.of(...)` is the checking factory: it returns `Result.Error` with the text `the phases sum to 50000000 ns, but the total is 52000000 ns` (nanoseconds, so there is no rounding).
- The constructor, called with the same numbers, throws `IllegalArgumentException`. So there are two doors: the factory returns an error value for data that comes from outside, and the constructor refuses to build an impossible object at all. The invariant is enforced inside the object, so no code can create a `PhaseTimings` that breaks it.

```java
  @Test
  void allowsAGapUpToTheTolerance() {
    Duration total = Duration.ofMillis(50).plus(PhaseTimings.SUM_TOLERANCE);

    assertThat(PhaseTimings.of(TEN, TEN, TEN, TEN, TEN, total)).isInstanceOf(Result.Success.class);
  }
```

`PhaseTimings.SUM_TOLERANCE` is the gap that is allowed between the sum and the total: 1 ms (`ARCHITECTURE.md`). The test uses a total exactly 1 ms over the sum, and the factory still returns `Result.Success`. This is the boundary: a gap equal to the tolerance is accepted. `curl` rounds its readings to microseconds, so the sum of the differences can differ slightly from the total.

```java
  @Test
  void refusesANegativePhaseEvenWhenTheSumIsRight() {
    Duration minusTen = TEN.negated();

    // 10 - 10 + 10 + 10 + 0 = 20, so the total is right and only the negative phase is wrong.
    assertThat(PhaseTimings.of(TEN, minusTen, TEN, TEN, ZERO, TEN.plus(TEN)))
        .isInstanceOf(Result.Error.class);
  }
```

`TEN.negated()` is minus 10 ms. The call gives the phases 10, minus 10, 10, 10 and 0 ms and a total of 20 ms (`TEN.plus(TEN)`), and the test expects `Result.Error`. The phases add up to 20 ms, so the sum rule is satisfied, as the comment in the test says. The only thing wrong with this input is the negative phase, so the test proves that a negative phase is refused by its own rule.

```java
  @Test
  void namesTheLongestPhaseAndKeepsTheEarlierOneOnATie() {
    PhaseTimings tie = new PhaseTimings(ZERO, TEN, TEN, ZERO, ZERO, Duration.ofMillis(20));
    PhaseTimings allZero = new PhaseTimings(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO);

    assertThat(tie.dominant()).isEqualTo(Phase.CONNECT);
    assertThat(allZero.dominant()).isEqualTo(Phase.DNS);
    assertThat(Phase.FIRST_BYTE.label()).isEqualTo("first byte");
  }
```

`dominant()` returns the longest phase. When two phases tie (here connect and TLS at 10 ms each) it keeps the earlier one in the order of the phases, `CONNECT`. When every phase is zero it returns the first phase, `DNS`. Making the tie rule explicit means the sentence "tls dominates" in the report never flips between two runs of the same data. The last line checks the printable name `"first byte"` of `Phase.FIRST_BYTE`.

### `TimingReportTest`: the median and the tables

```java
package io.plainround.seatwave.network.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimingReportTest {

  @Test
  void reportsTheMedianRunWhosePhasesStillSumToItsTotal() {
    List<CurlSample> runs =
        List.of(run("0.008013"), run("0.040919"), run("0.006390"), run("0.007500"));

    TargetTiming row = new TargetTiming("seatwave-https", ConnectionKind.NEW, runs);

    // Four runs: the lower of the two middle ones, a run that was really measured.
    assertThat(row.medianRun().phases().total()).isEqualTo(Duration.ofNanos(7_500_000));
    assertThat(row.fastest()).isEqualTo(Duration.ofNanos(6_390_000));
    assertThat(row.slowest()).isEqualTo(Duration.ofNanos(40_919_000));
  }
```

- `List.of(...)` builds a fixed list of four samples. `run("0.008013")` is a private helper at the end of the file that makes a sample from `NEW_HTTPS` with a different total (and a first-byte reading of 0.006 so that every phase stays at or above zero). The four totals are 8.013, 40.919, 6.390 and 7.500 ms.
- `new TargetTiming("seatwave-https", ConnectionKind.NEW, runs)` makes one row of the table. The median of four values is a choice: the comment says "Four runs: the lower of the two middle ones, a run that was really measured." Sorted, the totals are 6.390, 7.500, 8.013, 40.919; the lower middle is 7.500 ms. The row reports that run's own phases, so a row is one real request and its phases add up. The assertion checks `row.medianRun().phases().total()` is 7,500,000 ns, `fastest()` is 6,390,000 ns and `slowest()` is 40,919,000 ns.
- The odd 40.919 ms run, the kind of cold-start outlier that real timings contain, does not move the median at all. That is why the report uses the median.

```java
  @Test
  void refusesARowWithTooFewRunsOrARunOfAnotherTarget() {
    assertThatThrownBy(
            () -> new TargetTiming("seatwave-https", ConnectionKind.NEW, List.of(run("0.008013"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a timing needs at least 3 runs, but had 1");
    List<CurlSample> mixed = List.of(run("0.008013"), run("0.008013"), run("0.008013"));
    assertThatThrownBy(() -> new TargetTiming("seatwave-https", ConnectionKind.REUSED, mixed))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

- One run is too few: `new TargetTiming(..., List.of(run("0.008013")))` throws `IllegalArgumentException` with the message `a timing needs at least 3 runs, but had 1`. `hasMessage` checks the whole message.
- `mixed` is three runs of a `NEW` connection, but the row is declared `REUSED`. The constructor refuses a run that does not belong to the row. This protects the invariant "every row is the median of at least 3 runs of one target and one kind of connection".
- The lambda `() -> new TargetTiming(...)` is needed because `assertThatThrownBy` must run the code itself to catch the exception (`assertj.md`).

```java
  @Test
  void printsEveryRowWithItsSentenceAndListsRefusedGroupsAndLines() {
    List<CurlSample> samples =
        List.of(
            run("0.008013"),
            run("0.006390"),
            run("0.007500"),
            CurlLineTest.parsed(CurlLineTest.REUSED_HTTPS));
    List<CurlProblem> problems = List.of(new CurlProblem.Rejected("example-com", "status 404"));

    String table = TimingReport.renderPhases(samples, problems);

    assertThat(table)
        .contains("seatwave-https    new        HTTP/1.1     3")
        .contains("seatwave-https, new connection: tls dominates the median run")
        .contains("refused: too few runs for seatwave-https over a reused connection: 1")
        .contains("refused: rejected run of example-com: status 404");
  }
```

- This test arranges three `NEW` runs and one `REUSED` run (`CurlLineTest.REUSED_HTTPS`, a line from the other test class), plus one problem: a rejected run of `example-com` with the reason `status 404`.
- `TimingReport.renderPhases(samples, problems)` returns the whole table as a text. The test does not compare the whole text; it checks four pieces with `contains` (the table has decimals that depend on the arithmetic of the median, and the pieces are the facts that matter).
- The row `seatwave-https    new        HTTP/1.1     3` has the target, the kind of connection, the protocol and the number of runs: 3. Each column has a fixed width, so the spaces are part of the check.
- The sentence `seatwave-https, new connection: tls dominates the median run` is the one-line reading under the table.
- The reused group has only one run, so it is not a row. It is listed as `refused: too few runs for seatwave-https over a reused connection: 1`. A small group is never printed as a row.
- The problem line `refused: rejected run of example-com: status 404` proves a rejected line is shown to the reader, not dropped.

```java
  @Test
  void printsTheKeepAliveTable() {
    ReuseSample sample = new ReuseSample(Duration.ofMillis(240), Duration.ofMillis(80), "HTTP/1.1");
    ReuseTiming row = new ReuseTiming("distant-tls", "HTTP_2", List.of(sample, sample, sample), 3);

    assertThat(TimingReport.renderReuse(List.of(row)))
        .contains("distant-tls    HTTP_2     HTTP/1.1      3           240.00          80.00");
    assertThat(row.medianNew().minus(row.medianReused())).isEqualTo(Duration.ofMillis(160));
  }
```

The Java timing client (`TimingCli`) reports the keep-alive table. A `ReuseSample` is one run: the time of the first request on a new connection (240 ms), of the second on the same connection (80 ms) and the protocol that answered. Three identical samples make a `ReuseTiming` for the scenario `distant-tls` that asked for `HTTP_2`, with 3 connections opened. The rendered row has the medians: 240.00 and 80.00 ms. The last line checks that the saving by reuse, `medianNew().minus(medianReused())`, is 160 ms: the three round trips of a handshake that the second request did not pay (the scenario of the README has an 80 ms round trip).

```java
  @Test
  void refusesAKeepAliveRowThatMixesProtocolsOrCountsBelowZero() {
    ReuseSample oneOne = new ReuseSample(Duration.ofMillis(2), Duration.ofMillis(1), "HTTP/1.1");
    ReuseSample two = new ReuseSample(Duration.ofMillis(2), Duration.ofMillis(1), "HTTP/2");

    assertThatThrownBy(() -> new ReuseTiming("x", "HTTP_2", List.of(oneOne, oneOne, two), 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReuseTiming("x", "HTTP_2", List.of(oneOne, oneOne, oneOne), -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReuseSample(Duration.ofMillis(-1), Duration.ZERO, "HTTP/1.1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReuseSample(Duration.ZERO, Duration.ZERO, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

Four refusals, each by an exception from a constructor:

- `ReuseTiming` with samples that answered with different protocols (`HTTP/1.1`, `HTTP/1.1` and `HTTP/2`): a row that mixes protocols would be a wrong claim.
- A connection count of `-1`: a count cannot be below zero.
- A `ReuseSample` with a negative duration.
- A `ReuseSample` whose protocol is a blank text (`" "`).

```java
  @Test
  void refusesASampleThatIsNotA2xxTiming() {
    PhaseTimings phases = CurlLineTest.parsed(CurlLineTest.NEW_HTTPS).phases();

    assertThatThrownBy(
            () -> new CurlSample("x", "HTTP/1.1", ConnectionKind.NEW, 500, 1, phases, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new CurlSample("Bad Name", "HTTP/1.1", ConnectionKind.NEW, 200, 1, phases, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new CurlSample("x", "HTTP/1.1", ConnectionKind.NEW, 200, -1, phases, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }
```

`CurlSample` is the checked version of one `curl` line. It refuses a status of 500 (not 2xx), a name with a space and a capital (`Bad Name`), and a connection count of `-1`. `phases` is taken from `CurlLineTest.parsed(CurlLineTest.NEW_HTTPS).phases()`: a real, valid set of phases, so only the field under test is wrong. The lambdas defer each `new` so that `assertThatThrownBy` can catch the exception.

```java
  private static CurlSample run(String totalSeconds) {
    return CurlLineTest.parsed(
        CurlLineTest.NEW_HTTPS
            .replace("total=0.008013", "total=" + totalSeconds)
            .replace("ttfb=0.007095", "ttfb=0.006000"));
  }
}
```

The private helper `run(totalSeconds)` takes `NEW_HTTPS` and replaces its total, and also replaces `ttfb=0.007095` with `ttfb=0.006000`. The replacement matters: the original first-byte reading of 7.095 ms is later than some of the new totals (for example 6.390 ms), which would make the transfer phase negative and the parser would reject the line. With the first byte at 6.000 ms every total from 6.390 up leaves a transfer phase of zero or more.

## Try it yourself

All ran in a scratch copy on 10 November 2026.

1. Run the three classes:

```
./gradlew --offline cleanTest test --tests 'io.plainround.seatwave.network.report.*'
```

   (A filter of `'*report*'` would also run `SettingsReaderTest.reportsEveryProblemInOneRun`, because the filter matches method names too.) Expected: 18 lines ending in `PASSED` (7 `CurlLineTest`, 5 `PhaseTimingsTest`, 6 `TimingReportTest`) and `BUILD SUCCESSFUL`.

2. Break the parser on purpose, in a scratch copy only. In `CurlLineTest` change `Duration.ofNanos(167_000)` to `Duration.ofNanos(168_000)` and run the test again:

```
./gradlew --offline test --tests '*CurlLineTest'
```

   Expected: `CurlLineTest > turnsCumulativeReadingsIntoFivePhases() FAILED` and `6 tests completed, 1 failed`. Undo the edit.

3. Count the tests per class:

```
grep -c "@Test" src/test/java/io/plainround/seatwave/network/report/*.java
```

   Expected: `CurlLineTest.java:7`, `PhaseTimingsTest.java:5`, `TimingReportTest.java:6`.

## Mistakes people make

- **Testing with the real clock.** A test that measures real time is not repeatable. These tests feed fixed numbers.
- **Checking only the happy path.** Half of the tests here are about refusals, because a report that accepts bad input is wrong.
- **Comparing floating-point seconds.** The code keeps exact decimals (`BigDecimal`) and the tests compare exact `Duration` values in nanoseconds.
- **Copying expected values from the program's output.** The numbers here were worked out from the readings by subtraction, so the test checks the program instead of repeating it.
- **A helper that hides a failure.** `parsed` throws an `AssertionError` with the problem's description, so a bad test line explains itself.

## Check yourself

1. *Why is `NEW_HTTPS` a constant shared by two test classes?* One fixed, valid line is the base for many variations, each made with `replace`.
2. *What are the five phases of the first test, in nanoseconds?* 80,000; 167,000; 5,710,000; 1,138,000; 918,000.
3. *Which run does a four-run row report?* The lower of the two middle runs by total time, here 7.500 ms.
4. *What happens to a group with fewer than 3 runs?* It is listed as refused, never printed as a row.
5. *Why does `PhaseTimings` have both a constructor and a factory?* The constructor makes an impossible object impossible; the factory returns an error value for input from outside.

## Further reading

- JUnit 6.1.3 user guide: <https://docs.junit.org/6.1.3/overview.html>
- AssertJ documentation: <https://assertj.github.io/doc/>
- Java records and record patterns: <https://docs.oracle.com/en/java/javase/25/language/records.html>

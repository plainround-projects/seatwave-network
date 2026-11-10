# AssertJ 3.27.7

## What it is

An **assertion** is a check inside a test: "this value must be that value". AssertJ is a library of assertions with a **fluent** style: you write `assertThat(actual)` and then chain method calls that read like a sentence, each returning the object so the next call can follow. When a check fails, AssertJ prints the expected and the actual value in a clear message.

## Why this repository uses it

Tests are the requirements here, so they should read like requirements. `assertThat(response.statusCode()).isEqualTo(200)` is easier to read than a hand-written `if` with a `throw`. The README gives the reason: "Fluent assertions with clear failure messages." In a test of a network program the message matters, because a failure often shows a status code, a byte count or a refusal text, and that value is the clue.

## Where it appears

| Path | What it does with AssertJ |
| --- | --- |
| `gradle/libs.versions.toml` | `assertj = "3.27.7"` and `assertj-core`. |
| `build.gradle.kts` | `testImplementation(libs.assertj.core)`. |
| `gradle.lockfile` | Locks `assertj-core` and its dependency `byte-buddy` 1.18.3 (`dependency-locking.md`). |
| `src/test/java/**` | `assertThat` is imported in 11 files and `assertThatThrownBy` in 8 files. There are 141 `assertThat(` calls and 24 `assertThatThrownBy(` calls in all. |

## How it works underneath

1. `assertThat(x)` looks at the type of `x` and returns the matching **assertion object**: one for a `String`, one for a `List`, one for a `Duration`, one for a `boolean`, one for a `Path`, one for an `Optional`, one for any other object. Each has the methods that make sense for that type.
2. Each method (`isEqualTo`, `contains`, `hasSize`) checks the value. If the check holds, it returns the same assertion object, so another call can follow. If not, it throws an `AssertionError` with a message, and JUnit marks the test as failed (`junit.md`).
3. For exceptions, `assertThatThrownBy(() -> code)` runs the code in a try block, catches whatever is thrown and lets you check its type and message. The `() -> code` part is a **lambda**: a small unnamed function that is handed over and run later, inside AssertJ, so AssertJ can catch what it throws.
4. Because the checks are chained on one `assertThat`, several conditions on the same value come in one statement.
5. An assertion runs once, on the thread that calls it, at that moment. It does not wait. A test of a network program therefore waits in a different way: the call to the server (`client.send(...)`) blocks until the answer comes, and only then does the assertion look at it.

## Walkthrough of this repository's use

The numbers in this section come from `grep -rhoE` over `src/test` and count each method call.

### `isEqualTo` for numbers, text, durations and enums

```java
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

`isEqualTo` compares with `equals`. It is the most used method here (69 calls). For text it compares every character. For `Duration` values (`Duration.ofNanos(80_000)` is a span of 80,000 nanoseconds, 0.08 ms) it compares the length of the span. An enum value such as `Phase.TLS` or `ConnectionKind.NEW` is compared the same way. For numbers the compiler decides the type: `isEqualTo(200)` compares `int` values and `isEqualTo(1L)` would compare `long` values. Java **records** such as `PhaseTimings` get a correct `equals` automatically, so two records with the same contents are equal, and `TimingReportTest` can write `assertThat(row.medianNew().minus(row.medianReused())).isEqualTo(Duration.ofMillis(160));`.

### Text: `startsWith`, `endsWith`, `contains`, `matches`

```java
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

On text these check a part: `startsWith("...")` checks the beginning and `endsWith("...")` the end (3 and 1 calls). `contains("a", "b")` requires every listed piece to be somewhere in the text (21 calls, many on error messages). The chain `.startsWith(...).endsWith(...)` is one statement on one value. `hasSize(36_894)` on a byte array checks its length.

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

- `response.headers().firstValue("Server-Timing")` is an `Optional`: a box that holds a value or nothing. `hasValueSatisfying(value -> ...)` requires the box to hold a value and runs another assertion on it. Here the inner `matches("app;dur=\\d+\\.\\d{2}")` checks the text against a regular expression: `app;dur=` then one or more digits, a dot, and exactly two digits. In a Java string `\\d` is the two characters `\d`, which means "a digit".
- `hasValue("application/json")` requires the box to hold exactly that text. It is shorter than `isPresent()` plus `isEqualTo`.

### Lists, `containsExactly` and `extracting`

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

- `assertThat(settings.versions()).containsExactly(HttpClient.Version.HTTP_1_1, HttpClient.Version.HTTP_2)` checks that the list holds exactly these elements, in this order, and no others (10 calls).
- `extracting(Scenario::name)` first turns each element into one of its fields, then checks the resulting list. `Scenario::name` is a **method reference**, a short way to write "call `name()` on each element".
- `.discardError().orElseThrow()` is not AssertJ. It belongs to the result type (`result4j.md`): it turns "a settings value or a list of problems" into the settings value, or throws if there was a problem.

### Collections: `hasSize`, `isEmpty`, `allSatisfy`

```java
      List<ReuseSample> samples = probe.measure(uri, HttpClient.Version.HTTP_2, RUNS);

      // Two requests per run, but one connection per run: the second request reused it.
      assertThat(samples).hasSize(RUNS);
      assertThat(link.connectionsAccepted()).isEqualTo(RUNS);
      assertThat(samples).allSatisfy(sample -> assertThat(sample.protocol()).isEqualTo("HTTP/1.1"));
    }
```

`hasSize(RUNS)` checks the number of elements (5 calls). `isEmpty()` checks that a collection, a text or an `Optional` is empty (7 calls; for example `assertThat(response.body()).isEmpty()` for the body of a `HEAD` answer). `allSatisfy` runs a check on every element (1 call): every sample must say it was answered with `HTTP/1.1`.

### Numbers, booleans and comparisons

`isTrue()` and `isFalse()` check a `boolean` (3 and 6 calls). `isZero()` checks that a number or a `Duration` is zero (6 calls). `isPositive()` checks that a number is above zero (1 call, in `PageLoadModelTest`). `isGreaterThanOrEqualTo(x)` and `isGreaterThan(x)` compare two values of one type, including `Duration` values (3 and 1 calls):

```java
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
```

A real network has no exact timings, so the test claims only a **lower bound**: the request and the reply were each held 20 ms, so the elapsed time must be at least 40 ms. `isEqualTo(1)` on `link.connectionsAccepted()` then checks the exact count, which does not depend on time (`tests-other-packages-walkthrough.md`).

### `as`: a label for a failure message

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

`.as(protocol.label())` puts text in front of the failure message, so a failure in a loop says which protocol failed. Without it the message would not say which turn of the `for` loop broke.

### Exceptions: `assertThatThrownBy`

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

`assertThatThrownBy(() -> ...)` runs the lambda and expects it to throw. `.isInstanceOf(IllegalArgumentException.class)` checks the type of the exception (27 calls in all, some on values rather than exceptions). `.hasMessage("...")` checks the message exactly (1 call), and `hasMessageContaining("...")` checks that a part is there (2 calls). Most calls here check the type only: the value types all refuse bad input with the same exception.

### Results that are errors: a cast

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

The project's result type (`result4j.md`) is either `Result.Success` or `Result.Error`. The test casts the value to `Result.Error<PhaseTimings, String>` and calls `.error()` to get the text of the problem. The cast is safe here because the test has just arranged an input that must fail; if it succeeded instead, the cast would throw a `ClassCastException` and the test would error, which also shows a problem. `isInstanceOf(Result.Success.class)` and `isInstanceOf(Result.Error.class)` check the type without a cast (`allowsAGapUpToTheTolerance` and `refusesANegativePhaseEvenWhenTheSumIsRight`).

### Pattern and path checks

`containsPattern("simple +HTTP_1_1 +HTTP/1.1 +7 .* 7\\n")` checks that a regular expression matches somewhere in a text (2 calls, in `TimingSettingsTest`), which suits a table whose exact decimals change from run to run. `hasFileName("seatwave-test-ca.pem")` checks the last part of a `Path`. `hasSizeLessThan(140)` checks that a text is shorter than 140 characters.

## Try it yourself

1. Count the AssertJ entry points:

```
grep -rho "assertThat(\|assertThatThrownBy(" src/test | sort | uniq -c
```

   Expected: `141 assertThat(` and `24 assertThatThrownBy(`.

2. Count how often each check is used:

```
grep -rhoE "\.(isEqualTo|isInstanceOf|contains|containsExactly|hasSize|isEmpty|isZero)\(" src/test | sort | uniq -c | sort -rn
```

   Expected: `69 .isEqualTo(`, `27 .isInstanceOf(`, `21 .contains(`, `10 .containsExactly(`, `7 .isEmpty(`, `6 .isZero(` and `5 .hasSize(`.

3. Make a test fail on purpose, in a scratch copy only. In `PhaseTimingsTest` change `isEqualTo(TEN)` to `isEqualTo(ZERO)`, then run:

```
./gradlew --offline test --tests '*PhaseTimingsTest'
```

   Gradle prints `PhaseTimingsTest > acceptsPhasesThatSumToTheTotal() FAILED`, `org.opentest4j.AssertionFailedError at PhaseTimingsTest.java:19` and `5 tests completed, 1 failed`. The full message is in `build/test-results/test/TEST-io.plainround.seatwave.network.report.PhaseTimingsTest.xml`: `expected: 0S` and ` but was: 0.01S` (a `Duration` of ten milliseconds prints as `0.01S`). Undo the edit afterwards.

4. Find the tests that use `.as(`:

```
grep -rn "\.as(" src/test
```

   Expected: three calls, in `PageLoadModelTest` and `SeatWaveServerTest`.

## Mistakes people make

- **Forgetting the last call.** `assertThat(x);` with nothing after it checks nothing and the test passes. Every `assertThat` here ends with a method such as `isEqualTo`.
- **Comparing a `double` with `isEqualTo`.** Use `isCloseTo(x, within(tolerance))` for decimal results of calculations. The tests here compare only values that are exact.
- **Expecting an assertion to wait.** It looks at the value once. A call such as `client.send` waits; the assertion does not.
- **A time check with an upper bound.** A loaded machine makes any upper bound flaky. The timing tests here assert only a lower bound.
- **Casting a result without arranging the failure.** If the code succeeds, the cast throws a `ClassCastException`.
- **A lambda that does not throw.** `assertThatThrownBy` fails with "Expecting code to raise a throwable" if nothing is thrown.

## Check yourself

1. *What does `assertThat(x).isEqualTo(y)` do when the values differ?* It throws an `AssertionError` with both values, and JUnit marks the test as failed.
2. *Why does AssertJ take a lambda for exceptions?* It has to run the code itself so that it can catch what is thrown.
3. *What does `containsExactly` check?* That a list holds exactly the given elements, in that order, and nothing else.
4. *What does `.as("text")` do?* It puts the text in front of the failure message.
5. *Why do the `DelayLink` tests use `isGreaterThanOrEqualTo`?* The delay is a lower bound; an upper bound would fail on a busy machine.

## Further reading

- AssertJ core 3.27.7 documentation: <https://assertj.github.io/doc/>
- `Assertions` API (3.27.7): <https://www.javadoc.io/doc/org.assertj/assertj-core/3.27.7/org/assertj/core/api/Assertions.html>
- `assertThatThrownBy`: <https://assertj.github.io/doc/#assertj-core-exception-assertions>

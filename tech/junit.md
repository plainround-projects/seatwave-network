# JUnit Jupiter 6.1.3

## What it is

JUnit is the standard Java library for writing **automated tests**. A test is an ordinary method, marked with the annotation `@Test`, that runs some code and checks the result. If a check fails, JUnit reports the failing test by name and the build stops. **JUnit Jupiter** is the part of JUnit you write tests with. The **JUnit Platform** is the part that finds and runs them. This repository uses version **6.1.3** of both. JUnit 6 needs Java 17 or newer and keeps the Jupiter style of version 5.

An **annotation** is a label written with `@` in front of a method, class or parameter, such as `@Test`. It does nothing by itself. Tools read it.

## Why this repository uses it

The project's tests double as its requirements: every test method has a long name that states one behavior in plain words, and all 62 run with `./gradlew check`. JUnit gives the structure. AssertJ (`assertj.md`) gives the readable checks. This module is about the network, so many tests start a real server on the loopback address and talk to it; JUnit only has to start the test and notice whether it returned or threw.

## Where it appears

| Path | What it does with JUnit |
| --- | --- |
| `gradle/libs.versions.toml` | `junit = "6.1.3"` and the entries `junit-bom`, `junit-jupiter` and `junit-platform-launcher`. |
| `build.gradle.kts` | `platform(libs.junit.bom)`, `libs.junit.jupiter` and `libs.junit.platform.launcher`, and `useJUnitPlatform()`. |
| `gradle.lockfile` | Locks the JUnit libraries at 6.1.3 (`dependency-locking.md`). |
| `src/test/java/**` | 11 test classes with 62 `@Test` methods, and 1 helper class that holds no tests (`TestTls`). |

## How it works underneath

1. `./gradlew test` starts a new JVM and hands it the test classes, with the JUnit Platform launcher (`junit-platform-launcher`) as the entry point.
2. The Platform asks the **Jupiter engine** (`junit-jupiter-engine`) to **discover** tests: it scans the classes under `build/classes/java/test` for methods marked `@Test`.
3. For **each** test method Jupiter creates a **new instance** of the test class, runs the method, and throws the instance away. State cannot leak from one test to the next through an ordinary field.
4. A test **passes** if the method returns normally. It **fails** if it throws an `AssertionError`, which is what a wrong check throws. It is an **error** if it throws anything else.
5. Results go to `build/test-results/test/*.xml` (one file per class, 11 here), and Gradle prints one line per result because of `testLogging { events("passed", "failed", "skipped") }`.
6. The classes and methods are **package-private** (no `public`). JUnit 5 and 6 allow that, which keeps tests out of the public surface. The helper `TestTls` is the exception on purpose: it is `public` because tests in the `server`, `timing` and `tls` packages all use it.

JUnit does not promise an order between tests, and no test here depends on one. By default JUnit runs the tests one after the other on one thread, and Gradle uses one test JVM, so two tests never overlap in time. That matters here: the `DelayLink` tests measure elapsed time against a lower bound, and a second test competing for the machine would only slow them, never speed them up (`tests-other-packages-walkthrough.md`).

## Walkthrough of this repository's use

### `@Test`: one behavior, one sentence

```java
  @Test
  void acceptsPhasesThatSumToTheTotal() {
    PhaseTimings timings = new PhaseTimings(TEN, TEN, TEN, TEN, TEN, Duration.ofMillis(50));

    assertThat(timings.of(Phase.TRANSFER)).isEqualTo(TEN);
  }
```

`@Test` marks the method as a test. The name is a sentence about behavior, so a failure reads like a broken requirement: "accepts phases that sum to the total". The body has three steps that most tests here follow: **arrange** (build the input), **act** (call the code), **assert** (check the outcome). In this test the first statement arranges and acts (`new PhaseTimings(...)`) and the second asserts.

Most tests that talk to a server declare `throws Exception`. That is allowed: JUnit calls the method and treats any exception it throws as an error. It saves the test from catching checked exceptions such as `IOException` or `InterruptedException` that would be noise in a test.

### `@TempDir`: a folder for one test

```java
  @Test
  void loadsAFileAsUtf8(@TempDir Path folder) throws Exception {
    Path file = folder.resolve("settings.properties");
    Files.writeString(file, "# a comment\nname=Arena Night\n", StandardCharsets.UTF_8);

    Properties loaded = SettingsReader.load(file);

    assertThat(new SettingsReader(loaded).text("name")).isEqualTo("Arena Night");
  }
```

`@TempDir` on a method parameter asks JUnit to create a new empty temporary folder, pass it in as `folder`, and delete it, with everything inside, after the test. `SettingsReaderTest` uses it to write a small properties file and read it back, and `TlsFailuresTest` uses it twice to write a bad certificate file and an empty password file. I searched the test sources and found `@Test` 62 times and `@TempDir` 3 times.

### `@BeforeAll` and `@AfterAll`: one server for a whole class

```java
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

`@BeforeAll` marks a method that runs once before the first test of the class. `@AfterAll` marks one that runs once after the last. Both must be `static`, because no test instance exists yet when they run (JUnit creates a new instance for each test, as step 3 says). `SeatWaveServerTest` starts one plain HTTP server on a free port (`startPlain(0)`: port 0 asks the operating system for any free port) and one `HttpClient` before the tests, and closes both after. The fields are `static` for the same reason. This is the only class in the repository that uses these two annotations. The other test classes open what they need inside the test, in a `try` block that closes it automatically (`tests-other-packages-walkthrough.md`).

### What this repository does not use

There is no `@ParameterizedTest`, `@ValueSource`, `@EnumSource`, `@BeforeEach`, `@AfterEach`, `@Nested`, `@DisplayName`, `@Timeout` or `@ExtendWith`. Where several inputs need one check, a test lists the checks one after another or uses an ordinary `for` loop (`SeatWaveServerTest.refusesABadSectionBeforeAnyWork` loops over five bad queries). Each test builds what it needs with ordinary helper methods (the private `reader(...)`, `settings(...)`, `parsed(...)` and `run(...)`; see the walkthrough files). That keeps each test readable from top to bottom. A helper class holds no `@Test`, so JUnit ignores it.

There is also no `@Timeout`. A test that talks to a server never relies on JUnit to cut it off. Each wait carries its own bound instead: an `HttpClient` connect timeout and request timeout of 5 seconds, or a socket timeout of 5,000 milliseconds (`DelayLinkTest.connect`). A bound inside the call gives a clear error, where an outside timeout would only say that the test took too long.

### Static imports and the assertion style

All tests start with lines such as `import static org.assertj.core.api.Assertions.assertThat;`. A **static import** lets you write `assertThat(...)` instead of `Assertions.assertThat(...)`. The checks come from AssertJ, not from JUnit's own `Assertions` class (`assertj.md`).

### Where tests run from

Gradle runs the tests with the project folder as the working directory. That is why `PageLoadModelTest` and `TimingSettingsTest` can read `config/round-trips.properties` by that relative path. The tests that need a certificate read the folder from the system property `seatwave.tls.directory`, which only the Gradle `test` task sets (`gradle.md`).

### The build wiring

`testImplementation(libs.junit.jupiter)` makes the annotations and the engine available. `testRuntimeOnly(libs.junit.platform.launcher)` adds the launcher. `useJUnitPlatform()` tells the `test` task to use the Platform. The BOM `platform(libs.junit.bom)` fixes every JUnit library at 6.1.3 (`gradle.md`).

## Try it yourself

All ran in a scratch copy on 10 November 2026.

1. Run all tests and count the results:

```
./gradlew --offline cleanTest test | grep -c PASSED
```

   Expected: `62`. (`cleanTest` forces the tests to run again instead of being skipped as up to date.)

2. Run one class:

```
./gradlew --offline cleanTest test --tests '*PhaseTimingsTest'
```

   Expected: five lines ending in `PASSED`, then `BUILD SUCCESSFUL in 1s` and `6 actionable tasks: 3 executed, 3 up-to-date`.

3. Run one method:

```
./gradlew --offline cleanTest test --tests '*DelayLinkTest.closesAConnectionOverTheLimitAtOnce'
```

   Expected: one `PASSED` line, `DelayLinkTest > closesAConnectionOverTheLimitAtOnce() PASSED`.

4. Count the annotations at the start of a line:

```
grep -rhoE "^ *@[A-Za-z]+" src/test | sed 's/^ *//' | sort | uniq -c
```

   Expected: `1 @AfterAll`, `1 @BeforeAll` and `62 @Test`. (`@TempDir` sits inside a method signature, so this pattern does not show it; `grep -rn "@TempDir" src/test` finds the three uses.)

5. Read the totals from the result files:

```
grep -o 'tests="[0-9]*"' build/test-results/test/*.xml
```

   Each of the 11 files has a `tests="..."` count after a full run: 5 (`SettingsReaderTest`), 5 (`DelayLinkTest`), 8 (`PageLoadModelTest`), 7 (`CurlLineTest`), 5 (`PhaseTimingsTest`), 6 (`TimingReportTest`), 3 (`SeatMapJsonTest`), 9 (`SeatWaveServerTest`), 3 (`ReuseProbeTest`), 3 (`TimingSettingsTest`) and 8 (`TlsFailuresTest`). They add up to 62.

## Mistakes people make

- **Vague names** such as `test1`. A good name is a sentence about behavior, as here.
- **Tests that depend on each other's order.** JUnit gives no order promise. Each test builds its own input and starts its own server where it needs one, on a port the operating system picks.
- **Asserting nothing.** A test that only calls code passes even if the code is wrong.
- **Forgetting the launcher with Gradle 9.** Without `junit-platform-launcher` on the test runtime classpath the tests can not start. The build declares it.
- **A filter that matches too much.** `--tests '*report*'` also matches the package name `report`, so it runs every test in that package. Use the full class name when you want one class.
- **Running a TLS test outside Gradle.** `System.getProperty("seatwave.tls.directory")` is `null` unless the Gradle `test` task sets it.
- **Starting a server and never stopping it.** A server left running after a test holds its port. The tests close every server and link in a `try` block that closes it automatically.

## Check yourself

1. *What does `@Test` do?* Marks a method that JUnit runs as a test.
2. *Why does JUnit create a new instance of the class for every test?* So no test can see state left by another.
3. *What does `@TempDir` do?* JUnit makes a new empty folder for the test, passes it as the parameter, and deletes it afterwards.
4. *Why must a `@BeforeAll` method be `static`?* It runs before any instance of the test class exists.
5. *How do 62 `@Test` methods give 62 results?* Each method runs once; this repository has no parameterized tests.

## Further reading

- JUnit 6 user guide (6.1.3): <https://docs.junit.org/6.1.3/overview.html>
- Writing tests and annotations: <https://docs.junit.org/6.1.3/writing-tests/annotations.html>
- Running JUnit on Gradle: <https://docs.gradle.org/9.9.1/userguide/java_testing.html>

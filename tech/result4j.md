# result4j 1.2.0 (a library for results that are values)

## What it is

result4j is a small Java library. Its main type is `Result<R, E>`. A **result type** is an object that stands for "either this worked and here is the value, or it failed and here is the reason". `R` is the type of the good value and `E` the type of the reason. A `Result` is exactly one of two things: a **success** that holds an `R`, or an **error** that holds an `E`.

The Java standard library has no such type. `Optional` says "a value or nothing" but cannot say why there is nothing. An exception says why, but it jumps out of the normal flow of the program. A `Result` fills the gap: the failure is an ordinary value that a method returns, and the caller must look at it.

The library is `com.github.sviperll:result4j`, version 1.2.0, licensed Apache-2.0 (its POM says so).

## Why this repository uses it

A settings file or a line of `curl` output is typed or produced by something outside the program. A person can write `rtt.millis=abc`. A failed curl writes `http=` with nothing after it. These are **expected failures**: the program should list them, count them and print them, not die with a stack trace. One run must report every problem, not stop at the first.

Exceptions are kept for programmer errors: a null argument, a record built with a value its constructor forbids. The line is: if a person or a failed tool can cause it, it is a `Result`; if only a bug can cause it, it is an exception.

The dependency is declared in `gradle/libs.versions.toml`, used in `build.gradle.kts` and pinned in `gradle.lockfile`:

```text
result4j = "1.2.0"
result4j = { module = "com.github.sviperll:result4j", version.ref = "result4j" }
implementation(libs.result4j)
com.github.sviperll:result4j:1.2.0=compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath
```

`implementation` means the library is needed to compile and to run the main code. The lock file shows it on all four class paths. The README lists the reason the JDK alone is not enough: the JDK has no success-or-typed-error type.

## Where it appears

| File | What it does with `Result` |
|---|---|
| `config/SettingsReader.java` | Nothing: it does **not** use `Result`. It collects problem lines in a list that callers turn into a `Result`. |
| `server/ServeCli.java` | `read` returns `Result<Settings, List<String>>` (`Result.error`, `Result.success`); `main` switches over it. |
| `timing/TimingSettings.java` | `from` returns `Result<TimingSettings, List<String>>`. |
| `timing/TimingCli.java` | `main` switches over the result of `TimingSettings.from`. |
| `tls/TlsSettings.java` | `from` returns `Result<TlsSettings, List<String>>`. |
| `tls/TlsFailuresCli.java` | `main` switches over the result of `TlsSettings.from`. |
| `model/ModelSettings.java` | `from` returns `Result<ModelSettings, List<String>>`. |
| `model/PageModelCli.java` | `main` switches over the result of `ModelSettings.from`. |
| `report/PhaseTimings.java` | `of` returns `Result<PhaseTimings, String>`. |
| `report/CurlLine.java` | `parse` returns `Result<CurlSample, CurlProblem>`; `sample` and `malformed` build the results and examine one. |
| `report/ReportCli.java` | `main` switches over the result of `CurlLine.parse`. |

Ten files import the library. No other class does. (`SettingsReader`, `Runs`, `TimingReport` and the others do not.)

## How it works underneath

`Result<R, E>` is a **sealed interface** with two permitted types, both **records**. I ran `javap` on the 1.2.0 jar and confirmed it: `Result$Success` and `Result$Error` are `final` classes that extend `java.lang.Record`, and `Result` has a `PermittedSubclasses` entry that lists exactly those two. A **sealed** interface lists every type allowed to implement it. That is what lets the compiler check a `switch`: with two possible cases, a `switch` that handles both needs no `default`.

- `Result.Success<R, E>` is a record with one component, `result`. Its `toString` prints `Success[result=7]`.
- `Result.Error<R, E>` is a record with one component, `error`. Its `toString` prints `Error[error=[q must be a whole number]]`.

Both have the same two type parameters. A `Success` never uses `E`, and an `Error` never uses `R`. That is why a pattern is written `Result.Success<TlsSettings, List<String>>(TlsSettings settings)`: both type parameters are spelled out.

Everything is in memory. A `Result` is an ordinary object on the heap. There is no thread, no network and no file. Making one costs one small object.

**The calls this repository uses:**

- `Result.success(value)` makes a `Success`.
- `Result.error(reason)` makes an `Error`.
- A `switch` over the `Result` with the record patterns `Result.Success<...>(value)` and `Result.Error<...>(error)`.

The library offers more. I listed its public methods with `javap -public` on the jar: `isError`, `map`, `flatMap`, `andThen`, `mapError`, `discardError`, `recoverError`, `throwError`, `orOnErrorThrow`, `ifSuccess`, `peekError`, `peekSuccess`, `fromOptional`, `fromOptionalResult`, and the function builders `mapping`, `flatMapping` and `errorMapping`. This repository uses none of them.

I ran a small program with the 1.2.0 jar on the class path. It parses `42` and `x` and returns a `Result<Integer, List<String>>`:

```text
got 42
problems [x must be a whole number]
Success[result=7] | Error[error=[q must be a whole number]]
true
Success[result=14]
true
```

The last three lines: `equals` compares by content (a parsed 7 equals `Result.success(7)`, because records compare by their components), `map` applies a function to a success (7 became 14), and `isError()` is true for the bad one.

**Java version.** The class files in the 1.2.0 jar have major version 65, which is Java 21 (`javap -v` printed `major version: 65`). So the library needs Java 21 or newer, and this repository's `switch` with record patterns also needs Java 21. The project builds with `--release 25`.

## Walkthrough of this repository's use

### Building results: `TlsSettings.from`

```java
  public static Result<TlsSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String directory = reader.text("tls.directory");
    List<String> server = reader.wordsIn("tls.server.protocols", KNOWN_VERSIONS);
    List<String> oldClient = reader.wordsIn("tls.old.client.protocols", KNOWN_VERSIONS);
    Duration timeout = reader.millisBetween("client.timeout.millis", 1, MAX_TIMEOUT_MILLIS);
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(new TlsSettings(Path.of(directory), server, oldClient, timeout));
  }
```

- The return type `Result<TlsSettings, List<String>>` says in one line what the method gives: the settings, or a list of problem texts. Both cases are values, so one call reports every bad key.
- Every key is read first. The reader collects a problem line for each bad one and returns a harmless stand-in, so the reads never stop early.
- `Result.error(reader.problems())` is returned **before** the record is built. That is what makes the stand-ins harmless: the record is never made from them.
- `Result.success(new TlsSettings(...))` returns the settings.

### Building results: `ModelSettings.from` and `TimingSettings.from`

```java
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

The same shape. `ModelSettings.from` reads thirteen keys and has the same early return of `Result.error`, then `Result.success`.

```java
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new TimingSettings(
            Path.of(directory),
            protocols,
            path,
            runs,
            warmups,
            new Timeouts(connect, request),
            versions,
            maxConnections,
            scenarios));
  }
```

- `TimingSettings.from` has the same shape after its loop over the scenarios.

### Building results: `ServeCli.read`

```java
  private static Result<Settings, List<String>> read(SettingsReader reader) {
    Settings settings =
        new Settings(
            reader.text("tls.directory"),
            reader.wordsIn("tls.server.protocols", TLS_VERSIONS),
            reader.intBetween("http.port", FIRST_USER_PORT, MAX_PORT),
            reader.intBetween("https.port", FIRST_USER_PORT, MAX_PORT),
            reader.intBetween("distant.port", FIRST_USER_PORT, MAX_PORT),
            reader.millisBetween("distant.one.way.delay.millis", 0, MAX_DELAY_MILLIS),
            reader.intBetween("max.connections", 1, MAX_CONNECTIONS),
            Duration.ofSeconds(reader.intBetween("serve.seconds", 1, MAX_SECONDS)));
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(settings);
  }
```

- A `read` method that returns `Result<Settings, List<String>>`, with `Settings` a private record of the class. The same pattern: build, check `problems()`, return `error` or `success`.

### Building results: `PhaseTimings.of`

```java
  public static Result<PhaseTimings, String> of(
      Duration dns,
      Duration connect,
      Duration tls,
      Duration firstByte,
      Duration transfer,
      Duration total) {
    Optional<String> problem = problemWith(dns, connect, tls, firstByte, transfer, total);
    if (problem.isPresent()) {
      return Result.error(problem.get());
    }
    return Result.success(new PhaseTimings(dns, connect, tls, firstByte, transfer, total));
  }
```

- The error type here is a single `String`, the reason. The method returns `Result<PhaseTimings, String>`: one reason, not a list. Compare with the constructor, which throws for the same two rules. `of` is for input that comes from outside (a curl line); the constructor is for code that should never pass bad values.

### Building results: `CurlLine`

```java
  public static Result<CurlSample, CurlProblem> parse(String line) {
    Objects.requireNonNull(line, "line");
    int serverAt = line.indexOf(SERVER_TIMING_FIELD);
    if (serverAt < 0) {
      return malformed(line, "no server= field");
    }
    String serverTiming = line.substring(serverAt + SERVER_TIMING_FIELD.length()).strip();
    String[] tokens = line.substring(0, serverAt).strip().split(" +");
    String target = tokens[0];
    if (!CurlSample.TARGET_NAME.matcher(target).matches()) {
      return malformed(line, "invalid target name");
    }
    Map<String, String> fields = new HashMap<>();
    for (int tokenIndex = 1; tokenIndex < tokens.length; tokenIndex++) {
      String[] pair = tokens[tokenIndex].split("=", 2);
      if (pair.length != 2 || fields.put(pair[0], pair[1]) != null) {
        return malformed(line, "invalid or repeated field " + tokens[tokenIndex]);
      }
    }
    for (String key : REQUIRED_KEYS) {
      if (!fields.containsKey(key)) {
        return malformed(line, "no " + key + "= field");
      }
    }
    try {
      return sample(target, fields, serverTiming);
    } catch (NumberFormatException | ArithmeticException notANumber) {
      // A number that does not parse is bad input, so it is a returned problem, not a crash.
      return malformed(line, "a number is invalid");
    }
  }
```

- `parse` returns `Result<CurlSample, CurlProblem>`: the error side is a sealed interface with two cases (`Malformed` and `Rejected`).
- Each failure path ends in `return malformed(line, "...")`, a helper that makes `Result.error(new CurlProblem.Malformed(...))`.

```java
  private static Result<CurlSample, CurlProblem> malformed(String line, String reason) {
    String shown =
        line.length() > SHOWN_LINE_CHARACTERS ? line.substring(0, SHOWN_LINE_CHARACTERS) : line;
    return Result.error(new CurlProblem.Malformed(shown, reason));
  }
}
```

- The helper that builds the error result.

### Examining a result: `switch` with record patterns

```java
    ConnectionKind kind =
        Integer.parseInt(fields.get("connects")) == 0 ? ConnectionKind.REUSED : ConnectionKind.NEW;
    return switch (phases) {
      case Result.Success<PhaseTimings, String>(PhaseTimings checked) ->
          Result.success(
              new CurlSample(target, protocol, kind, status, sizeBytes, checked, serverTiming));
      case Result.Error<PhaseTimings, String>(String reason) ->
          Result.error(new CurlProblem.Rejected(target, reason));
    };
  }
```

- Here `sample` examines the `Result<PhaseTimings, String>` and returns a different `Result`: a switch **expression** (it produces a value). Each case unpacks the record: `Result.Success<PhaseTimings, String>(PhaseTimings checked)` binds the good value to `checked`; `Result.Error<PhaseTimings, String>(String reason)` binds the reason.
- The compiler checks that both cases are present. If a later version of the library added a third case, this `switch` would stop compiling, and the author would have to handle it.

```java
    try {
      SettingsReader reader = new SettingsReader(SettingsReader.load(Path.of(arguments[0])));
      switch (read(reader)) {
        case Result.Success<Settings, List<String>>(Settings settings) -> serve(settings);
        case Result.Error<Settings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid setting: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException | GeneralSecurityException failed) {
      System.err.println("cannot serve: " + failed.getMessage());
      System.exit(EXIT_BAD_USAGE);
    }
  }
```

- In `ServeCli.main` the `switch` is a **statement**: each arm does something instead of producing a value. On success it calls `serve(settings)`. On error it prints one line per problem with `problems.forEach(...)` and exits with status 2.

```java
          switch (CurlLine.parse(line)) {
            case Result.Success<CurlSample, CurlProblem>(CurlSample sample) -> samples.add(sample);
            case Result.Error<CurlSample, CurlProblem>(CurlProblem problem) ->
                problems.add(problem);
          }
```

- `ReportCli` uses the same idea per line of curl output: a success adds a sample to one list; an error adds a problem to another. Both lists are printed at the end.

The other three commands (`timing`, `tlsFailures`, `pageModel`) have the same two-arm `switch` as `ServeCli`. Their lines are in `java-timing-walkthrough.md`, `java-tls-walkthrough.md` and `java-model-and-config-walkthrough.md`.

## Try it yourself

All commands run in the repository folder.

**1. See that the library is the only runtime dependency.**

```text
./gradlew -q dependencies --configuration runtimeClasspath
```

```text
runtimeClasspath - Runtime classpath of source set 'main'.
+--- com.github.sviperll:result4j:1.2.0
\--- com.github.sviperll:result4j:{strictly 1.2.0} -> 1.2.0 (c)
```

(Gradle also prints a banner and a legend, left out here.) The `strictly` line is the lock file's constraint: the version may not change.

**2. List the methods of the type, from the jar.** Find the jar in Gradle's cache and run `javap`:

```text
javap -public -cp "$(find ~/.gradle -name 'result4j-1.2.0.jar' | head -1)" com.github.sviperll.result4j.Result | head -12
```

You should see `success`, `error`, `isError`, `map`, `flatMap` and the others listed above.

**3. Watch an expected failure come back as a value, not a crash.** Break a settings file as in `java-properties.md`, and run:

```text
sed -e 's/^rtt.millis=80/rtt.millis=abc/' -e '/^trials=/d' config/page-model.properties > build/bad-model.properties
./gradlew -q pageModel --args=build/bad-model.properties
```

```text
invalid setting: rtt.millis must be a decimal number, but was abc
invalid setting: trials is missing
```

No stack trace: the program took the `Error` arm of the `switch` and exited with status 2. Delete the copy afterwards.

**4. Run the demo program.** Save the program from "How it works underneath" (the one that parses `42` and `x`) as `build/R4j.java` and run `java -cp <the jar> build/R4j.java`. It prints the six lines above.

## Mistakes people make

- **Using a `Result` for a bug.** A null argument or a record built with a forbidden value is a programmer error. `Objects.requireNonNull` and the constructors throw, as they should.
- **Using an exception for a typo in a settings file.** The program would stop at the first bad key. Here, every bad key is listed in one run.
- **Reading the value of an error.** The caller must look at the case first. The sealed `switch` forces it.
- **Using the stand-in values.** `SettingsReader` returns a stand-in for a bad key. The code returns `Result.error(reader.problems())` before it builds anything from them.
- **A `default` arm that hides a new case.** None of the switches here has a `default`, so a new case stops the build.
- **Mixing up the two type parameters.** The pattern needs both: `Result.Success<R, E>(R value)`.

## Check yourself

1. What are the two cases of a `Result`, and what does each hold? (`Success` holds the value `R`; `Error` holds the reason `E`.)
2. Why do the `switch`es over a `Result` in this repository have no `default` arm? (The interface is sealed with two permitted records, so the compiler knows both cases are handled. A new case would stop the build.)
3. When does this repository throw an exception instead of returning an error? (For programmer errors: a null argument, or a record built with a value its constructor forbids.)
4. In `TlsSettings.from`, why is `Result.error(reader.problems())` returned before the record is built? (The stand-in values of bad keys are harmless only because the record is never made from them.)
5. What error type does `PhaseTimings.of` use, and why not a list? (A single `String`: it reports the first rule a set of phases breaks.)

## Further reading

Official documentation:

- The result4j project and its README, at the pinned version 1.2.0: https://github.com/sviperll/result4j
- Maven Central page of the artifact: https://central.sonatype.com/artifact/com.github.sviperll/result4j/1.2.0
- Sealed classes (JEP 409): https://openjdk.org/jeps/409
- Records (JEP 395): https://openjdk.org/jeps/395
- Pattern matching for `switch` (JEP 441) and record patterns (JEP 440): https://openjdk.org/jeps/441 and https://openjdk.org/jeps/440

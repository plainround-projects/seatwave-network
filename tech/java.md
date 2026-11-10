# Java 25 (the language and the standard library)

## What it is

Java is a programming language. You write **classes** in text files that end in `.java`. A program called the **compiler** (`javac`) turns them into **bytecode**, a compact instruction format stored in `.class` files. A second program, the **Java Virtual Machine** (JVM), reads the bytecode and runs it. The JVM is the same on a Mac, a Windows PC and a Linux server, so the same bytecode runs on all of them.

Java is **statically typed**. Every variable, parameter and return value has a type, such as `int` (a whole number) or `String` (text). The compiler checks the types before the program runs. A mistake such as passing text where a number is expected stops the build instead of crashing later.

The **JDK** (Java Development Kit) is the download that has the compiler, the JVM and the **standard library**: ready-made classes such as `String`, `List`, `Duration` and `HttpClient`. This repository uses **JDK 25**, a long-term-support release. The machine used for these notes had Eclipse Temurin 25.0.2, and `java -version` printed `openjdk version "25.0.2" 2026-01-20 LTS`.

## Why this repository uses it

The repository is the hands-on project of Module 5 of a system design course. It serves a ticket-booking case called SeatWave over HTTP and HTTPS, delays bytes on purpose, times each phase of a request, models page loads, and tests TLS failures. Java 25 gives what that work needs, with no framework:

- **A web server, an HTTP client and TLS in the JDK** (`com.sun.net.httpserver`, `java.net.http`, `javax.net.ssl`). `java-networking-and-tls.md` explains them.
- **Sockets** (`java.net.Socket` and `ServerSocket`) for the delay link.
- **Virtual threads** (Java 21): cheap threads, so every connection and every direction of a connection can have its own thread.
- **Records, enums and sealed interfaces**: small immutable data types in a few lines, and `switch` statements that the compiler proves complete.
- **A standard library that covers the rest**, so the project has exactly one runtime library (`result4j.md`).

## Where it appears

| Path | What it does with Java |
|---|---|
| `src/main/java/io/plainround/seatwave/network/server/` | 3 files: SeatWave's web server and its JSON bodies. See `java-server-and-link-walkthrough.md`. |
| `src/main/java/io/plainround/seatwave/network/link/` | 3 files: the delay link. Same walkthrough. |
| `src/main/java/io/plainround/seatwave/network/timing/` | 5 files: the Java timing client. See `java-timing-walkthrough.md`. |
| `src/main/java/io/plainround/seatwave/network/tls/` | 6 files: TLS contexts and the failure lab. See `java-tls-walkthrough.md`. |
| `src/main/java/io/plainround/seatwave/network/report/` | 12 files: curl line parser and report tables. See `java-report-walkthrough.md`. |
| `src/main/java/io/plainround/seatwave/network/model/` and `config/` | 6 files: the page model and the settings reader. See `java-model-and-config-walkthrough.md`. |
| `src/test/java/io/plainround/seatwave/network/` | The tests, also Java. |
| `build.gradle.kts` | Tells Gradle to compile with Java 25 and to treat every warning as an error. |

In all there are 35 source files under `src/main/java`. I ran `./gradlew -q compileJava` (it printed nothing, which means success) and found 58 class files under `build/classes/java/main/`. That is more than 35 because a nested class, a record or an enum inside a file gets its own class file.

## How it works underneath

**From text to a running program.** You write `Scheme.java`. The compiler reads it and writes `Scheme.class`. Gradle does this for you. To start a program, a JVM process starts, loads the class you name, and calls its `main` method. Each command of this repository has its own `main` (`ServeCli`, `TimingCli`, `TlsFailuresCli`, `ReportCli`, `PageModelCli`), and each has a Gradle task of the same short name.

**A process** is one running copy of a program, with its own memory. The JVM is one process. When `main` returns and no other thread is running, the process ends with **exit status** 0, which means success. A program may call `System.exit(n)` to end at once with another number. By convention a non-zero status means failure. This repository uses 1 for "the run found something wrong" and 2 for "the command was used incorrectly".

**Memory.** Two areas matter. The **stack** holds the method calls in progress with their local variables: each call adds a frame and each return removes one. Every thread has its own stack. The **heap** holds objects: `new Scenario(...)` makes an object on the heap. A variable holds a **reference**, like an address that points to the object. The JVM frees objects that no variable can reach any more. This is **garbage collection**. You never free memory by hand.

**The JIT compiler.** The JVM first interprets bytecode. It counts how often each method runs, and the **JIT compiler** (just-in-time compiler) turns the busy methods into native machine code while the program runs. A method is slow in its first runs and fast after. That is why the timing client makes warm-up runs and throws them away.

**Threads.** A **thread** is one line of execution inside a process. A program may have several, and they run at the same time on different processor cores. A **blocking** call, such as reading from a socket, makes its thread wait until the data arrives. A **virtual thread** is a thread that the JDK manages itself, cheap enough to start thousands: when it blocks, the JDK parks it and lets another one use the processor. This is why the delay link can give every connection five threads.

**The class path** is the list of places where the JVM looks for compiled classes and libraries (jar files, which are zip files of classes). Gradle builds the right class path for each task.

**Compile flags.** `build.gradle.kts` sets `options.release = 25` (compile for Java 25 and use only its library) and `-Xlint:all -Werror` (turn on every warning, and make any warning fail the build). So a warning cannot stay.

## Walkthrough of this repository's use

### The basic words

**Class.** A class is a blueprint for objects. It has **fields** (the data) and **methods** (the code). Everything in Java lives in a class. Records, enums and interfaces are special kinds of class.

**Package and import.** A **package** is a named group of classes, written with dots: `io.plainround.seatwave.network.server`. The name matches the folder path under `src/main/java`. The first line of a file is `package ...;`. After it come the **imports**: each `import java.util.List;` lets the file write `List` instead of the full name `java.util.List`. Classes of the same package need no import.

```java
package io.plainround.seatwave.network.server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
```

**Visibility and `static`, `final`.** `public` means any class may use it. `private` means only this class. `static` means it belongs to the class, not to an object: you call it as `SeatMapJson.events()`, with no object. `final` on a field or variable means it can be assigned once. `final` on a class means no one can extend it.

```java
public final class SeatMapJson {

  /** Module 1's one event. */
  public static final int EVENT_ID = 1;

  // Module 1 seeded 1,000 seats per section, labelled A-1 to A-1000.
  private static final int SEATS_PER_SECTION = 1_000;
  // Module 1 printed this start time in its sample output; it is reused so the body is the same.
  private static final long EVENT_STARTS_AT_EPOCH_MILLIS = 1_796_761_251_783L;
  // A section is one capital letter, as in Module 1's section=A.
  private static final Pattern SECTION = Pattern.compile("[A-Z]");

  private SeatMapJson() {}
```

- `public static final int EVENT_ID = 1;` is a constant.
- `private SeatMapJson() {}` is a **constructor**: code that runs when an object is made. It is private and empty, so nobody can write `new SeatMapJson()`. The class is a bag of static methods.

**Method.** A method is a named piece of code with parameters and a return type. `void` means it returns nothing.

```java
  /**
   * Checks a section name against the allow-list: exactly one capital letter.
   *
   * @param section the text from the query string
   * @return true if the section is valid
   */
  public static boolean isValidSection(String section) {
    Objects.requireNonNull(section, "section");
    return SECTION.matcher(section).matches();
  }
```

**The `main` method.** A program starts at `public static void main(String[] arguments)`. The array holds what the user typed after the command name.

```java
  /**
   * Prints the report.
   *
   * @param arguments one or more files of curl timing lines
   */
  public static void main(String[] arguments) {
    if (arguments.length == 0) {
      System.err.println("usage: report <curl-timings.txt>...");
      System.exit(EXIT_BAD_USAGE);
    }
```

**Control flow.** `if`, `for`, `while` and `switch` choose and repeat. `&&` is "and", `||` is "or", `!` is "not". A `for` loop has three parts: start, test, step.

```java
    List<String> labels = new ArrayList<>(SEATS_PER_SECTION);
    for (int seatNumber = 1; seatNumber <= SEATS_PER_SECTION; seatNumber++) {
      labels.add(section + "-" + seatNumber);
    }
```

**Operators.** `+=` adds and assigns. `&=` on booleans is "and" and assign (`TlsFailuresCli` uses it). `x ? a : b` is the **conditional expression**: `a` if `x` is true, otherwise `b`.

```java
    double share = totalMillis == 0 ? 0 : PERCENT * dominantMillis / totalMillis;
```

### The features this code uses

Each feature below shows a real example from this repository. The **minimum Java version** of each feature is in the table at the end of this section.

#### 1. Records

A **record** is a short way to declare a class that only carries data. The line `record Response(int status, byte[] body) {}` gives you a constructor, accessor methods `status()` and `body()`, and `equals`, `hashCode` and `toString`, all made by the compiler. The fields are `final`, so a record is immutable once made.

```java
  private record Response(int status, byte[] body) {}
```

A **compact constructor** has no parameter list. It runs before the fields are assigned and is the place to check values and to make defensive copies.

```java
public record LinkSettings(
    Duration oneWayDelay, HandshakeDelay handshakeDelay, int maxConnections) {

  // A one-way delay above 10 seconds is not a network; it is a typo.
  private static final Duration LONGEST_ONE_WAY_DELAY = Duration.ofSeconds(10);

  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if the delay is negative or too long, or no connection is
   *     allowed
   */
  public LinkSettings {
    Objects.requireNonNull(oneWayDelay, "oneWayDelay");
    Objects.requireNonNull(handshakeDelay, "handshakeDelay");
    if (oneWayDelay.isNegative() || oneWayDelay.compareTo(LONGEST_ONE_WAY_DELAY) > 0) {
      throw new IllegalArgumentException("the one-way delay must be from 0 to 10 seconds");
    }
    if (maxConnections < 1) {
      throw new IllegalArgumentException("at least one connection must be allowed");
    }
  }
```

- If a check throws, no object exists: a `LinkSettings` that is out of range **cannot be created**. That is the pattern throughout the repository.
- `List.copyOf(...)` inside such a constructor makes the record hold an unchangeable copy, so a caller cannot change it later.

A record can be **nested** inside a class, or inside an interface:

```java
  record Malformed(String line, String reason) implements CurlProblem {
    @Override
    public String describe() {
      return "malformed line (" + reason + "): " + line;
    }
  }
```

#### 2. Enums

An **enum** is a type with a fixed set of named values. An enum may have fields and a constructor. `values()` returns all the constants in order. `name()` returns the constant's name as text.

```java
public enum Phase {
  /** Turning the host name into an IP address (Module 6). */
  DNS("dns"),
  /** The TCP three-way handshake: one round trip. */
  CONNECT("connect"),
  /** The TLS handshake: one round trip for TLS 1.3, two for TLS 1.2; zero for plain HTTP. */
  TLS("tls"),
  /** From the end of the handshakes to the first byte of the response: one round trip + server. */
  FIRST_BYTE("first byte"),
  /** From the first byte of the response to the last. */
  TRANSFER("transfer");

  private final String label;

  Phase(String label) {
    this.label = label;
  }

  /**
   * Returns the name a report prints.
   *
   * @return for example {@code first byte}
   */
  public String label() {
    return label;
  }
}
```

#### 3. Sealed interfaces

A **sealed** interface lists (or implies, from the same file) every type that may implement it. The compiler then knows all the cases.

```java
/** Why one line of curl output did not become a {@link CurlSample}. */
public sealed interface CurlProblem {
```

#### 4. `switch` expressions and statements

A **switch expression** produces a value: `case X -> value;`. One arm may name several constants. For an enum, the compiler checks that every constant is covered, and then no `default` is needed. I wrote a three-value enum with a switch that covers only two, and the compiler said:

```text
error: the switch expression does not cover all possible input values
```

That is the check every `switch` here relies on: add a constant, and the build stops until each `switch` handles it.

```java
    return switch (protocol) {
      case HTTP3 -> 1;
      case HTTP1_ONE_CONNECTION, HTTP1_SIX_CONNECTIONS, HTTP2 -> 1 + settings.tlsRoundTrips();
    };
```

A switch can also match **patterns**. A **record pattern** takes a record apart and names its parts:

```java
    return switch (phases) {
      case Result.Success<PhaseTimings, String>(PhaseTimings checked) ->
          Result.success(
              new CurlSample(target, protocol, kind, status, sizeBytes, checked, serverTiming));
      case Result.Error<PhaseTimings, String>(String reason) ->
          Result.error(new CurlProblem.Rejected(target, reason));
    };
```

`Result.Success<PhaseTimings, String>(PhaseTimings checked)` means: "if it is a `Success`, take its one component and call it `checked`". This is how every command handles a `Result` (see `result4j.md`).

#### 5. Generics

A **generic** type has a type parameter in angle brackets: `List<String>` is a list of text. `<T>` before a method declares a type parameter of the method. `<E extends Enum<E>>` says `E` must be an enum. `<?>` is a **wildcard**: "any type". The **diamond** `new ArrayList<>()` lets the compiler fill in the type.

```java
  public static <T> T medianBy(List<T> runs, Comparator<T> order) {
    Objects.requireNonNull(order, "order");
    requireEnough(runs);
    List<T> sorted = new ArrayList<>(runs);
    sorted.sort(order);
    // The lower middle: for an even count it is a value that was really measured, not an average.
    return sorted.get((sorted.size() - 1) / 2);
  }
```

```java
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

Generics exist only at compile time. A `List<Integer>` cannot hold the primitive `int`, so it holds `Integer`, a box object, and the language boxes and unboxes for you.

#### 6. Lambdas and method references

A **lambda** is a small function written inline: `run -> run.phases().total()`. A **method reference** names an existing method: `SeatWaveServer::handle`, `String::valueOf`, `String[]::new` (the last makes a new array of a given size).

```java
  public CurlSample medianRun() {
    return Runs.medianBy(runs, Comparator.comparing(run -> run.phases().total()));
  }
```

```java
  private SeatWaveServer(HttpServer server, String scheme) {
    this.server = server;
    this.scheme = scheme;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }
```

#### 7. Streams and `Optional`

A **stream** is a pipeline over a collection: `filter` keeps items, `map` changes them, `reduce` combines them, `toList()` collects them. A stream does not change the original list. `Optional<T>` is a box that holds one value or nothing; `orElseThrow()` unwraps it or throws.

```java
    if (runs.stream().map(ReuseSample::protocol).distinct().count() != 1) {
      throw new IllegalArgumentException("the runs of one row must use one protocol");
    }
```

```java
  public Duration fastest() {
    return runs.stream()
        .map(run -> run.phases().total())
        .min(Comparator.naturalOrder())
        .orElseThrow();
  }
```

#### 8. Immutable collections

`List.of(a, b)` makes a list that cannot change. `List.copyOf(list)` makes an unchangeable copy. `list.getFirst()` returns the first item. An attempt to add to an unchangeable list throws `UnsupportedOperationException`.

```java
    serverProtocols = List.copyOf(serverProtocols);
    versions = List.copyOf(versions);
    scenarios = List.copyOf(scenarios);
```

#### 9. Exceptions

An **exception** is an object that interrupts the normal flow when something goes wrong. `throw new X(...)` raises it. `try { ... } catch (X e) { ... }` handles it. `finally { ... }` runs in every case.

- **Checked** exceptions (`IOException`, `InterruptedException`, `GeneralSecurityException`): the compiler forces each method to catch them or to declare `throws`.
- **Unchecked** exceptions (`IllegalArgumentException`, `NullPointerException`): no declaration needed. They mean a bug.
- **Multi-catch**: `catch (IOException | GeneralSecurityException failed)` handles either.

```java
    } catch (IOException | GeneralSecurityException failed) {
      System.err.println("cannot serve: " + failed.getMessage());
      System.exit(EXIT_BAD_USAGE);
    }
```

`InterruptedException` is thrown when another thread interrupts a waiting thread. The right response is to stop, or to restore the **interrupt flag** with `Thread.currentThread().interrupt()` so callers can still see it. The delay link does this:

```java
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      writer.interrupt();
    }
```

`addSuppressed` attaches a second error to the first, so neither is lost:

```java
    } catch (IOException peerGone) {
      // The receiving side is gone, so the connection is over: closing the sending side makes
      // its reader see the end too, instead of filling a queue nobody empties.
      try {
        from.close();
      } catch (IOException alsoGone) {
        peerGone.addSuppressed(alsoGone);
        connectionsFailed.incrementAndGet();
      }
```

#### 10. try-with-resources

A **resource** is something that must be closed: a file, a socket, a server, an executor. It implements `AutoCloseable`. `try (Resource r = ...) { ... }` closes `r` at the end of the block, even if an exception is thrown. Several resources are closed in reverse order. Since Java 9 an existing variable can be used: `try (exchange)`.

```java
    try (SeatWaveServer plain = SeatWaveServer.startPlain(settings.httpPort());
        SeatWaveServer tls =
            SeatWaveServer.startTls(
                settings.httpsPort(),
                TlsMaterial.serverContext(
                    directory.resolve("seatwave-valid.p12"), directory.resolve("store.password")),
                settings.protocols());
        DelayLink distant =
            DelayLink.start(
                settings.distantPort(),
                tls.port(),
                new LinkSettings(
                    settings.oneWayDelay(),
                    HandshakeDelay.ONE_ROUND_TRIP,
                    settings.maxConnections()))) {
```

```java
  private void handle(HttpExchange exchange) throws IOException {
    long startNanos = System.nanoTime();
    try (exchange) {
      String method = exchange.getRequestMethod();
      boolean isHead = "HEAD".equals(method);
      if (!isHead && !"GET".equals(method)) {
        exchange.getResponseHeaders().set("Allow", "GET, HEAD");
        reply(exchange, startNanos, METHOD_NOT_ALLOWED, problem("only GET and HEAD"), false);
        return;
      }
      Response response = route(exchange.getRequestURI());
      reply(exchange, startNanos, response.status(), response.body(), isHead);
    }
  }
```

#### 11. Strings and formatting

`String` is immutable text. `+` joins strings. `StringBuilder` builds a long text without a new `String` for each append. `String.format(Locale.ROOT, "...", values)` fills `%` codes: `%s` any value as text, `%d` a whole number, `%.2f` a decimal with 2 digits, `%n` a line break, `%,d` a whole number with thousands separators, `%-12s` text padded on the right to 12 characters, `%8.2f` a decimal in 8 characters. `Locale.ROOT` means "no country rules", so the decimal point is always a point.

```java
  private static final String PHASE_HEADER =
      "target            connection protocol  runs   dns ms connect ms   tls ms first byte ms"
          + " transfer ms   total ms   fastest  slowest  dominant";
  private static final String PHASE_ROW =
      "%-17s %-10s %-9s %4d %8.2f %10.2f %8.2f %13.2f %11.2f %10.2f %9.2f %8.2f  %s%n";
  private static final String REUSE_HEADER =
      "scenario       requested  answered   runs  new conn p50 ms  reused p50 ms  saved by reuse ms"
          + "  connections opened";
  private static final String REUSE_ROW = "%-14s %-10s %-10s %4d %16.2f %14.2f %18.2f %19d%n";
```

#### 12. Numbers

`int` is a 32-bit whole number (about plus or minus 2 billion). `long` is 64-bit; a literal ends in `L`. `double` is a decimal number. An underscore in a number is only for reading: `1_000_000`. A cast `(int) x` converts, and can lose information. Whole-number division drops the fraction: `(bytes + size - 1) / size` is a **ceiling division**.

```java
  private int segmentCount(int responseIndex) {
    int bytes = settings.responseBytes().get(responseIndex);
    return (bytes + settings.segmentBytes() - 1) / settings.segmentBytes();
  }
```

```java
      currentWindow = (int) Math.min((long) currentWindow + inRound, Integer.MAX_VALUE);
```

Here the `(long)` cast stops `currentWindow + inRound` from overflowing an `int`, and `Math.min(..., Integer.MAX_VALUE)` caps the result before the `(int)` cast.

#### 13. Time: `Duration` and `System.nanoTime`

`Duration` is a length of time. It has no `<` operator: use `compareTo`, `isNegative()` or `isZero()`. `Duration.ofMillis(5)`, `toNanos()`, `plus`, `minus`, `multipliedBy` and `abs` do the arithmetic. `System.nanoTime()` is the clock for measuring elapsed time: it only goes forward, and its zero is arbitrary.

```java
  public Duration medianNew() {
    return Runs.median(runs.stream().map(ReuseSample::newConnection).toList());
  }
```

```java
  private static Timed timed(HttpClient client, HttpRequest request)
      throws IOException, InterruptedException {
    long startNanos = System.nanoTime();
    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
```

#### 14. Regular expressions

A **regular expression** (regex) is a pattern for text. `Pattern.compile("[A-Z]")` builds it once. `matcher(text).matches()` is true if the whole text matches.

```java
  // A path SeatWave serves: a slash, then letters, digits, slashes, and one optional query.
  private static final Pattern PROBE_PATH = Pattern.compile("/[a-z0-9/]*(\\?[a-z]+=[A-Z0-9]+)?");
  private static final Pattern SCENARIO_NAME = Pattern.compile("[a-z][a-z0-9-]{0,19}");
```

#### 15. Files and paths

`Path.of("build/tls")` is a file path. `path.resolve("name")` appends a name. `Files.newInputStream`, `Files.readString`, `Files.readAllLines` and `Files.newBufferedReader` read files. Always say the character set: `StandardCharsets.UTF_8`.

```java
  private static char[] readPassword(Path passwordFile) throws IOException {
    Objects.requireNonNull(passwordFile, "passwordFile");
    String password = Files.readString(passwordFile, StandardCharsets.UTF_8).strip();
    if (password.isEmpty()) {
      throw new IOException("the password file is empty: " + passwordFile);
    }
    return password.toCharArray();
  }
```

#### 16. Anonymous classes and `@Override`

An **anonymous class** is a subclass with no name, written where it is used. `@Override` tells the compiler a method replaces one of the parent, so a typo fails the build.

```java
    https.setHttpsConfigurator(
        new HttpsConfigurator(context) {
          @Override
          public void configure(HttpsParameters parameters) {
            SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
            // Only the listed versions: an older version a client offers is refused in the
            // handshake, which is the fail-safe answer (Module 5, Section 9).
            ssl.setProtocols(accepted);
            parameters.setSSLParameters(ssl);
          }
        });
```

#### 17. Threads, executors and the small concurrency tools

- **Virtual threads and executors.** `Executors.newVirtualThreadPerTaskExecutor()` makes an executor that starts one virtual thread per task. `executor.submit(task)` starts a task. `Thread.ofVirtual().start(task)` starts one virtual thread directly. Closing an `ExecutorService` (since Java 19) waits for its tasks.
- **Queues.** An `ArrayBlockingQueue` has a fixed capacity. `take()` waits for an item; `offer(item, timeout, unit)` waits for room, and returns false on timeout.
- **`Semaphore`.** A counter of permits. `tryAcquire()` takes one without waiting; `release()` gives it back.
- **`AtomicInteger`.** A counter that many threads can change without losing updates.
- **`volatile`.** A field marker: a write is visible to other threads at once.
- **`ConcurrentHashMap.newKeySet()`.** A set safe for many threads.
- **Join and interrupt.** `thread.join()` waits for a thread to end. `thread.interrupt()` asks a waiting thread to stop.

```java
  private void acceptLoop() {
    while (!listener.isClosed()) {
      try {
        Socket client = listener.accept();
        if (!openSlots.tryAcquire()) {
          // Over the limit: refuse at once rather than accept and stall (failure mode 5).
          connectionsRefused.incrementAndGet();
          client.close();
          continue;
        }
        connectionsAccepted.incrementAndGet();
        threads.submit(() -> serve(client));
      } catch (IOException acceptFailed) {
        // accept fails when close() closed the listener, which ends the loop; any other failure
        // also ends it, and clients then see "connection refused" instead of a hang.
        return;
      }
    }
  }
```

```java
  private static void waitUntil(long dueNanos) throws InterruptedException {
    long remainingNanos;
    // A sleep can wake late by a share of its length (macOS coalesces timers), so the writer
    // sleeps half of what is left at a time, and spins only for the last fraction of a millisecond.
    while ((remainingNanos = dueNanos - System.nanoTime()) > SPIN_BELOW_NANOS) {
      Thread.sleep(Duration.ofNanos(remainingNanos / 2));
    }
    while (System.nanoTime() < dueNanos) {
      Thread.onSpinWait();
    }
  }
```

#### 18. Javadoc

A comment that starts with `/**` is **Javadoc**. Tools turn it into web documentation. `@param name text` documents a parameter, `@return` the result, `@throws` an exception, `{@code x}` prints code, `{@link Type}` links. The build's `-Xlint:all` also checks them.

```java
  /**
   * Makes runs, each with a new client: one request on a new connection, then one on the same.
   *
   * @param uri the resource, for example the seat map
   * @param version the HTTP version to ask for; the server may answer with an older one
   * @param runs how many runs, 1 or more
   * @return one sample per run, with the protocol the server answered with
   * @throws IOException if a request fails or the server answers anything but 200 with a body
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public List<ReuseSample> measure(URI uri, HttpClient.Version version, int runs)
      throws IOException, InterruptedException {
```

#### 19. The static helpers used everywhere

- `Objects.requireNonNull(x, "x")` throws `NullPointerException` named `x` if `x` is null, otherwise returns `x`.
- `Math.min`, `Math.max`, `Math.ceil`, `Math.toIntExact` (throws instead of wrapping).
- `System.out.printf(...)` prints formatted text; `System.err.println(...)` prints to the error stream; `System.exit(n)` ends the program; `System.getProperty("os.name")` reads a property of the JVM; `System.setProperty(...)` sets one; `System.lineSeparator()` is the line break of the machine.
- `Runtime.version()` is the Java version that runs the code.

### The minimum Java version of each feature

| Feature | Example in this repository | First final in |
|---|---|---|
| Underscores in numbers | `1_000`, `65_535` | Java 7 |
| try-with-resources, multi-catch, `addSuppressed` | `ServeCli.serve`, `DelayLink.deliver` | Java 7 |
| Generics with `<>` (diamond) | `new ArrayList<>()` | Java 7 |
| Lambdas, method references, streams, `Optional`, `Duration` | `TargetTiming.medianRun` | Java 8 |
| `ConcurrentHashMap.newKeySet()`, `SplittableRandom` | `DelayLink`, `PageLoadModel` | Java 8 |
| Existing variable as a try resource (`try (exchange)`) | `SeatWaveServer.handle` | Java 9 |
| `List.of`, `Duration.toSeconds`, `Thread.onSpinWait`, `Runtime.version()` | many | Java 9 |
| `List.copyOf`, `Optional.orElseThrow()` with no argument | `TimingSettings` | Java 10 |
| `String.strip`, `isBlank`, `Path.of`, `Files.readString`, `toArray(String[]::new)`, `java.net.http.HttpClient`, `HttpResponse.sslSession()` | many | Java 11 |
| Switch expressions (`case X -> value`), several labels per arm | `PageLoadModel.setupRoundTrips` | Java 14 |
| `Stream.toList()` | `ReuseTiming.medianNew` | Java 16 |
| Records (and compact constructors) | `LinkSettings`, `Scenario` | Java 16 |
| Sealed interfaces | `CurlProblem` | Java 17 |
| `RandomGenerator` interface | `PageLoadModel.simulate` | Java 17 |
| `Thread.sleep(Duration)`, `ExecutorService.close()` | `DelayLink.waitUntil` | Java 19 |
| Virtual threads, `Executors.newVirtualThreadPerTaskExecutor`, `Thread.ofVirtual` | `SeatWaveServer`, `DelayLink` | Java 21 |
| Pattern matching for `switch`, record patterns | every `Result` `switch` | Java 21 |
| `List.getFirst()` | `ReuseTiming.protocol` | Java 21 |
| `HttpClient` as `AutoCloseable` | `ReuseProbe.measure` | Java 21 |

So the code needs Java 21 at least. It is built with `--release 25`.

**Java 25 features this code does not use.** Java 25 made instance main methods and compact source files (JEP 512), flexible constructor bodies (JEP 513) and scoped values (JEP 506) final. This code uses the classic `public static void main(String[] arguments)`, has no code before `super(...)`, and passes values as arguments. It also uses no `var`, no text blocks and no `instanceof` patterns.

## Try it yourself

All commands run in the repository folder.

**1. Check the Java version.**

```text
java -version
```

```text
openjdk version "25.0.2" 2026-01-20 LTS
OpenJDK Runtime Environment Temurin-25.0.2+10 (build 25.0.2+10-LTS)
OpenJDK 64-Bit Server VM Temurin-25.0.2+10 (build 25.0.2+10-LTS, mixed mode, sharing)
```

**2. Compile, and count the class files.**

```text
./gradlew -q compileJava
find build/classes/java/main -name '*.class' | wc -l
find src/main/java -name '*.java' | wc -l
```

The first command printed nothing (success). The next two printed `58` and `35`: nested records and enums have their own class files.

**3. Look inside a record.** `javap` prints the members of a compiled class:

```text
javap -p -cp build/classes/java/main io.plainround.seatwave.network.link.LinkSettings
```

```text
Compiled from "LinkSettings.java"
public final class io.plainround.seatwave.network.link.LinkSettings extends java.lang.Record {
  private final java.time.Duration oneWayDelay;
  private final io.plainround.seatwave.network.link.HandshakeDelay handshakeDelay;
  private final int maxConnections;
  private static final java.time.Duration LONGEST_ONE_WAY_DELAY;
  public io.plainround.seatwave.network.link.LinkSettings(java.time.Duration, io.plainround.seatwave.network.link.HandshakeDelay, int);
  public java.time.Duration roundTrip();
  public final java.lang.String toString();
  public final int hashCode();
  public final boolean equals(java.lang.Object);
  public java.time.Duration oneWayDelay();
```

(The listing goes on with the other two accessors.) A record is `final`, extends `java.lang.Record`, has private final fields, and has the generated `toString`, `hashCode`, `equals` and accessors.

**4. See a `switch` that is not complete.** Save this as `build/Exh.java`, and run `java build/Exh.java`:

```text
public class Exh {
  enum Color { RED, GREEN, BLUE }
  static int code(Color c) {
    return switch (c) {
      case RED -> 1;
      case GREEN -> 2;
    };
  }
  public static void main(String[] a) { System.out.println(code(Color.RED)); }
}
```

```text
build/Exh.java:4: error: the switch expression does not cover all possible input values
    return switch (c) {
           ^
1 error
error: compilation failed
```

Add `case BLUE -> 3;` and it compiles. Delete the file afterwards.

**5. Run a command and read its exit status.**

```text
./gradlew -q report
```

It prints `usage: report <curl-timings.txt>...` and Gradle then reports the failure. The program ended with `System.exit(2)`: used incorrectly.

## Mistakes people make

- **Comparing text with `==`.** `==` compares references. Use `equals`. The code writes `"/events".equals(path)`, with the literal first, so a null `path` cannot crash it.
- **Catching an exception and doing nothing.** Every `catch` here records, returns or rethrows. An `InterruptedException` restores the interrupt flag.
- **Forgetting that a `long` literal needs `L`.** `1_796_761_251_783L`.
- **Integer overflow.** An `int` wraps silently. The code computes in `long` where a sum can be large, and uses `Math.toIntExact` where it must fit.
- **Dividing whole numbers and expecting a fraction.** `7 / 2` is `3`. The model uses `double` where fractions matter.
- **A `switch` with a `default` that hides a new case.** None of the `switch`es over enums or `Result` has a `default`.
- **Leaving a resource open.** Servers, links, clients, executors and files are all in `try (...)` blocks.
- **Comparing a `double` with `==` or ignoring NaN.** The settings checks are written so NaN fails them.
- **A default locale in a format.** `String.format` without `Locale.ROOT` can print a comma for a decimal point on some computers.

## Check yourself

1. What is the difference between a checked and an unchecked exception, with one example of each from this repository? (A checked one must be caught or declared: `IOException` in `SeatWaveServer.handle`. An unchecked one needs no declaration and means a bug: `IllegalArgumentException` in `LinkSettings`.)
2. What does a record's compact constructor do, and why is it used so much here? (It runs before the fields are set and checks them, so a value that breaks a rule cannot exist.)
3. Why does a `switch` over `HttpClient.Version` in `ReuseProbe.protocolName` need no `default`? (The enum has exactly two constants in JDK 25 and both are covered. If a constant is added, the build stops.)
4. What does `try (exchange)` do? (It closes the exchange at the end of the block, even on an error, because `HttpExchange` is `AutoCloseable`.)
5. What is the lowest Java version this code could run on, and which feature sets it? (Java 21: virtual threads and pattern matching for `switch`.)

## Further reading

Official documentation, for the JDK this repository pins (Java 25):

- The Java Language Specification, Java SE 25: https://docs.oracle.com/javase/specs/jls/se25/html/index.html
- The Java SE 25 API: https://docs.oracle.com/en/java/javase/25/docs/api/index.html
- JDK 25 release notes and the list of JEPs: https://openjdk.org/projects/jdk/25/
- Records (JEP 395): https://openjdk.org/jeps/395
- Sealed classes (JEP 409): https://openjdk.org/jeps/409
- Pattern matching for `switch` (JEP 441) and record patterns (JEP 440): https://openjdk.org/jeps/441 and https://openjdk.org/jeps/440
- Virtual threads (JEP 444): https://openjdk.org/jeps/444
- Javadoc: https://docs.oracle.com/en/java/javase/25/javadoc/javadoc.html

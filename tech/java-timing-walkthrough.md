# The `timing` package, line by line

## What it is

This file reads the Java code of the `timing` package, one line at a time. The package has five files: `Scheme.java`, `Scenario.java`, `TimingSettings.java`, `ReuseProbe.java` and `TimingCli.java`. Together they are the **Java timing client**: a program that sends a request to SeatWave, then a second request on the same connection, and prints how long each took. It does with Java's own HTTP client what `curl -w` does from the shell.

A **client** is a program that sends requests to a server. **Timing** here means reading a clock before and after a request.

## Why this repository uses it

Module 5 asks what a **new connection** costs and what **reusing** one saves. A new connection pays for the TCP handshake and, over HTTPS, the TLS handshake. A reused (keep-alive) connection has paid already. The timing client puts a number on the difference, at four distances, with the JDK's HTTP client (`java.net.http.HttpClient`), the one service code in Java would use. No library is added.

## Where it appears

| File | What it does |
|---|---|
| `timing/Scheme.java` | An enum: plain `HTTP` or `HTTPS`. |
| `timing/Scenario.java` | One way of reaching SeatWave: a name, a scheme, a delay and a handshake rule. |
| `timing/TimingSettings.java` | Reads and checks `config/timing.properties` and `config/lab2-distances.properties`. |
| `timing/ReuseProbe.java` | Makes the runs: a new client, a first request, a second request, a clock around each. |
| `timing/TimingCli.java` | The `timing` command: starts the servers and links, runs every scenario, prints the table. |

It uses `link/DelayLink`, `server/SeatWaveServer`, `tls/TlsMaterial` and `report/TimingReport` (see the other walkthroughs).

## How it works underneath

**One run, step by step.**

1. The probe builds a **new** `HttpClient`. A client keeps a pool of open connections and reuses them. A new client has an empty pool, so its first request must open a connection.
2. It sends request 1 and reads a clock before and after. The time covers everything: opening the TCP connection, the TLS handshake (for HTTPS), sending the request, the server's work, and receiving all of the body.
3. It sends request 2 with the same client. The pool holds the open connection, so nothing is opened. The time is the request and the reply only.
4. It closes the client, which closes its connections. The next run starts again with a new client.

**Where the milliseconds go with a distant fan.** The link adds 40 ms each way, so a round trip is 80 ms. A reused connection costs one round trip (the request goes, the reply returns): about 83 ms in the table. A new plain HTTP connection costs two (the TCP handshake, then the request): about 164 ms. A new HTTPS connection costs three (TCP, TLS 1.3, then the request): about 250 ms. On this machine with no link, the same requests cost 1.5 ms (HTTP), and about 8 to 11 ms for the first HTTPS request, because the TLS handshake costs processor time (key exchange and signature checks), not distance.

**Warm-up.** The first runs of any Java program are slow: classes load, and the JIT compiler (just-in-time compiler, which turns hot code into machine code while the program runs) has not warmed up. The probe makes a few runs first and throws them away. The rest are measured.

**Median.** A report row shows the median run: sort the times and take the middle one. One slow run (a pause for garbage collection, another program on the machine) cannot move it. `Runs.median` in the `report` package does it.

**Counting connections.** The link counts every connection it accepts. The CLI reads the count before and after the measured runs. Seven runs that each opened one connection give `connections opened = 7`. If reuse had failed, the count would be 14.

**What "HTTP/2" means here.** The client may be asked for HTTP/2. The JDK server does not speak HTTP/2, so it answers HTTP/1.1. The table has two columns, "requested" and "answered", so a reader sees what was really used. See `java-networking-and-tls.md`.

## Walkthrough of this repository's use

### `timing/Scheme.java`

```java
package io.plainround.seatwave.network.timing;

/** Plain HTTP or HTTP over TLS. */
public enum Scheme {
  /** Plain HTTP: no TLS handshake, nothing hidden or protected. */
  HTTP,
  /** HTTP over TLS. */
  HTTPS
}
```

- An enum with two values. `Scheme.HTTP.name()` is the text `HTTP`; the CLI lower-cases it to build the address start `http`.
- The `/** ... */` lines above each value are Javadoc comments on that value.

### `timing/Scenario.java`

```java
package io.plainround.seatwave.network.timing;

import io.plainround.seatwave.network.link.HandshakeDelay;
import java.time.Duration;
import java.util.Objects;
```

- The imports: the link's `HandshakeDelay`, `Duration` (a length of time), and `Objects` (for the null check).

```java
/**
 * One way of reaching SeatWave, for example plain HTTP on this machine, or HTTPS to a fan 80 ms
 * away.
 *
 * @param name a short name for the report, for example {@code distant-tls}
 * @param scheme plain HTTP or HTTPS
 * @param oneWayDelay the delay the link adds in each direction; zero for this machine
 * @param handshakeDelay whether the link also charges the TCP handshake's round trip
 */
public record Scenario(
    String name, Scheme scheme, Duration oneWayDelay, HandshakeDelay handshakeDelay) {
```

- A **record** with four components: a name (for the report), the scheme, the one-way delay of the link (zero for "this machine"), and the handshake rule.
- `{@code distant-tls}` prints code in the generated documentation.

```java
  /** Checks the fields. */
  public Scenario {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(scheme, "scheme");
    Objects.requireNonNull(oneWayDelay, "oneWayDelay");
    Objects.requireNonNull(handshakeDelay, "handshakeDelay");
    if (name.isBlank()) {
      throw new IllegalArgumentException("a scenario needs a name");
    }
  }
}
```

- The compact constructor checks every field. `Objects.requireNonNull(x, "x")` throws `NullPointerException` if `x` is null, naming it. A name with only spaces is refused with `IllegalArgumentException`.

### `timing/TimingSettings.java`

```java
package io.plainround.seatwave.network.timing;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import io.plainround.seatwave.network.link.HandshakeDelay;
import io.plainround.seatwave.network.report.Runs;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
```

- `Result` is the library type that holds a value or an error list (`result4j.md`). `SettingsReader` reads typed values from a properties file. `Runs` holds the rule "at least 3 runs". `HttpClient` is imported only for its `Version` enum. `Pattern` is a regular expression.

```java
/**
 * Everything the Java timing client needs, read from a settings file with no defaults.
 *
 * @param tlsDirectory the folder of the TEST certificates, {@code build/tls}
 * @param serverProtocols the TLS versions the HTTPS server accepts
 * @param probePath the path and query every request asks for
 * @param runs the measured runs per row, at least {@link Runs#MINIMUM_RUNS}
 * @param warmupRuns runs made first and thrown away, so class loading and the JIT compiler do not
 *     land in a measured run
 * @param timeouts the connect and request timeouts of every call
 * @param versions the HTTP versions the client asks for, in order
 * @param linkMaxConnections the most connections each link allows at once
 * @param scenarios the ways of reaching SeatWave, in order
 */
public record TimingSettings(
    Path tlsDirectory,
    List<String> serverProtocols,
    String probePath,
    int runs,
    int warmupRuns,
    Timeouts timeouts,
    List<HttpClient.Version> versions,
    int linkMaxConnections,
    List<Scenario> scenarios) {
```

- A record with nine components. Note `List<HttpClient.Version> versions`: the settings hold the JDK's own version enum. The nested record `Timeouts` is defined at the end of the file.
- Every component is documented with `@param`.

```java
  // A path SeatWave serves: a slash, then letters, digits, slashes, and one optional query.
  private static final Pattern PROBE_PATH = Pattern.compile("/[a-z0-9/]*(\\?[a-z]+=[A-Z0-9]+)?");
  private static final Pattern SCENARIO_NAME = Pattern.compile("[a-z][a-z0-9-]{0,19}");
  private static final int MAX_RUNS = 1_000;
  private static final int MAX_TIMEOUT_MILLIS = 60_000;
  private static final int MAX_DELAY_MILLIS = 10_000;
  private static final int MAX_CONNECTIONS = 10_000;
  private static final List<String> TLS_VERSIONS = List.of("TLSv1.3", "TLSv1.2");
```

- `PROBE_PATH` is the pattern of a path SeatWave serves. In the Java string `"/[a-z0-9/]*(\\?[a-z]+=[A-Z0-9]+)?"` the `\\` is one backslash, so the regular expression is `/[a-z0-9/]*(\?[a-z]+=[A-Z0-9]+)?`. Read it left to right: a slash; any number of lower-case letters, digits and slashes; then, optionally, a question mark, lower-case letters, an equals sign, and capital letters or digits. `/events/1/seats?section=A` matches. `/../etc` does not, because `.` is not in any class. That keeps a typo, or a hostile value, from becoming a request for another path.
- `SCENARIO_NAME` allows a lower-case letter, then up to 19 more lower-case letters, digits or hyphens. Scenario names become parts of property keys (`scenario.distant-tls.scheme`), so they must be short and plain.
- The `MAX_...` constants are the top of each allowed range.
- `TLS_VERSIONS` lists the two TLS version names the JDK knows.

```java
  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if a value is outside its range
   */
  public TimingSettings {
    Objects.requireNonNull(tlsDirectory, "tlsDirectory");
    Objects.requireNonNull(probePath, "probePath");
    Objects.requireNonNull(timeouts, "timeouts");
    serverProtocols = List.copyOf(serverProtocols);
    versions = List.copyOf(versions);
    scenarios = List.copyOf(scenarios);
    if (!PROBE_PATH.matcher(probePath).matches()) {
      throw new IllegalArgumentException("not a SeatWave path: " + probePath);
    }
    if (runs < Runs.MINIMUM_RUNS || warmupRuns < 0 || linkMaxConnections < 1) {
      throw new IllegalArgumentException("runs, warm-up runs or the connection limit is too small");
    }
    if (serverProtocols.isEmpty() || versions.isEmpty() || scenarios.isEmpty()) {
      throw new IllegalArgumentException("protocols, versions and scenarios must not be empty");
    }
  }
```

- This compact constructor runs on every `new TimingSettings(...)`. It first checks for null, then makes **defensive copies**: `List.copyOf(...)` returns an unchangeable list, so the caller cannot change the settings afterwards by keeping the old list.
- `PROBE_PATH.matcher(probePath).matches()` checks the whole path.
- `runs < Runs.MINIMUM_RUNS` refuses fewer than 3 runs: with fewer there is no median.
- Empty lists are refused because a run with no scenario or no version would print nothing and look like success.

```java
  /**
   * Reads the settings.
   *
   * @param reader the settings reader
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<TimingSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String directory = reader.text("tls.directory");
    List<String> protocols = reader.wordsIn("tls.server.protocols", TLS_VERSIONS);
    String path = reader.textMatching("probe.path", PROBE_PATH);
    int runs = reader.intBetween("probe.runs", Runs.MINIMUM_RUNS, MAX_RUNS);
    int warmups = reader.intBetween("probe.warmup.runs", 0, MAX_RUNS);
    Duration connect = reader.millisBetween("client.connect.timeout.millis", 1, MAX_TIMEOUT_MILLIS);
    Duration request = reader.millisBetween("client.request.timeout.millis", 1, MAX_TIMEOUT_MILLIS);
    List<HttpClient.Version> versions =
        reader.enumList("client.versions", HttpClient.Version.class);
    int maxConnections = reader.intBetween("link.max.connections", 1, MAX_CONNECTIONS);
    List<Scenario> scenarios = new ArrayList<>();
    for (String name : reader.wordsIn("scenario.names", namesInFile(reader))) {
      String prefix = "scenario." + name + ".";
      scenarios.add(
          new Scenario(
              name,
              reader.enumValue(prefix + "scheme", Scheme.class, Scheme.HTTP),
              reader.millisBetween(prefix + "one.way.delay.millis", 0, MAX_DELAY_MILLIS),
              reader.enumValue(
                  prefix + "handshake.delay", HandshakeDelay.class, HandshakeDelay.NONE)));
    }
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

- `from` returns `Result<TimingSettings, List<String>>`: the settings, or one text line per problem.
- Each `reader.xxx(key, ...)` call returns a value, or records a problem and returns a stand-in. Names show the rule: `intBetween("probe.runs", Runs.MINIMUM_RUNS, MAX_RUNS)` accepts 3 to 1,000. `millisBetween(...)` reads a number of milliseconds as a `Duration`.
- `reader.enumList("client.versions", HttpClient.Version.class)` turns `http_1_1,http_2` into `[HTTP_1_1, HTTP_2]`: it upper-cases each word and calls `Enum.valueOf`. The JDK 25 `HttpClient.Version` has exactly two values, so `http_3` is reported as an unknown value (I tried it: `client.versions has an unknown value: http_3`).
- The `for` loop goes over the scenario names. For each name it builds the key prefix `scenario.<name>.` and reads three keys with it. `reader.enumValue(prefix + "scheme", Scheme.class, Scheme.HTTP)` reads one enum value; the third argument is the harmless stand-in returned when the key is bad.
- Nothing is built from stand-ins: `if (!reader.problems().isEmpty()) return Result.error(...)` comes first. Then `Result.success(new TimingSettings(...))`.

```java
  private static List<String> namesInFile(SettingsReader reader) {
    // Scenario names become parts of keys, so only short lower-case names are allowed; any other
    // name is missing from this list, and wordsIn reports it.
    return List.of(reader.text("scenario.names").split(" *, *")).stream()
        .filter(name -> SCENARIO_NAME.matcher(name).matches())
        .toList();
  }
```

- `reader.text("scenario.names").split(" *, *")` cuts the text at each comma with optional spaces around it. `List.of(array)` makes a list from the array. `.stream().filter(...).toList()` keeps only the names that fit `SCENARIO_NAME`.
- That list is the allow-list for `reader.wordsIn("scenario.names", ...)`: a bad name is missing from it, and `wordsIn` reports it.
- A detail: `scenario.names` is read twice (here and inside `wordsIn`), so a missing key is reported twice (`scenario.names is missing` appears two times). The second line is redundant but harmless.

```java
  /**
   * The two timeouts of every call, drawn from one budget.
   *
   * @param connect the longest wait for a TCP connection
   * @param request the longest wait for a whole response
   */
  public record Timeouts(Duration connect, Duration request) {

    /** Checks the fields. */
    public Timeouts {
      Objects.requireNonNull(connect, "connect");
      Objects.requireNonNull(request, "request");
    }
  }
}
```

- A nested record for the two timeouts: **connect** (longest wait for the TCP connection) and **request** (longest wait for a whole response). The comment "drawn from one budget" means both come from one place in the settings.
- Every network call in this repository has a timeout, because a call without one can wait forever.

### `timing/ReuseProbe.java`

```java
package io.plainround.seatwave.network.timing;

import io.plainround.seatwave.network.report.ReuseSample;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.net.ssl.SSLContext;
```

- `ReuseSample` is the result record of one run. `HttpClient`, `HttpRequest` and `HttpResponse` are the JDK HTTP client types. `SSLContext` holds the trust rules of TLS.

```java
/**
 * The Java timing client: measures a first request on a new connection and a second request on the
 * same, reused connection, with the JDK's {@link HttpClient}.
 *
 * <p>Each run builds its own client on purpose. A client keeps its connections open and reuses
 * them, which is right for service code (Module 5, Section 10) but would hide the cost this probe
 * measures; a new client is the only way the JDK offers to force a new connection. Thread-safe:
 * runs share only the immutable TLS context.
 */
public final class ReuseProbe {

  private static final int OK = 200;
  private static final double NANOS_PER_MILLI = 1_000_000.0;

  private final SSLContext clientContext;
  private final TimingSettings.Timeouts timeouts;
```

- The class comment states the key design choice. **Each run builds its own client on purpose.** A client keeps its connections and reuses them; that is right for service code and would hide the cost this probe measures. A new client is the only way the JDK offers to force a new connection.
- `OK = 200` is the status code that counts.
- The fields hold the TLS context (which trusts only the TEST certificate authority) and the timeouts. Both are immutable, so runs can share them.

```java
  /**
   * Creates the probe.
   *
   * @param clientContext trusts the TEST certificate authority only
   * @param timeouts the connect and request timeouts of every call
   */
  public ReuseProbe(SSLContext clientContext, TimingSettings.Timeouts timeouts) {
    this.clientContext = Objects.requireNonNull(clientContext, "clientContext");
    this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
  }
```

- The constructor stores the two arguments after null checks. `Objects.requireNonNull(x, "x")` returns `x`, so it can be written inline in the assignment.

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
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(version, "version");
    if (runs < 1) {
      throw new IllegalArgumentException("at least one run is needed");
    }
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeouts.request()).GET().build();
    List<ReuseSample> samples = new ArrayList<>(runs);
    for (int runIndex = 0; runIndex < runs; runIndex++) {
      try (HttpClient client =
          HttpClient.newBuilder()
              .version(version)
              .sslContext(clientContext)
              .connectTimeout(timeouts.connect())
              .build()) {
        Timed first = timed(client, request);
        Timed second = timed(client, request);
        samples.add(new ReuseSample(first.elapsed(), second.elapsed(), second.protocol()));
      }
    }
    return List.copyOf(samples);
  }
```

- `measure` takes the address, the version to ask for, and how many runs. It returns one `ReuseSample` per run.
- `throws IOException, InterruptedException`: a network call can fail (`IOException`) and a waiting thread can be interrupted (`InterruptedException`). These are **checked exceptions**: the compiler forces the method to declare them or catch them.
- `HttpRequest.newBuilder(uri).timeout(timeouts.request()).GET().build()` builds a GET request with a request timeout. The same request object is reused, because requests are immutable.
- `try (HttpClient client = HttpClient.newBuilder()...build())` creates a new client and closes it at the end of each loop pass. `HttpClient` is `AutoCloseable` since Java 21.
- `.version(version)` is the HTTP version the client asks for. `.sslContext(clientContext)` is the trust rule for HTTPS. `.connectTimeout(...)` is the longest wait to open a TCP connection.
- The two `timed(...)` calls are the first request (new connection) and the second (the pool now holds an open connection).
- `new ReuseSample(first.elapsed(), second.elapsed(), second.protocol())` stores both times and the protocol the server answered with. `List.copyOf(samples)` returns an unchangeable list.

```java
  private static Timed timed(HttpClient client, HttpRequest request)
      throws IOException, InterruptedException {
    long startNanos = System.nanoTime();
    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
    if (response.statusCode() != OK || response.body().length == 0) {
      // A refusal or an empty body is not a timing of the seat map; stop rather than report it.
      throw new IOException(
          String.format(
              Locale.ROOT,
              "%s answered %d with %d bytes after %.1f ms",
              request.uri(),
              response.statusCode(),
              response.body().length,
              elapsed.toNanos() / NANOS_PER_MILLI));
    }
    return new Timed(elapsed, protocolName(response.version()));
  }
```

- `System.nanoTime()` before and after gives the elapsed time. `client.send(request, HttpResponse.BodyHandlers.ofByteArray())` is a **blocking** call: it returns when the whole body has arrived. A **body handler** says what to do with the body; `ofByteArray()` collects it into a `byte[]`.
- The check refuses a non-200 answer or an empty body: a refusal is not a timing of the seat map. The method throws `IOException` with the address, the status, the size and the time. `%.1f` prints one digit after the decimal point.
- `protocolName(response.version())` is what the server really used.

```java
  private static String protocolName(HttpClient.Version version) {
    return switch (version) {
      case HTTP_1_1 -> "HTTP/1.1";
      case HTTP_2 -> "HTTP/2";
    };
  }

  private record Timed(Duration elapsed, String protocol) {}
}
```

- `protocolName` is a switch expression over the two versions. It has no `default`: the compiler sees that `HttpClient.Version` has only `HTTP_1_1` and `HTTP_2` in Java 25. If a later JDK adds `HTTP_3`, this switch stops compiling and a person must decide what to print. (JDK 26 is reported to add HTTP/3; I have only JDK 25 here and could not check that.)
- `private record Timed(Duration elapsed, String protocol) {}` is a tiny private pair.

### `timing/TimingCli.java`

```java
package io.plainround.seatwave.network.timing;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import io.plainround.seatwave.network.link.DelayLink;
import io.plainround.seatwave.network.link.LinkSettings;
import io.plainround.seatwave.network.report.ReuseSample;
import io.plainround.seatwave.network.report.ReuseTiming;
import io.plainround.seatwave.network.report.TimingReport;
import io.plainround.seatwave.network.server.SeatWaveServer;
import io.plainround.seatwave.network.tls.TlsMaterial;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
```

- The imports show the whole cast: settings reader, link, report classes, server, TLS material, and the JDK HTTP client. `URISyntaxException` is thrown by the `URI` constructor on a bad address.

```java
/**
 * Command-line entry point of the Java timing client. It starts SeatWave over HTTP and HTTPS on the
 * loopback address, puts a {@link DelayLink} in front of the server for each scenario, and prints
 * the median time of a request on a new connection and on a reused one. Nothing leaves this
 * machine. Exit status 2 means bad usage or settings.
 */
public final class TimingCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  // Any free port: the servers and links live only as long as this run.
  private static final int ANY_FREE_PORT = 0;

  private TimingCli() {}
```

- The CLI starts SeatWave over HTTP and HTTPS and puts a `DelayLink` in front of the server for each scenario. Nothing leaves the machine.
- Exit status 2 is the shell convention for "used incorrectly". `ANY_FREE_PORT = 0` asks the operating system for any free port, since everything lives only as long as this run.

```java
  /**
   * Prints the keep-alive table.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while a request is in flight
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: timing <timing.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (TimingSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<TimingSettings, List<String>>(TimingSettings settings) ->
            System.out.print(run(settings));
        case Result.Error<TimingSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid setting: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException | GeneralSecurityException | URISyntaxException failed) {
      System.err.println("the timing run failed: " + failed.getMessage());
      System.exit(EXIT_BAD_USAGE);
    }
  }
```

- The `main` method takes the settings file as its only argument, checks the count, reads the file with `SettingsReader.load`, and parses it with `TimingSettings.from`.
- The `switch` is a pattern-matching switch over `Result`. On success it prints what `run` returns, and `System.out.print(...)` writes it without adding a line break. On error it prints one `invalid setting:` line per problem and exits with status 2.
- `catch (IOException | GeneralSecurityException | URISyntaxException failed)` handles an unreadable file, a bad certificate file, a failed request, or a bad address. All exit with status 2 and a message. (Exit status 2 here also covers a failed request, which is not strictly "bad usage".)

```java
  static String run(TimingSettings settings)
      throws IOException, GeneralSecurityException, InterruptedException, URISyntaxException {
    Path directory = settings.tlsDirectory();
    ReuseProbe probe =
        new ReuseProbe(
            TlsMaterial.clientContext(directory.resolve("seatwave-test-ca.pem")),
            settings.timeouts());
    List<ReuseTiming> rows = new ArrayList<>();
    try (SeatWaveServer plain = SeatWaveServer.startPlain(ANY_FREE_PORT);
        SeatWaveServer tls =
            SeatWaveServer.startTls(
                ANY_FREE_PORT,
                TlsMaterial.serverContext(
                    directory.resolve("seatwave-valid.p12"), directory.resolve("store.password")),
                settings.serverProtocols())) {
      for (Scenario scenario : settings.scenarios()) {
        int upstreamPort =
            switch (scenario.scheme()) {
              case HTTP -> plain.port();
              case HTTPS -> tls.port();
            };
        for (HttpClient.Version version : settings.versions()) {
          rows.add(measure(probe, settings, scenario, upstreamPort, version));
        }
      }
    }
    return header(settings) + TimingReport.renderReuse(rows);
  }
```

- `run` has no `public` or `private`: it is **package-private**, visible only inside the `timing` package. Tests in the same package can call it.
- `TlsMaterial.clientContext(...)` builds a client TLS context that trusts only the TEST CA. `TlsMaterial.serverContext(...)` builds the server's context from the valid key store and the password file.
- `try (SeatWaveServer plain = ...; SeatWaveServer tls = ...)` starts both servers and closes both at the end.
- For each scenario, a `switch` expression picks the port to forward to: the plain server's port for `HTTP`, the TLS server's port for `HTTPS`. For each requested version, it adds one row.
- `header(settings) + TimingReport.renderReuse(rows)` returns the full text.

```java
  private static ReuseTiming measure(
      ReuseProbe probe,
      TimingSettings settings,
      Scenario scenario,
      int upstreamPort,
      HttpClient.Version version)
      throws IOException, InterruptedException, URISyntaxException {
    LinkSettings linkSettings =
        new LinkSettings(
            scenario.oneWayDelay(), scenario.handshakeDelay(), settings.linkMaxConnections());
    try (DelayLink link = DelayLink.start(ANY_FREE_PORT, upstreamPort, linkSettings)) {
      URI uri = uri(scenario.scheme(), link.port(), settings.probePath());
      if (settings.warmupRuns() > 0) {
        probe.measure(uri, version, settings.warmupRuns());
      }
      // Connections are counted after the warm-up, so the count belongs to the measured runs.
      int openedBefore = link.connectionsAccepted();
      List<ReuseSample> samples = probe.measure(uri, version, settings.runs());
      return new ReuseTiming(
          scenario.name(), version.name(), samples, link.connectionsAccepted() - openedBefore);
    }
  }
```

- `measure` builds the `LinkSettings` for the scenario and starts a link in a try-with-resources, so the link closes at the end.
- `uri(...)` builds the address of the **link's** port, not of the server: the client talks to the link, and the link talks to the server.
- Warm-up runs happen first (their results are dropped). The count of connections is read after the warm-up, so it belongs to the measured runs only.
- `link.connectionsAccepted() - openedBefore` is the number of connections opened by the measured runs.

```java
  private static URI uri(Scheme scheme, int port, String probePath) throws URISyntaxException {
    String[] pathAndQuery = probePath.split("\\?", 2);
    // The URI constructor quotes each part itself, so no part is glued into a URL by hand.
    return new URI(
        scheme.name().toLowerCase(Locale.ROOT),
        null,
        InetAddress.getLoopbackAddress().getHostAddress(),
        port,
        pathAndQuery[0],
        pathAndQuery.length > 1 ? pathAndQuery[1] : null,
        null);
  }
```

- `probePath.split("\\?", 2)` cuts the path from the query at the first question mark, into at most two parts.
- `new URI(scheme, userInfo, host, port, path, query, fragment)` is the seven-argument constructor. It **quotes** each part itself, so no part is glued into a URL by hand. `null` means "none" for user info and fragment. `InetAddress.getLoopbackAddress().getHostAddress()` is the text `127.0.0.1`.
- `pathAndQuery.length > 1 ? pathAndQuery[1] : null` is a **conditional expression**: use the query if there is one, else null.

```java
  private static String header(TimingSettings settings) {
    return String.format(
        Locale.ROOT,
        "Java %s on %s %s; SeatWave on the JDK HTTP server at %s; every request: GET %s%n"
            + "%d measured runs per row after %d warm-up runs; each run = a new client, one"
            + " request on a new connection, then one on the same connection%n%n",
        Runtime.version(),
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
        InetAddress.getLoopbackAddress().getHostAddress(),
        settings.probePath(),
        settings.runs(),
        settings.warmupRuns());
  }
}
```

- `Runtime.version()` is the running Java version (it printed `25.0.2+10-LTS`). `System.getProperty("os.name")` and `os.arch` print the machine (`Mac OS X` and `aarch64` here). The header says which loopback address and which request every row uses, plus the number of runs and warm-ups.
- The string is split with `+` to fit the line; Java joins constants at compile time. `%n` is a line break, `%d` a whole number, `%s` a text.

## Try it yourself

All commands run in the repository folder and use only the loopback address. Timings differ on every run and on every machine; compare the shape, not the digits.

**1. The main table.**

```text
./gradlew -q timing --args='config/timing.properties'
```

One real run on an Apple M2 Max Mac with Temurin 25.0.2 (it took 13 seconds in all):

```text
Java 25.0.2+10-LTS on Mac OS X aarch64; SeatWave on the JDK HTTP server at 127.0.0.1; every request: GET /events/1/seats?section=A
7 measured runs per row after 3 warm-up runs; each run = a new client, one request on a new connection, then one on the same connection

scenario       requested  answered   runs  new conn p50 ms  reused p50 ms  saved by reuse ms  connections opened
simple         HTTP_1_1   HTTP/1.1      7             1.54           1.13               0.41                   7
simple         HTTP_2     HTTP/1.1      7             1.38           1.11               0.27                   7
local-tls      HTTP_1_1   HTTP/1.1      7            10.59           2.31               8.28                   7
local-tls      HTTP_2     HTTP/1.1      7             7.62           1.73               5.89                   7
distant-http   HTTP_1_1   HTTP/1.1      7           164.32          83.50              80.82                   7
distant-http   HTTP_2     HTTP/1.1      7           165.10          83.94              81.16                   7
distant-tls    HTTP_1_1   HTTP/1.1      7           249.79          82.20             167.59                   7
distant-tls    HTTP_2     HTTP/1.1      7           248.59          81.93             166.66                   7
```

Look for three things. The "answered" column says `HTTP/1.1` even where `HTTP_2` was requested. The distant rows sit near 83 ms (1 round trip), 164 ms (2) and 250 ms (3). "connections opened" equals "runs".

**2. The same client at three distances (Lab 2).**

```text
./gradlew -q timing --args='config/lab2-distances.properties'
```

```text
scenario       requested  answered   runs  new conn p50 ms  reused p50 ms  saved by reuse ms  connections opened
rtt-20ms       HTTP_1_1   HTTP/1.1      7            70.96          23.02              47.94                   7
rtt-40ms       HTTP_1_1   HTTP/1.1      7           139.86          45.17              94.69                   7
rtt-80ms       HTTP_1_1   HTTP/1.1      7           256.61          84.97             171.63                   7
```

(The header lines are the same as above and left out.) A new HTTPS connection costs about three round trips, and reuse costs about one, so the saving is about two round trips at every distance.

**3. A settings file with mistakes.** Make a copy of the settings with the key `scenario.names` deleted:

```text
grep -v '^scenario.names' config/timing.properties > build/bad-timing.properties
./gradlew -q timing --args=build/bad-timing.properties
```

```text
invalid setting: scenario.names is missing
invalid setting: scenario.names is missing
```

The same line appears twice (see the note on `namesInFile`), followed by Gradle's own failure text. The program exited with status 2.

**4. A path or a version the code refuses.** Change two keys in another copy and run it. (`build/` is ignored by git, so the copies leave the repository clean; delete them afterwards.)

```text
sed -e 's/^client.versions=.*/client.versions=http_3/' -e 's/^probe.path=.*/probe.path=\/..\/etc/' config/timing.properties > build/bad2.properties
./gradlew -q timing --args=build/bad2.properties
```

```text
invalid setting: probe.path must match /[a-z0-9/]*(\?[a-z]+=[A-Z0-9]+)?, but was /../etc
invalid setting: client.versions has an unknown value: http_3
```

## Mistakes people make

- **Reusing one client for every run.** The second run would find an open connection and the "new connection" time would be wrong. This probe builds a new client per run.
- **Skipping warm-up.** The first runs include class loading and JIT time, which are not network time. Three warm-up runs are thrown away.
- **Reporting the mean or one run.** One slow run drags a mean. The median of at least 3 runs is the rule (`Runs.MINIMUM_RUNS`).
- **Believing the version you asked for.** Asking for HTTP/2 does not give HTTP/2 from a server that cannot speak it. The table shows the answered protocol.
- **A call with no timeout.** The client has a connect timeout and the request has a request timeout.
- **Gluing a URL by hand.** The code builds the address with the multi-argument `URI` constructor, which quotes each part.
- **Counting connections including warm-up.** The count is read after the warm-up.

## Check yourself

1. Why does each run build a new `HttpClient`? (A client reuses open connections. A new client is the only way to force a new connection for the first request.)
2. What does the second request in a run show, and why is it faster? (The cost of a request on an open connection. The TCP and TLS handshakes were already paid by the first request.)
3. With a link of 40 ms each way, about how long should a new HTTPS connection's first request take, and why? (About 240 ms plus a little work: three round trips of 80 ms for TCP, TLS 1.3 and the request.)
4. What does the "answered" column say when `HTTP_2` was requested, and why? (`HTTP/1.1`, because the JDK server does not offer HTTP/2.)
5. What does `connections opened = 7` tell you for 7 runs? (Each run opened exactly one connection, so the second request reused it.)

## Further reading

Official documentation, for the JDK this repository pins (Java 25):

- `java.net.http.HttpClient`: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/HttpClient.html
- `java.net.http.HttpRequest` and `HttpResponse`: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/package-summary.html
- `java.net.URI`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/URI.html
- HTTP Client API (JEP 321): https://openjdk.org/jeps/321
- Records (JEP 395): https://openjdk.org/jeps/395
- Pattern matching for `switch` (JEP 441): https://openjdk.org/jeps/441

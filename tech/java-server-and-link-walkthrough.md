# The `server` and `link` packages, line by line

## What it is

This file reads the Java code of two packages, one line at a time. The `server` package (three files: `SeatMapJson.java`, `SeatWaveServer.java`, `ServeCli.java`) is SeatWave itself: a small web server. The `link` package (three files: `DelayLink.java`, `LinkSettings.java`, `HandshakeDelay.java`) is a pretend long-distance cable that sits between a client and the server and makes every byte wait. Both are plain Java with no framework.

A **web server** is a program that waits for requests from other programs and answers each one. A **package** is a named group of Java classes kept in one folder. The packages here live under `src/main/java/io/plainround/seatwave/network/`.

## Why this repository uses it

Module 5 of the course asks where the time goes before the first byte of a response arrives. To measure that, the repository needs a real server to measure (the `server` package) and a way to make one machine behave as if the fan were 80 ms away (the `link` package). The JDK ships a web server and everything the link needs, so no library is added.

## Where it appears

| File | What it does |
|---|---|
| `server/SeatMapJson.java` | Builds the two JSON bodies SeatWave answers with: the event list and the seat map of one section. |
| `server/SeatWaveServer.java` | Starts the JDK web server over plain HTTP or over HTTPS and answers `GET /events`, `GET /events/1/seats?section=A` and `GET /health`. |
| `server/ServeCli.java` | The `serve` command: starts three listeners on the loopback address and keeps them up for a fixed time. |
| `link/DelayLink.java` | A forwarder that holds every chunk of bytes for a fixed time in each direction. |
| `link/LinkSettings.java` | The checked settings of one link: the delay, the handshake rule and the connection limit. |
| `link/HandshakeDelay.java` | An enum with two values: charge the TCP handshake's round trip to the first bytes, or do not. |

Read `java.md` first for the language words (record, enum, `switch`, try-with-resources) and `java-networking-and-tls.md` for the network words (port, socket, TCP, HTTP, TLS). This file explains each line again where it is used.

## How it works underneath

**One request, end to end.** A client opens a TCP connection to port 8080. The JDK server accepts it. The client writes the text `GET /events/1/seats?section=A HTTP/1.1`, some header lines and an empty line. The server reads that text, calls the `handle` method of `SeatWaveServer`, and writes back a status line, headers and the JSON body. The connection stays open, so the next request can reuse it (keep-alive).

**Three listeners, one machine.** `ServeCli` starts three things at once, each on `127.0.0.1` (the loopback address, which never leaves the machine):

1. Port 8080: SeatWave over plain HTTP.
2. Port 8443: SeatWave over HTTPS (HTTP inside a TLS tunnel).
3. Port 8444: a `DelayLink` that listens, and for each client connection opens a second connection to port 8443. Every chunk of bytes that passes waits 40 ms in each direction. A client at 8444 sees an 80 ms round trip.

```text
client --> 8444 [DelayLink: waits 40 ms] --> 8443 [HTTPS SeatWave]
client --> 8443 [HTTPS SeatWave]                      (no extra wait)
client --> 8080 [HTTP SeatWave]                       (no extra wait)
```

**Threads in the link.** A **thread** is one line of execution inside a program. The link gives every connection five **virtual threads** (cheap threads managed by the JDK, a Java 21 feature): one to run the connection, two to read (one per direction) and two to write (one per direction). A reading thread copies bytes into a queue with a due time. A writing thread takes them out in order, waits until the due time, and writes them to the other side. That wait is the delay.

**Nothing here is a mock.** The server is the real JDK server, the sockets are real operating-system sockets, and the TLS is the real JDK TLS. Only the distance is faked.

## Walkthrough of this repository's use

### `server/SeatMapJson.java`

The file starts with the package line and the imports.

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

- `package ...server;` says this class lives in the `server` package. The folder path must match.
- `StandardCharsets` names text encodings. UTF-8 is the usual one for JSON.
- `ArrayList`, `Collections` and `List` are the growable list, a helper class with sorting, and the list type.
- `Locale` says how to format numbers; `Locale.ROOT` means no country rules.
- `Objects` has `requireNonNull`, a null check. `Pattern` is a regular expression (a text pattern).

Now the class comment and the constants.

```java
/**
 * The two read bodies of SeatWave version 0 (Module 1), byte for byte: the event list and the seat
 * map of one section, every seat available.
 *
 * <p>Module 1 read these from its database. This module studies the bytes on the wire, not the
 * database, so the bodies are built in memory once; their size and shape are what matter here.
 * Thread-safe: it holds no state.
 */
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

- `/** ... */` is a **Javadoc** comment: a comment that tools turn into documentation. `<p>` starts a paragraph.
- `public final class SeatMapJson` is a class nobody can extend (`final`). `public` means any class can use it.
- `public static final int EVENT_ID = 1;` is a constant: `static` (one copy for the class), `final` (never changes). `1_000` style numbers use an underscore only to be readable (Java 7).
- `1_796_761_251_783L` ends in `L` because it does not fit in an `int`; it is a `long`. It is a time in **epoch milliseconds**: milliseconds since 1 January 1970 UTC. Module 1 printed this start time, so the body stays byte for byte the same.
- `Pattern.compile("[A-Z]")` builds the pattern once. `[A-Z]` means one capital letter.
- `private SeatMapJson() {}` is a private constructor with an empty body. It stops anyone from writing `new SeatMapJson()`: the class is only a bag of static methods.

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

- `isValidSection` is an **allow-list** check: instead of listing bad input, it describes the only good input (exactly one capital letter) and refuses everything else.
- `Objects.requireNonNull(section, "section")` throws `NullPointerException` with the message `section` if the argument is null. A null here is a bug in the caller, not bad user input.
- `SECTION.matcher(section).matches()` is true only if the whole text matches the pattern. (`find()` would accept a match anywhere in the text.)

```java
  /**
   * Returns the event list.
   *
   * @return the UTF-8 bytes of the JSON array with Module 1's one event
   */
  public static byte[] events() {
    String json =
        String.format(
            Locale.ROOT,
            "[{\"eventId\":%d,\"name\":\"Arena Night\",\"startsAt\":%d}]",
            EVENT_ID,
            EVENT_STARTS_AT_EPOCH_MILLIS);
    return json.getBytes(StandardCharsets.UTF_8);
  }
```

- `byte[]` is an array of bytes. HTTP carries bytes, not characters, so the method returns bytes.
- `String.format(Locale.ROOT, "...%d...%d...", a, b)` fills `%d` (a whole number) with each argument. Inside a Java string, `\"` is one double-quote character.
- The result looks like `[{"eventId":1,"name":"Arena Night","startsAt":1796761251783}]`: a JSON array with one event.
- `json.getBytes(StandardCharsets.UTF_8)` turns the text into bytes in UTF-8.

```java
  /**
   * Returns the seat map of one section, every seat available.
   *
   * @param section one capital letter
   * @return the UTF-8 bytes of the JSON array, in Module 1's order (labels sorted as text)
   * @throws IllegalArgumentException if the section is not one capital letter
   */
  public static byte[] seats(String section) {
    if (!isValidSection(section)) {
      throw new IllegalArgumentException("a section is one capital letter, but was " + section);
    }
    List<String> labels = new ArrayList<>(SEATS_PER_SECTION);
    for (int seatNumber = 1; seatNumber <= SEATS_PER_SECTION; seatNumber++) {
      labels.add(section + "-" + seatNumber);
    }
    // Module 1's database returned seats ordered by their text label: A-1, A-10, A-100, A-1000,
    // A-101.
    Collections.sort(labels);
    StringBuilder json = new StringBuilder("[");
    for (String label : labels) {
      if (json.length() > 1) {
        json.append(',');
      }
      json.append("{\"seat\":\"").append(label).append("\",\"state\":\"available\"}");
    }
    return json.append(']').toString().getBytes(StandardCharsets.UTF_8);
  }
```

- `throws IllegalArgumentException` in the Javadoc says what happens on a bad section. `IllegalArgumentException` is an **unchecked** exception: the compiler does not force callers to catch it.
- `new ArrayList<>(SEATS_PER_SECTION)` reserves room for 1,000 items. The `<>` (diamond) lets the compiler fill in `String`.
- The `for` loop counts `seatNumber` from 1 to 1,000. `section + "-" + seatNumber` joins text and a number into `A-1`, `A-2`, ... `A-1000`.
- `Collections.sort(labels)` sorts as text, so `A-10` comes before `A-2`. Module 1's database returned seats in that order, and the bytes must match.
- `StringBuilder` builds a long text without making a new `String` for every append. The `[` starts the array. `json.length() > 1` is false only for the first seat, so no comma is written before it.
- `json.append(']').toString().getBytes(...)` closes the array and returns the bytes. The whole body is 36,894 bytes for any section letter.

### `server/SeatWaveServer.java`

```java
package io.plainround.seatwave.network.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
```

- `com.sun.net.httpserver` is the JDK's built-in HTTP server package (module `jdk.httpserver`). `HttpServer` serves plain HTTP; `HttpsServer` serves HTTPS; `HttpExchange` is one request and its reply; `HttpsConfigurator` and `HttpsParameters` set the TLS rules.
- `InetAddress` is an IP address; `InetSocketAddress` is an IP address plus a port; `URI` is a parsed web address.
- `ExecutorService` and `Executors` run tasks on threads.
- `SSLContext` holds the keys and trust rules of TLS; `SSLParameters` holds choices such as the allowed TLS versions.

```java
/**
 * SeatWave's read endpoints of Module 1 ({@code GET /events}, {@code GET /events/1/seats?section=A}
 * and {@code GET /health}) on the JDK's own HTTP server, over plain HTTP or over HTTPS.
 *
 * <p>The server listens on the loopback address only, so nothing outside this machine can reach it.
 * It speaks HTTP/1.1 with keep-alive, which is all the JDK server offers: it does not negotiate
 * HTTP/2. Every response carries {@code Server-Timing: app;dur=<ms>}, the time the handler spent,
 * so a client can tell server time from network time (Module 1, Section 6a).
 *
 * <p>Each exchange runs on its own virtual thread. The JDK server handles one exchange per
 * connection at a time, so the number of open connections bounds the threads; {@link ServeCli} caps
 * it with the JDK's {@code jdk.httpserver.maxConnections} property. Thread-safe: handlers share no
 * mutable state.
 */
public final class SeatWaveServer implements AutoCloseable {
```

- `implements AutoCloseable` means the class has a `close()` method, so it can be used in `try (...)` and is closed automatically at the end of the block.
- `{@code ...}` in Javadoc prints the text as code; `{@link ServeCli}` makes a link.
- The comment says the JDK server serves **one exchange per connection at a time** and speaks only HTTP/1.1.

```java
  // 0 asks the operating system for its default accept backlog.
  private static final int SYSTEM_DEFAULT_BACKLOG = 0;
  // 0 lets stop() return at once; a lab server has no request worth waiting for at shutdown.
  private static final int STOP_DELAY_SECONDS = 0;
  private static final String SEATS_PATH = "/events/" + SeatMapJson.EVENT_ID + "/seats";
  private static final String SECTION_QUERY_PREFIX = "section=";
  private static final int OK = 200;
  private static final int BAD_REQUEST = 400;
  private static final int NOT_FOUND = 404;
  private static final int METHOD_NOT_ALLOWED = 405;
  // sendResponseHeaders takes -1 for "no body", which is what a HEAD request must get.
  private static final long NO_BODY = -1;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
```

- A **backlog** is the length of the queue of connections the operating system has completed but the program has not yet accepted. `0` asks for the system default.
- `STOP_DELAY_SECONDS = 0` makes `stop` return at once.
- `SEATS_PATH = "/events/" + SeatMapJson.EVENT_ID + "/seats"` is joined at compile time into `/events/1/seats`.
- 200, 400, 404 and 405 are HTTP status codes: OK, Bad Request, Not Found, Method Not Allowed.
- `NO_BODY = -1` is what `sendResponseHeaders` needs to mean "no body" (a HEAD reply).
- `1_000_000.0` converts nanoseconds to milliseconds (a `double`, so the division keeps fractions).

```java
  private final HttpServer server;
  private final ExecutorService executor;
  private final String scheme;

  private SeatWaveServer(HttpServer server, String scheme) {
    this.server = server;
    this.scheme = scheme;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }
```

- Three `private final` fields: the server, the executor, and the text `http` or `https`.
- The constructor is `private`: only the two `start...` methods below can make one. That keeps "make it and start it" in one place.
- `Executors.newVirtualThreadPerTaskExecutor()` makes an executor that starts one new virtual thread for each task.
- `server.setExecutor(executor)` tells the server to run each exchange on that executor.
- `server.createContext("/", this::handle)` maps every path under `/` to the method `handle`. `this::handle` is a **method reference**: a short way to pass an existing method as the handler.
- `server.start()` starts the server's own background thread that accepts connections.

```java
  /**
   * Starts SeatWave over plain HTTP: SIMPLE, as Module 1 ran it.
   *
   * @param port the port, or 0 for any free port
   * @return the running server
   * @throws IOException if the port cannot be bound
   */
  public static SeatWaveServer startPlain(int port) throws IOException {
    return new SeatWaveServer(HttpServer.create(loopback(port), SYSTEM_DEFAULT_BACKLOG), "http");
  }
```

- `startPlain` is a `static` factory method: it makes the object for you.
- `HttpServer.create(address, backlog)` creates the server and binds the port at once. If the port is taken it throws `IOException`; the method declares `throws IOException`.
- `loopback(port)` is defined at the end of the file. Port 0 means "any free port"; the operating system picks one and `port()` reads it back.

```java
  /**
   * Starts SeatWave over HTTPS with the given server key and the given TLS versions only.
   *
   * @param port the port, or 0 for any free port
   * @param context holds the server's key and certificate chain
   * @param protocols the TLS versions to accept, for example {@code TLSv1.3}
   * @return the running server
   * @throws IOException if the port cannot be bound
   */
  public static SeatWaveServer startTls(int port, SSLContext context, List<String> protocols)
      throws IOException {
    Objects.requireNonNull(context, "context");
    String[] accepted = List.copyOf(protocols).toArray(String[]::new);
    if (accepted.length == 0) {
      throw new IllegalArgumentException("at least one TLS version is needed");
    }
    HttpsServer https = HttpsServer.create(loopback(port), SYSTEM_DEFAULT_BACKLOG);
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
    return new SeatWaveServer(https, "https");
  }
```

- `List.copyOf(protocols).toArray(String[]::new)` makes an unchangeable copy of the list and then a `String` array. `String[]::new` is a method reference that creates the array of the right size.
- An empty array would allow no TLS version, so the method refuses it with `IllegalArgumentException`.
- `HttpsServer.create(...)` is the HTTPS twin of `HttpServer.create`.
- `new HttpsConfigurator(context) { ... }` is an **anonymous class**: a subclass with no name, written where it is used. Its `configure` method is called by the server for every new connection, before the TLS handshake.
- `getSSLContext().getDefaultSSLParameters()` starts from the defaults. `ssl.setProtocols(accepted)` replaces the version list with only the allowed versions. `parameters.setSSLParameters(ssl)` hands the choice to the server.
- The effect: a client that offers only TLS 1.2 to a server that accepts only TLS 1.3 is refused during the handshake (`java-tls-walkthrough.md` shows it).
- `@Override` tells the compiler this method replaces one of the parent class. A typo in the name then fails the build.

```java
  /**
   * Returns the port the server listens on.
   *
   * @return the port
   */
  public int port() {
    return server.getAddress().getPort();
  }

  /**
   * Returns the address of the server's root, with the loopback address as host.
   *
   * @return for example {@code https://127.0.0.1:8443/}
   */
  public URI baseUri() {
    return URI.create(
        scheme + "://" + server.getAddress().getAddress().getHostAddress() + ":" + port() + "/");
  }

  /** Stops the server and its executor. */
  @Override
  public void close() {
    server.stop(STOP_DELAY_SECONDS);
    executor.close();
  }
```

- `port()` asks the bound server for its address and takes the port. This is how a test learns which free port it got.
- `baseUri()` builds `http://127.0.0.1:PORT/` or `https://...`. `getHostAddress()` gives the numeric text `127.0.0.1`. The loopback address is always `127.0.0.1` here.
- `close()` stops the server (`stop(0)`) and then closes the executor, which waits for running tasks.

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

- `handle` runs once per request, on a virtual thread.
- `System.nanoTime()` is a clock for measuring durations: it only goes forward and its zero is arbitrary. The start is saved to compute `Server-Timing` later.
- `try (exchange)` uses an already-existing variable as a try-with-resources resource (Java 9). At the end of the block `exchange.close()` ends the exchange, even on an error.
- `getRequestMethod()` is the method text: `GET`, `HEAD`, `DELETE` or any other. A **HEAD** request asks for the headers of a GET without the body.
- Anything but GET and HEAD gets status 405 and an `Allow` header that lists what is allowed.
- `Response response = route(exchange.getRequestURI())` picks the answer from the path and query. The record at the end of the file holds status and body.

```java
  private static Response route(URI uri) {
    String path = uri.getRawPath();
    String query = uri.getRawQuery();
    if ("/events".equals(path) && query == null) {
      return new Response(OK, SeatMapJson.events());
    }
    if ("/health".equals(path) && query == null) {
      return new Response(OK, "{\"status\":\"up\"}".getBytes(StandardCharsets.UTF_8));
    }
    if (SEATS_PATH.equals(path)) {
      // Allow-list: exactly one parameter, section, with one capital letter. Anything else is
      // refused before any work.
      if (query == null || !query.startsWith(SECTION_QUERY_PREFIX)) {
        return new Response(BAD_REQUEST, problem("section must be one capital letter"));
      }
      String section = query.substring(SECTION_QUERY_PREFIX.length());
      if (!SeatMapJson.isValidSection(section)) {
        return new Response(BAD_REQUEST, problem("section must be one capital letter"));
      }
      return new Response(OK, SeatMapJson.seats(section));
    }
    return new Response(NOT_FOUND, problem("no such resource"));
  }
```

- `getRawPath()` and `getRawQuery()` give the text as the client sent it, without decoding `%41` into `A`. That avoids a path that decodes into something else than it looked like.
- `"/events".equals(path) && query == null`: the literal first, so a null `path` cannot crash it. `&&` is "and" and stops early.
- The health body is the fixed text `{"status":"up"}`.
- For the seat map: the query must start with `section=`, and the part after it must be exactly one capital letter. Anything else is a 400 before any work is done.
- Any other path is 404.

```java
  private static void reply(
      HttpExchange exchange, long startNanos, int status, byte[] body, boolean isHead)
      throws IOException {
    exchange
        .getResponseHeaders()
        .set("Content-Type", status == OK ? "application/json" : "application/problem+json");
    double serverMillis = (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
    exchange
        .getResponseHeaders()
        .set("Server-Timing", String.format(Locale.ROOT, "app;dur=%.2f", serverMillis));
    if (isHead) {
      exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
      exchange.sendResponseHeaders(status, NO_BODY);
      return;
    }
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
```

- `Content-Type` is `application/json` for a 200 and `application/problem+json` for errors (the media type of RFC 9457 problem details).
- `Server-Timing: app;dur=0.37` is a standard response header that says how long the server spent. `%.2f` prints two digits after the point. The measured time is the handler's time only, so a client can subtract it from its own total and be left with network time.
- For HEAD, the code sets `Content-Length` to the size the GET would have and calls `sendResponseHeaders(status, -1)`: headers only.
- For GET, `sendResponseHeaders(status, body.length)` promises an exact length. (A length of 0 would mean chunked encoding, which no body here needs.) Then the body is written to `getResponseBody()`, and the `try (OutputStream out = ...)` closes it, which finishes the reply.

```java
  private static byte[] problem(String detail) {
    // One error shape for every refusal: problem details (RFC 9457), with no internal names.
    return ("{\"type\":\"about:blank\",\"detail\":\"" + detail + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static InetSocketAddress loopback(int port) {
    return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
  }

  private record Response(int status, byte[] body) {}
}
```

- `problem` builds the one error shape: `{"type":"about:blank","detail":"..."}`. The detail text is chosen by the code and never includes anything from the client, so a bad request cannot inject text into the reply.
- `loopback(port)` is the single place that picks the address: `InetAddress.getLoopbackAddress()`. Nothing in this class can listen on another interface.
- `private record Response(int status, byte[] body) {}` is a **record**: a short way to declare a class with two read-only fields and accessor methods `status()` and `body()`.

### `server/ServeCli.java`

```java
package io.plainround.seatwave.network.server;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import io.plainround.seatwave.network.link.DelayLink;
import io.plainround.seatwave.network.link.HandshakeDelay;
import io.plainround.seatwave.network.link.LinkSettings;
import io.plainround.seatwave.network.tls.TlsMaterial;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
```

- `Result` comes from the library `result4j` (see `result4j.md`). `SettingsReader`, `DelayLink`, `HandshakeDelay`, `LinkSettings` and `TlsMaterial` are this repository's own classes.
- `Path` is a file path. `GeneralSecurityException` is the parent of the exceptions the security classes throw. `Duration` is a length of time.

```java
/**
 * Command-line entry point that keeps SeatWave running for a fixed time, so curl and openssl can
 * measure it: plain HTTP, HTTPS, and HTTPS behind a link that adds a round-trip time. Every port is
 * on the loopback address. Exit status 2 means bad usage, bad settings or a port in use.
 */
public final class ServeCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  private static final int MAX_PORT = 65_535;
  // The first port an ordinary user may bind; lower ports need administrator rights.
  private static final int FIRST_USER_PORT = 1_024;
  private static final int MAX_SECONDS = 3_600;
  private static final int MAX_DELAY_MILLIS = 10_000;
  private static final int MAX_CONNECTIONS = 10_000;
  private static final List<String> TLS_VERSIONS = List.of("TLSv1.3", "TLSv1.2");

  private ServeCli() {}
```

- **CLI** means command-line interface. The class has a `main` method: the JVM starts a program by calling it.
- Exit status 2 follows the shell convention for "used incorrectly". 65,535 is the largest port number (16 bits). Ports below 1,024 have traditionally needed administrator rights, so they are refused.
- `List.of("TLSv1.3", "TLSv1.2")` is a fixed list of the two version names the JDK accepts.

```java
  /**
   * Serves until the configured time is up.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while serving
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: serve <serve.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
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

- `String[] arguments` holds what the user typed after the command name. Gradle's `--args='config/serve.properties'` becomes `arguments[0]`.
- `System.err` is the error stream, shown next to normal output but separate. `System.exit(n)` ends the whole program with status `n`.
- `SettingsReader.load(Path.of(arguments[0]))` reads the properties file. `read(reader)` turns it into a `Result`.
- `switch (read(reader)) { case ... -> ... }` is a **pattern-matching switch** over a sealed type (Java 21). `Result.Success<Settings, List<String>>(Settings settings)` is a **record pattern**: it checks that the result is a success and pulls the value out into the variable `settings`. The second case does the same for the error and prints one line per problem.
- The compiler knows `Result` has exactly two cases, so there is no `default`.
- `catch (IOException | GeneralSecurityException failed)` catches either one. Both end with exit status 2 and a message.

```java
  private static void serve(Settings settings)
      throws IOException, GeneralSecurityException, InterruptedException {
    // The JDK server reads its connection limit from this property when it first starts, so it
    // must be set before the first server (failure mode 5: a cap that refuses, not a hang).
    System.setProperty(
        "jdk.httpserver.maxConnections", Integer.toString(settings.maxConnections()));
    Path directory = Path.of(settings.tlsDirectory());
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
      System.out.printf(
          Locale.ROOT,
          "plain HTTP: %s%nHTTPS:      %s%ndistant:    https://127.0.0.1:%d/ (adds %d ms of"
              + " round-trip time)%nserving for %d s; TLS versions %s; at most %d connections each%n",
          plain.baseUri(),
          tls.baseUri(),
          distant.port(),
          settings.oneWayDelay().multipliedBy(2).toMillis(),
          settings.duration().toSeconds(),
          String.join(", ", settings.protocols()),
          settings.maxConnections());
      System.out.flush();
      // A bounded run: the servers stop by themselves, so a forgotten terminal holds no port.
      Thread.sleep(settings.duration());
    }
  }
```

- `System.setProperty("jdk.httpserver.maxConnections", ...)` sets a JVM-wide property that the JDK server reads once, when it first starts. When more connections than this arrive, the server closes the extra ones at once. That is why the property is set before any server starts.
- `try (A a = ...; B b = ...; C c = ...) { ... }` opens three resources; all are closed at the end, in the reverse order (link, then TLS server, then plain server).
- The TLS server gets its context from `TlsMaterial.serverContext(keystore, passwordFile)`. The key store is `seatwave-valid.p12` and the password comes from a file, never from code.
- `DelayLink.start(distantPort, tls.port(), new LinkSettings(...))` puts the link in front of the HTTPS server. The handshake rule is `ONE_ROUND_TRIP`.
- `System.out.printf(Locale.ROOT, "...%s%n...", ...)` prints a formatted block. `%s` is any value as text, `%d` is a whole number, `%n` is a line break. The string is split in two with `+` only to fit the line width.
- `System.out.flush()` pushes the text out before the long sleep, so a script sees the ready message at once.
- `Thread.sleep(settings.duration())` blocks this thread for the configured time (300 s in `config/serve.properties`). Then the block ends and the three resources close. A bounded run means a forgotten terminal holds no port forever.

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

- `read` builds a `Settings` record from the reader. Each `reader.xxx("key", ...)` call returns a value or, if the key is missing or wrong, records a problem and returns a harmless stand-in.
- `Result.error(reader.problems())` is returned before any real use of the stand-ins. `Result.success(settings)` is the good case.
- The ranges are part of the safety: ports from 1,024 to 65,535, delay 0 to 10,000 ms, connections 1 to 10,000, seconds 1 to 3,600.

```java
  private record Settings(
      String tlsDirectory,
      List<String> protocols,
      int httpPort,
      int httpsPort,
      int distantPort,
      Duration oneWayDelay,
      int maxConnections,
      Duration duration) {}
}
```

- A private record that groups the eight values. A record gives constructor, accessors, `equals` and `toString` for free.

### `link/HandshakeDelay.java`

```java
package io.plainround.seatwave.network.link;

/**
 * Whether a {@link DelayLink} also charges the round trip of the TCP handshake.
 *
 * <p>The operating system completes the TCP handshake with the link itself, on the loopback
 * address, before the link sees the connection, so the link cannot delay the handshake. With {@link
 * #ONE_ROUND_TRIP} it holds the first bytes the client sends one extra round trip instead, so the
 * total time of a new connection matches a real distant one. A timing tool then shows that round
 * trip inside the next phase (TLS, or the first byte), not as "connect".
 */
public enum HandshakeDelay {
  /** The link adds nothing for the TCP handshake: a connect through it costs no round trip. */
  NONE,
  /** The first client bytes wait one extra round trip, standing for the TCP handshake. */
  ONE_ROUND_TRIP
}
```

- An **enum** is a type with a fixed set of named values. `NONE` and `ONE_ROUND_TRIP` are the only two.
- The comment explains the problem this solves. The link listens on the loopback address, so the operating system finishes the TCP handshake with the link before the link's code runs. The link cannot delay that handshake. With `ONE_ROUND_TRIP`, the link holds the first client bytes one extra round trip instead. A timing tool then shows that round trip inside the next phase (TLS or first byte) and not in "connect".

### `link/LinkSettings.java`

```java
package io.plainround.seatwave.network.link;

import java.time.Duration;
import java.util.Objects;

/**
 * How a {@link DelayLink} behaves.
 *
 * @param oneWayDelay the delay added in each direction; the round-trip time is twice this
 * @param handshakeDelay whether the first client bytes also pay the TCP handshake's round trip
 * @param maxConnections how many connections may be open at once; more are closed at once
 */
public record LinkSettings(
    Duration oneWayDelay, HandshakeDelay handshakeDelay, int maxConnections) {
```

- A record with three fields: the one-way delay (a `Duration`), the handshake rule, and the connection limit.
- The Javadoc `@param` lines document each field.

```java
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

- `LONGEST_ONE_WAY_DELAY` is 10 seconds: anything longer "is not a network; it is a typo".
- `public LinkSettings { ... }` is a **compact constructor**: code that runs before the fields are set, with the parameters in scope. It is the place to validate. If it throws, no `LinkSettings` object exists.
- `oneWayDelay.isNegative()` and `compareTo(...) > 0` check the range. `Duration` has no `<` operator, so `compareTo` is used: negative, zero or positive.
- `maxConnections < 1` refuses a link that could carry nothing.

```java
  /**
   * Returns the round-trip time the link adds.
   *
   * @return twice the one-way delay
   */
  public Duration roundTrip() {
    return oneWayDelay.multipliedBy(2);
  }
}
```

- `roundTrip()` is twice the one-way delay: a message there and the reply back. 40 ms each way gives 80 ms.

### `link/DelayLink.java`

```java
package io.plainround.seatwave.network.link;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
```

- `ServerSocket` waits for connections; `Socket` is one connection. `InputStream` and `OutputStream` read and write bytes on a socket.
- `ArrayBlockingQueue` and `BlockingQueue`: a queue with a fixed capacity where `take` waits when it is empty and `offer` waits (or gives up) when it is full.
- `ConcurrentHashMap` is a map safe to use from many threads; `Set` is an unordered group without duplicates. `Semaphore` counts permits. `TimeUnit` names units of time. `AtomicInteger` is a counter safe to change from many threads.

```java
/**
 * A pretend long-distance link on one machine: a TCP forwarder on the loopback address that holds
 * every chunk of bytes for a fixed one-way delay before passing it on, in each direction.
 *
 * <p>With a one-way delay of 40 ms, every exchange of bytes that needs an answer costs 80 ms: a
 * round-trip time of 80 ms, as for a fan in another country. The operating system completes the TCP
 * handshake with the link itself before {@code accept} returns, so the link cannot delay the
 * handshake; {@link HandshakeDelay#ONE_ROUND_TRIP} charges that round trip to the first bytes the
 * client sends instead.
 *
 * <p>Bytes keep their order in each direction, as on a TCP connection. It never drops anything; a
 * lossy link is Lab 3's subject. It accepts at most a fixed number of connections at once and
 * closes any connection over that limit at once. Thread-safe: the counters are atomic, and each
 * connection's bytes are confined to its own reader and writer threads in each direction.
 */
```

- The Javadoc says what the link promises: bytes keep their order in each direction, nothing is dropped, and a limit on open connections, with extra connections closed at once.
- **Thread-safe** here means: the counters are atomic, and each connection's bytes are touched only by that connection's own threads.

```java
public final class DelayLink implements AutoCloseable {

  // One read takes at most 16 KiB; any size works, since every chunk waits the same delay.
  private static final int CHUNK_BYTES = 16 * 1024;
  // Chunks waiting in one direction; a full queue makes the reader wait, which is backpressure.
  private static final int CHUNKS_IN_FLIGHT = 1_024;
  private static final Duration UPSTREAM_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  // How long a reader waits for room in a full queue before it gives the connection up.
  private static final Duration QUEUE_OFFER_TIMEOUT = Duration.ofSeconds(30);
  // 0 asks the operating system for its default accept backlog.
  private static final int SYSTEM_DEFAULT_BACKLOG = 0;
  // Below this, the writer spins instead of sleeping: a fifth of a millisecond of busy waiting.
  private static final long SPIN_BELOW_NANOS = 200_000;
  private static final Chunk END_OF_STREAM = new Chunk(0, new byte[0]);
```

- `CHUNK_BYTES = 16 * 1024` is 16 KiB: one read takes at most that many bytes. Any size works, because every chunk waits the same time.
- `CHUNKS_IN_FLIGHT = 1_024` is the queue length. When the queue is full, the reader stops reading from its socket. The sender's operating system then fills its buffers and slows the sender. This is **backpressure**: a full queue pushes back instead of eating memory.
- `UPSTREAM_CONNECT_TIMEOUT` is 5 seconds: the longest the link waits to reach the real server. `QUEUE_OFFER_TIMEOUT` is 30 seconds. **Every network call has a timeout** in this repository; a call without one can hang forever.
- `SPIN_BELOW_NANOS = 200_000` is 0.2 ms. Below it, the writer **spins** (loops on the clock) instead of sleeping, because a sleep can wake late.
- `END_OF_STREAM` is a special chunk with no bytes. It is compared by identity (`==`), as a marker that the sender is done.

```java
  private final ServerSocket listener;
  private final InetSocketAddress upstream;
  private final long oneWayDelayNanos;
  private final long handshakeExtraNanos;
  private final Semaphore openSlots;
  private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
  private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
  private final AtomicInteger connectionsAccepted = new AtomicInteger();
  private final AtomicInteger connectionsRefused = new AtomicInteger();
  private final AtomicInteger connectionsFailed = new AtomicInteger();
  private volatile boolean isClosed;
```

- Fields that never change are `final`. `listener` is the listening socket and `upstream` the address of the real server.
- Times are kept in nanoseconds as `long` values: `oneWayDelayNanos` and `handshakeExtraNanos`.
- `ConcurrentHashMap.newKeySet()` makes a set that many threads may change. `openSockets` lists every socket in use, so `close()` can close them all.
- Three `AtomicInteger` counters: accepted, refused and failed connections.
- `volatile boolean isClosed` is a flag that is written by one thread and read by others. `volatile` makes a write visible to other threads at once.

```java
  private DelayLink(ServerSocket listener, InetSocketAddress upstream, LinkSettings settings) {
    this.listener = listener;
    this.upstream = upstream;
    this.oneWayDelayNanos = settings.oneWayDelay().toNanos();
    this.handshakeExtraNanos =
        switch (settings.handshakeDelay()) {
          case NONE -> 0;
          case ONE_ROUND_TRIP -> settings.roundTrip().toNanos();
        };
    this.openSlots = new Semaphore(settings.maxConnections());
    threads.submit(this::acceptLoop);
  }
```

- The constructor copies the settings into fields. `settings.oneWayDelay().toNanos()` converts to nanoseconds.
- The `switch` here is a **switch expression**: it produces a value. Each `case NONE -> 0;` arm gives the number to use. No arm may be missing for an enum, or the build fails.
- `new Semaphore(settings.maxConnections())` has one permit per allowed connection.
- `threads.submit(this::acceptLoop)` starts the accept loop on its own virtual thread.

```java
  /**
   * Starts a link on the loopback address that forwards to one upstream server.
   *
   * @param port the port to listen on, or 0 for any free port
   * @param upstreamPort the port of the server on the loopback address
   * @param settings the delay, the handshake rule and the connection limit
   * @return the running link
   * @throws IOException if the port cannot be bound
   */
  public static DelayLink start(int port, int upstreamPort, LinkSettings settings)
      throws IOException {
    Objects.requireNonNull(settings, "settings");
    InetAddress loopback = InetAddress.getLoopbackAddress();
    ServerSocket listener = new ServerSocket(port, SYSTEM_DEFAULT_BACKLOG, loopback);
    return new DelayLink(listener, new InetSocketAddress(loopback, upstreamPort), settings);
  }

  /**
   * Returns the port the link listens on.
   *
   * @return the port
   */
  public int port() {
    return listener.getLocalPort();
  }
```

- `start` opens a `ServerSocket(port, backlog, loopback)`. The third argument is the address to listen on: the loopback address, so only this machine can connect.
- The link's upstream is also on the loopback address: the real server's port.
- `port()` asks the listening socket for its local port, so port 0 works in tests.

```java
  /**
   * Returns how many connections the link has accepted and forwarded so far.
   *
   * @return the count; a client that reuses one connection for many requests adds one
   */
  public int connectionsAccepted() {
    return connectionsAccepted.get();
  }

  /**
   * Returns how many connections the link closed at once because the limit was reached.
   *
   * @return the count
   */
  public int connectionsRefused() {
    return connectionsRefused.get();
  }

  /**
   * Returns how many connections ended because a socket failed, for example an upstream that was
   * not listening.
   *
   * @return the count
   */
  public int connectionsFailed() {
    return connectionsFailed.get();
  }

  /** Stops accepting, closes every open connection and waits for the link's threads to end. */
  @Override
  public void close() throws IOException {
    isClosed = true;
    listener.close();
    for (Socket socket : openSockets) {
      socket.close();
    }
    threads.close();
  }
```

- Three getters return the counters, so a test or the timing client can see how many connections were opened. The timing client reads the accepted count before and after a run to learn how many connections that run opened.
- `close()` sets the flag, closes the listener (which makes `accept` fail and ends the accept loop), closes every open socket (which makes blocked reads fail), and then `threads.close()` waits for every virtual thread to finish.

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

- `listener.accept()` blocks until a client connects, and returns a `Socket`.
- `openSlots.tryAcquire()` takes a permit without waiting. If none is left, the link is full: it counts a refusal and closes the client at once. Closing at once is better than accepting and stalling: the client sees the connection end quickly.
- Otherwise the link counts the connection and starts `serve(client)` on a new virtual thread. `() -> serve(client)` is a **lambda**: a small anonymous function.
- When `close()` closes the listener, `accept` throws `IOException`, the method returns, and the loop ends. Any other accept failure also ends the loop, and later clients get "connection refused" instead of a hang.

```java
  private void serve(Socket client) {
    Socket server = new Socket();
    openSockets.add(client);
    openSockets.add(server);
    try (client;
        server) {
      if (isClosed) {
        // close() ran between accept and here; it may have missed these two sockets.
        return;
      }
      try {
        server.connect(upstream, Math.toIntExact(UPSTREAM_CONNECT_TIMEOUT.toMillis()));
      } catch (IOException upstreamUnreachable) {
        // Counted before the client's socket closes, then handled by closing it: the client sees
        // its connection end, which is what a dead server looks like from the far side of a link.
        connectionsFailed.incrementAndGet();
        return;
      }
      // Small TLS and HTTP messages must leave at once; Nagle's algorithm would hold them back.
      client.setTcpNoDelay(true);
      server.setTcpNoDelay(true);
      try (ExecutorService directions = Executors.newVirtualThreadPerTaskExecutor()) {
        directions.submit(() -> forward(client, server, handshakeExtraNanos));
        directions.submit(() -> forward(server, client, 0));
      }
    } catch (IOException socketFailed) {
      // Setting an option or closing a socket failed; the connection is over either way.
      connectionsFailed.incrementAndGet();
    } finally {
      openSockets.remove(client);
      openSockets.remove(server);
      openSlots.release();
    }
  }
```

- `serve` handles one client connection. It makes a new `Socket` for the upstream and registers both sockets so `close()` can reach them.
- `try (client; server)` closes both sockets at the end of the block.
- If `close()` ran between `accept` and here, the method returns early; it may have missed these two sockets.
- `server.connect(upstream, Math.toIntExact(...millis))` connects to the real server, with a timeout. `Math.toIntExact` throws instead of wrapping if the value does not fit an `int`.
- If the real server is not there, the failure is counted and the method returns. Returning closes the client socket, and the client sees its connection end, which is what a dead server looks like from the far side.
- `setTcpNoDelay(true)` turns off **Nagle's algorithm**, which holds small writes back to merge them. Small TLS and HTTP messages must leave at once.
- `try (ExecutorService directions = Executors.newVirtualThreadPerTaskExecutor())` makes a small executor for this connection. It starts two tasks: `forward(client, server, handshakeExtraNanos)` and `forward(server, client, 0)`. Closing the executor at the end of the block waits for both to finish, so the sockets stay open until both directions end.
- Only the direction from the client carries the handshake charge (the third argument), because the client speaks first.
- `finally` runs even on a return or an exception: it removes the sockets and **releases the permit**, so a finished connection frees its slot.

```java
  private void forward(Socket from, Socket to, long firstChunkExtraNanos) {
    BlockingQueue<Chunk> inFlight = new ArrayBlockingQueue<>(CHUNKS_IN_FLIGHT);
    Thread writer = Thread.ofVirtual().start(() -> deliver(inFlight, from, to));
    byte[] buffer = new byte[CHUNK_BYTES];
    long extraNanos = firstChunkExtraNanos;
    try {
      InputStream in = from.getInputStream();
      int read;
      while ((read = in.read(buffer)) >= 0) {
        long dueNanos = System.nanoTime() + oneWayDelayNanos + extraNanos;
        // Only the first chunk pays the handshake's round trip; the chunks behind it wait for it
        // anyway, because the writer delivers in order.
        extraNanos = 0;
        Chunk chunk = new Chunk(dueNanos, Arrays.copyOf(buffer, read));
        if (!inFlight.offer(chunk, QUEUE_OFFER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          break;
        }
      }
      inFlight.offer(END_OF_STREAM, QUEUE_OFFER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      writer.join();
    } catch (IOException closed) {
      // The peer reset or closed the connection; the writer stops on its own when it fails.
      writer.interrupt();
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      writer.interrupt();
    }
  }
```

- `forward` is the reader of one direction. It makes a queue and starts a **writer** virtual thread with `Thread.ofVirtual().start(...)` that runs `deliver`.
- `byte[] buffer = new byte[CHUNK_BYTES]` is the read buffer.
- `in.read(buffer)` blocks until some bytes arrive and returns how many (it returns -1 at the end of the stream). The `while ((read = in.read(buffer)) >= 0)` pattern assigns and tests in one line.
- `dueNanos` is the time to deliver this chunk: now, plus the one-way delay, plus the extra (only for the first chunk). `System.nanoTime()` is the clock. Setting `extraNanos = 0` means only the first chunk pays the handshake round trip. The later chunks wait behind it anyway, because the writer delivers in order.
- `Arrays.copyOf(buffer, read)` copies just the bytes that were read, because the buffer is reused for the next read.
- `inFlight.offer(chunk, 30000, MILLISECONDS)` waits at most 30 s for room. If it fails, the loop breaks.
- After the stream ends, the reader queues `END_OF_STREAM` and waits for the writer with `writer.join()`.
- `catch (IOException closed)`: the connection was reset or closed. The reader interrupts the writer. `catch (InterruptedException stopped)`: someone interrupted this thread; it restores the **interrupt flag** with `Thread.currentThread().interrupt()` and stops the writer. (Swallowing an interrupt would hide the request to stop.)

```java
  private void deliver(BlockingQueue<Chunk> inFlight, Socket from, Socket to) {
    try {
      OutputStream out = to.getOutputStream();
      while (true) {
        Chunk chunk = inFlight.take();
        if (chunk == END_OF_STREAM) {
          // The sender finished: pass the end of the stream on, after the same delay.
          to.shutdownOutput();
          return;
        }
        waitUntil(chunk.dueNanos());
        out.write(chunk.bytes());
        out.flush();
      }
    } catch (IOException peerGone) {
      // The receiving side is gone, so the connection is over: closing the sending side makes
      // its reader see the end too, instead of filling a queue nobody empties.
      try {
        from.close();
      } catch (IOException alsoGone) {
        peerGone.addSuppressed(alsoGone);
        connectionsFailed.incrementAndGet();
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }
```

- `deliver` is the writer. It loops forever, taking chunks from the queue. `take()` waits when the queue is empty.
- When it takes `END_OF_STREAM`, it calls `to.shutdownOutput()`: it tells the far side "no more bytes from me" (a half-close), after the same delay as the data. Then it returns.
- For a real chunk: `waitUntil(chunk.dueNanos())` waits for the due time, then `out.write(...)` and `out.flush()` send the bytes.
- If writing fails, the receiver is gone. The writer closes the sending side, so the reader sees the end too instead of filling a queue nobody empties. If that close also fails, the second error is attached with `addSuppressed` and the failure is counted.

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

  private record Chunk(long dueNanos, byte[] bytes) {}
}
```

- `waitUntil` has two loops. The first sleeps half of the remaining time while more than 0.2 ms remains. Sleeping half each time means a late wake-up can only waste a share of the remaining time. (The comment notes that macOS merges timers, which makes sleeps wake late.)
- The second loop spins until the time is reached. `Thread.onSpinWait()` is a hint to the processor that this is a busy wait.
- `Thread.sleep(Duration)` takes a `Duration` (Java 19).
- `private record Chunk(long dueNanos, byte[] bytes) {}` is the queue item: when, and what.

## Try it yourself

All commands run in the repository folder (`seatwave-network`). They use only the loopback address. The first run of any Gradle command may download Gradle and the libraries, and the machine used here printed nothing else.

**1. Start SeatWave and read the three listeners.**

```text
./gradlew -q serve --args='config/serve.properties'
```

It printed:

```text
plain HTTP: http://127.0.0.1:8080/
HTTPS:      https://127.0.0.1:8443/
distant:    https://127.0.0.1:8444/ (adds 80 ms of round-trip time)
serving for 300 s; TLS versions TLSv1.3; at most 256 connections each
```

Leave it running and open a second terminal. Press `Ctrl-C` in the first one when you are done.

**2. Ask for the health page, with the full reply.**

```text
curl -s -i http://127.0.0.1:8080/health
```

```text
HTTP/1.1 200 OK
Date: Tue, 10 Nov 2026 23:09:03 GMT
Content-type: application/json
Server-timing: app;dur=0.40
Content-length: 15

{"status":"up"}
```

The JDK server writes header names with only the first letter in capitals (`Server-timing`). Header names are not case sensitive.

**3. Try a method the server does not allow.**

```text
curl -s -i -X DELETE http://127.0.0.1:8080/events
```

```text
HTTP/1.1 405 Method Not Allowed
Date: Tue, 10 Nov 2026 23:09:04 GMT
Allow: GET, HEAD
Content-type: application/problem+json
Server-timing: app;dur=0.10
Content-length: 51

{"type":"about:blank","detail":"only GET and HEAD"}
```

**4. Try a bad section.** Quote the address, or your shell will treat the `?` as a pattern.

```text
curl -s -w ' %{http_code}\n' 'http://127.0.0.1:8080/events/1/seats?section=a'
```

```text
{"type":"about:blank","detail":"section must be one capital letter"} 400
```

**5. Feel the delay link.** Port 8443 is HTTPS without the link; port 8444 has the link. The `--cacert` flag tells curl to trust the TEST certificate authority (read `curl-and-openssl.md`).

```text
curl -s --cacert build/tls/seatwave-test-ca.pem -o /dev/null -w 'distant total %{time_total}\n' https://127.0.0.1:8444/health
```

```text
distant total 0.253316
```

That is about 0.25 s: three round trips of 80 ms (the TCP handshake charged by the link, the TLS handshake, and the request) plus a little work. Timings differ on every run.

**6. Start a second server on a taken port.** With the first one still running:

```text
./gradlew -q serve --args='config/serve.properties'
```

```text
cannot serve: Address already in use
```

Gradle then prints its own `FAILURE: Build failed` text. The program exited with status 2 because the port was in use.

## Mistakes people make

- **Forgetting that the delay is per direction.** A one-way delay of 40 ms is an 80 ms round trip. `LinkSettings.roundTrip()` exists so no code multiplies by two by hand.
- **Expecting the link to delay the TCP handshake.** The operating system answers the handshake itself. That is why `HandshakeDelay.ONE_ROUND_TRIP` charges the round trip to the first bytes. In a timing tool the cost then shows up in the TLS or first-byte phase, not in "connect".
- **Trusting a client-supplied value.** Every part of the request is checked against an allow-list (`/events`, `/health`, `section=` plus one capital letter) before any work.
- **Starting the HTTP server before setting its limit.** `jdk.httpserver.maxConnections` is read when the first server starts. `ServeCli` sets it first.
- **Swallowing an interrupt.** The link restores the interrupt flag when it catches `InterruptedException`.
- **A network call with no timeout.** The link's upstream connect has 5 seconds and the queue offer has 30 seconds.
- **Listening on all addresses.** The code uses the loopback address only, so nothing outside the machine can connect.

## Check yourself

1. What does port 0 mean when a server is started, and how does the program learn the real port? (It asks the operating system for any free port; `port()` reads it back from the bound socket.)
2. Why does `DelayLink` queue each chunk with a due time instead of calling `Thread.sleep` in the reading loop? (A sleep in the reader would delay reading, and chunks would bunch together; the queue lets the reader keep reading while the writer delivers each chunk at its own due time, in order.)
3. What happens to a client when the link is full? (The link counts a refusal and closes that client's connection at once.)
4. Why does `SeatWaveServer.reply` use `-1` as the length for a HEAD request? (`-1` means "no body". A HEAD reply carries only headers.)
5. Which line of `ServeCli` makes the program stop after 300 seconds, and why does it matter? (`Thread.sleep(settings.duration())`, then the try block closes all three resources; a forgotten terminal then holds no port.)

## Further reading

Official documentation, for the JDK this repository pins (Java 25):

- `com.sun.net.httpserver` package (module `jdk.httpserver`): https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html
- `java.net.ServerSocket` and `java.net.Socket`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/ServerSocket.html
- `java.util.concurrent.Semaphore`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Semaphore.html
- Virtual threads (JEP 444): https://openjdk.org/jeps/444
- Server-Timing header (the W3C specification): https://www.w3.org/TR/server-timing/
- Problem details for HTTP APIs (RFC 9457): https://www.rfc-editor.org/rfc/rfc9457

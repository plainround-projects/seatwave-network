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

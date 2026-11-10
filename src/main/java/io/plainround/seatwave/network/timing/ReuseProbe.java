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

  private static String protocolName(HttpClient.Version version) {
    return switch (version) {
      case HTTP_1_1 -> "HTTP/1.1";
      case HTTP_2 -> "HTTP/2";
    };
  }

  private record Timed(Duration elapsed, String protocol) {}
}

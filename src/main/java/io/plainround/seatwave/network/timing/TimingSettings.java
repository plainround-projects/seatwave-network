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

  // A path SeatWave serves: a slash, then letters, digits, slashes, and one optional query.
  private static final Pattern PROBE_PATH = Pattern.compile("/[a-z0-9/]*(\\?[a-z]+=[A-Z0-9]+)?");
  private static final Pattern SCENARIO_NAME = Pattern.compile("[a-z][a-z0-9-]{0,19}");
  private static final int MAX_RUNS = 1_000;
  private static final int MAX_TIMEOUT_MILLIS = 60_000;
  private static final int MAX_DELAY_MILLIS = 10_000;
  private static final int MAX_CONNECTIONS = 10_000;
  private static final List<String> TLS_VERSIONS = List.of("TLSv1.3", "TLSv1.2");

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

  private static List<String> namesInFile(SettingsReader reader) {
    // Scenario names become parts of keys, so only short lower-case names are allowed; any other
    // name is missing from this list, and wordsIn reports it.
    return List.of(reader.text("scenario.names").split(" *, *")).stream()
        .filter(name -> SCENARIO_NAME.matcher(name).matches())
        .toList();
  }

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

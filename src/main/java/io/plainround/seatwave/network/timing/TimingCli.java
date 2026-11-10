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

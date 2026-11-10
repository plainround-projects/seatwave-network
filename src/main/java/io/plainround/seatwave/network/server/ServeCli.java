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

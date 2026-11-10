package io.plainround.seatwave.network.tls;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point of failure mode 4: tries the four TLS scenarios and prints what the
 * client did with each. Exit status 2 means bad usage or settings; 1 means a scenario ended the
 * wrong way (a bad certificate accepted, or the good one refused).
 */
public final class TlsFailuresCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_WRONG_OUTCOME = 1;

  private TlsFailuresCli() {}

  /**
   * Prints one line per scenario.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while a request is in flight
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: tlsFailures <tls-failures.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (TlsSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<TlsSettings, List<String>>(TlsSettings settings) -> run(settings);
        case Result.Error<TlsSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid setting: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException | GeneralSecurityException unusable) {
      System.err.println("cannot use the test certificates: " + unusable.getMessage());
      System.exit(EXIT_BAD_USAGE);
    }
  }

  private static void run(TlsSettings settings)
      throws IOException, GeneralSecurityException, InterruptedException {
    System.out.println(
        "Server accepts "
            + String.join(", ", settings.serverProtocols())
            + "; the client trusts only the TEST CA and checks dates and names.");
    TlsFailures failures = new TlsFailures(settings);
    boolean isEveryOutcomeRight = true;
    for (TlsScenario scenario : TlsScenario.values()) {
      HandshakeOutcome outcome = failures.attempt(scenario);
      boolean shouldBeAccepted = scenario == TlsScenario.VALID;
      isEveryOutcomeRight &= outcome.isAccepted() == shouldBeAccepted;
      System.out.printf(
          Locale.ROOT,
          "%-12s %-9s %s%n",
          scenario.name().toLowerCase(Locale.ROOT),
          outcome.isAccepted() ? "accepted" : "refused",
          outcome.detail());
    }
    if (!isEveryOutcomeRight) {
      System.exit(EXIT_WRONG_OUTCOME);
    }
  }
}

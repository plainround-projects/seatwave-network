package io.plainround.seatwave.network.tls;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Where the TEST certificates are and which TLS versions each side allows. Read from a settings
 * file with no defaults.
 *
 * @param directory the folder the {@code testCertificates} task writes, {@code build/tls}
 * @param serverProtocols the TLS versions the server accepts, for example {@code TLSv1.3}
 * @param oldClientProtocols the versions the client offers in {@link TlsScenario#OLD_VERSION}
 * @param timeout the connect and request timeout of every client call
 */
public record TlsSettings(
    Path directory,
    List<String> serverProtocols,
    List<String> oldClientProtocols,
    Duration timeout) {

  // The JDK's names for the TLS versions it implements; anything else is a typo.
  private static final List<String> KNOWN_VERSIONS = List.of("TLSv1.3", "TLSv1.2");
  private static final int MAX_TIMEOUT_MILLIS = 60_000;

  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if a list is empty or names an unknown TLS version
   */
  public TlsSettings {
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(timeout, "timeout");
    serverProtocols = List.copyOf(serverProtocols);
    oldClientProtocols = List.copyOf(oldClientProtocols);
    if (serverProtocols.isEmpty() || oldClientProtocols.isEmpty()) {
      throw new IllegalArgumentException("each side needs at least one TLS version");
    }
    if (!KNOWN_VERSIONS.containsAll(serverProtocols)
        || !KNOWN_VERSIONS.containsAll(oldClientProtocols)) {
      throw new IllegalArgumentException("TLS versions must be from " + KNOWN_VERSIONS);
    }
  }

  /**
   * Reads the settings.
   *
   * @param reader the settings reader
   * @return the settings, or one line per missing or invalid key
   */
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

  /**
   * Returns the CA certificate the client trusts.
   *
   * @return {@code <directory>/seatwave-test-ca.pem}
   */
  public Path certificateAuthority() {
    return directory.resolve("seatwave-test-ca.pem");
  }

  /**
   * Returns the keystore password file.
   *
   * @return {@code <directory>/store.password}
   */
  public Path passwordFile() {
    return directory.resolve("store.password");
  }
}

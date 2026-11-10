package io.plainround.seatwave.network.tls;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;

/** The TEST certificates the build writes before the tests run, and contexts made from them. */
public final class TestTls {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private TestTls() {}

  /** The folder the {@code testCertificates} task wrote, passed in by the build. */
  public static Path directory() {
    return Path.of(System.getProperty("seatwave.tls.directory"));
  }

  /** A server context with the valid TEST certificate. */
  public static SSLContext validServer() throws IOException, GeneralSecurityException {
    return TlsMaterial.serverContext(
        directory().resolve("seatwave-valid.p12"), directory().resolve("store.password"));
  }

  /** A client context that trusts only the TEST CA. */
  public static SSLContext client() throws IOException, GeneralSecurityException {
    return TlsMaterial.clientContext(directory().resolve("seatwave-test-ca.pem"));
  }

  /** The lab's settings: a TLS 1.3 server and an old client that offers TLS 1.2 only. */
  public static TlsSettings settings() {
    return new TlsSettings(directory(), List.of("TLSv1.3"), List.of("TLSv1.2"), TIMEOUT);
  }
}

package io.plainround.seatwave.network.tls;

import io.plainround.seatwave.network.server.SeatWaveServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * Tries each {@link TlsScenario} against a real HTTPS server on the loopback address, with a client
 * that trusts only the TEST certificate authority and checks dates and names as every browser does.
 *
 * <p>The point is the refusals: an expired certificate, a certificate for another name and an old
 * TLS version each stop the request before a byte of HTTP is sent. That is failing safe. Thread
 * safety: each call starts its own server and client and shares nothing.
 */
public final class TlsFailures {

  // Any free port: the server lives only as long as one attempt.
  private static final int ANY_FREE_PORT = 0;

  private final TlsSettings settings;
  private final SSLContext clientContext;

  /**
   * Prepares the client side once.
   *
   * @param settings where the certificates are and which versions each side allows
   * @throws IOException if the CA certificate cannot be read
   * @throws GeneralSecurityException if it is not a certificate
   */
  public TlsFailures(TlsSettings settings) throws IOException, GeneralSecurityException {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clientContext = TlsMaterial.clientContext(settings.certificateAuthority());
  }

  /**
   * Starts a server for the scenario, sends {@code GET /health} and reports what happened.
   *
   * @param scenario the scenario
   * @return accepted with the TLS version and cipher suite, or refused with the client's reason
   * @throws IOException if the server cannot start
   * @throws GeneralSecurityException if the server keystore cannot be opened
   * @throws InterruptedException if the thread is interrupted while waiting for the reply
   */
  public HandshakeOutcome attempt(TlsScenario scenario)
      throws IOException, GeneralSecurityException, InterruptedException {
    SSLContext serverContext =
        TlsMaterial.serverContext(
            settings.directory().resolve(scenario.keystoreFile()), settings.passwordFile());
    try (SeatWaveServer server =
            SeatWaveServer.startTls(ANY_FREE_PORT, serverContext, settings.serverProtocols());
        HttpClient client = client(scenario)) {
      HttpRequest request =
          HttpRequest.newBuilder(server.baseUri().resolve(URI.create("health")))
              .timeout(settings.timeout())
              .GET()
              .build();
      try {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String negotiated =
            response.sslSession().map(TlsFailures::describe).orElse("no TLS session");
        return new HandshakeOutcome(scenario, true, response.statusCode() + " over " + negotiated);
      } catch (IOException refused) {
        // The expected failure of a bad certificate or version: the client refuses, and the
        // reason is the result, not an error of this program.
        return new HandshakeOutcome(scenario, false, refused.getMessage());
      }
    }
  }

  private HttpClient client(TlsScenario scenario) {
    HttpClient.Builder builder =
        HttpClient.newBuilder().sslContext(clientContext).connectTimeout(settings.timeout());
    if (scenario == TlsScenario.OLD_VERSION) {
      SSLParameters oldOnly = clientContext.getDefaultSSLParameters();
      oldOnly.setProtocols(settings.oldClientProtocols().toArray(String[]::new));
      builder.sslParameters(oldOnly);
    }
    return builder.build();
  }

  private static String describe(SSLSession session) {
    return session.getProtocol() + " with " + session.getCipherSuite();
  }
}

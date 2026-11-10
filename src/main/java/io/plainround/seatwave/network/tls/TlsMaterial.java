package io.plainround.seatwave.network.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Builds the two TLS contexts of the lab from the files the {@code testCertificates} build task
 * writes into {@code build/tls}: a server context that holds one TEST server key, and a client
 * context that trusts the TEST certificate authority and nothing else.
 *
 * <p>Only the JDK's standard TLS is used. There is deliberately no "trust every certificate" option
 * anywhere in this project: a client that cannot verify the server refuses to talk to it.
 * Thread-safe: it holds no state.
 */
public final class TlsMaterial {

  // "TLS" asks the JDK for its default TLS implementation, which supports TLS 1.3 and 1.2.
  private static final String TLS = "TLS";
  private static final String PKCS12 = "PKCS12";
  private static final String TRUSTED_CA_ALIAS = "seatwave-test-ca";

  private TlsMaterial() {}

  /**
   * Builds a server context from a PKCS12 keystore with one key and its certificate chain.
   *
   * @param keystore the server keystore, for example {@code build/tls/seatwave-valid.p12}
   * @param passwordFile the file that holds the keystore password
   * @return a context for {@code SeatWaveServer.startTls}
   * @throws IOException if a file cannot be read
   * @throws GeneralSecurityException if the keystore cannot be opened
   */
  public static SSLContext serverContext(Path keystore, Path passwordFile)
      throws IOException, GeneralSecurityException {
    char[] password = readPassword(passwordFile);
    KeyStore store = KeyStore.getInstance(PKCS12);
    try (InputStream in = Files.newInputStream(keystore)) {
      store.load(in, password);
    }
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, password);
    SSLContext context = SSLContext.getInstance(TLS);
    // null trust managers and randomness: the JDK's defaults, since the server checks no client.
    context.init(keys.getKeyManagers(), null, null);
    return context;
  }

  /**
   * Builds a client context that trusts exactly one certificate authority.
   *
   * @param certificateAuthorityPem the CA certificate in PEM form
   * @return a context for {@code HttpClient.Builder.sslContext}
   * @throws IOException if the file cannot be read
   * @throws GeneralSecurityException if the file is not a certificate
   */
  public static SSLContext clientContext(Path certificateAuthorityPem)
      throws IOException, GeneralSecurityException {
    Objects.requireNonNull(certificateAuthorityPem, "certificateAuthorityPem");
    Certificate authority;
    try (InputStream in = Files.newInputStream(certificateAuthorityPem)) {
      authority = CertificateFactory.getInstance("X.509").generateCertificate(in);
    }
    KeyStore trusted = KeyStore.getInstance(PKCS12);
    // An empty in-memory store with one trusted entry: the JDK's default trust store, with the
    // public certificate authorities, is not used, so only the TEST CA can vouch for a server.
    trusted.load(null, null);
    trusted.setCertificateEntry(TRUSTED_CA_ALIAS, authority);
    TrustManagerFactory trust =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(trusted);
    SSLContext context = SSLContext.getInstance(TLS);
    context.init(null, trust.getTrustManagers(), null);
    return context;
  }

  private static char[] readPassword(Path passwordFile) throws IOException {
    Objects.requireNonNull(passwordFile, "passwordFile");
    String password = Files.readString(passwordFile, StandardCharsets.UTF_8).strip();
    if (password.isEmpty()) {
      throw new IOException("the password file is empty: " + passwordFile);
    }
    return password.toCharArray();
  }
}

package io.plainround.seatwave.network.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsFailuresTest {

  @Test
  void acceptsTheValidCertificateOverTls13() throws Exception {
    HandshakeOutcome outcome = new TlsFailures(TestTls.settings()).attempt(TlsScenario.VALID);

    assertThat(outcome.isAccepted()).isTrue();
    assertThat(outcome.detail()).startsWith("200 over TLSv1.3 with TLS_");
  }

  @Test
  void refusesAnExpiredCertificate() throws Exception {
    HandshakeOutcome outcome = new TlsFailures(TestTls.settings()).attempt(TlsScenario.EXPIRED);

    assertThat(outcome.isAccepted()).isFalse();
    assertThat(outcome.detail()).contains("certificate_expired");
  }

  @Test
  void refusesACertificateIssuedForAnotherName() throws Exception {
    HandshakeOutcome outcome = new TlsFailures(TestTls.settings()).attempt(TlsScenario.WRONG_NAME);

    assertThat(outcome.isAccepted()).isFalse();
    assertThat(outcome.detail()).contains("No subject alternative names matching IP address");
  }

  @Test
  void refusesAClientThatOffersOnlyAnOlderVersion() throws Exception {
    HandshakeOutcome outcome = new TlsFailures(TestTls.settings()).attempt(TlsScenario.OLD_VERSION);

    assertThat(outcome.isAccepted()).isFalse();
    assertThat(outcome.detail()).contains("protocol_version");
  }

  @Test
  void refusesATrustFileThatIsNotACertificate(@TempDir Path folder) throws Exception {
    Path notACertificate =
        Files.writeString(folder.resolve("ca.pem"), "not a certificate", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> TlsMaterial.clientContext(notACertificate))
        .isInstanceOf(java.security.cert.CertificateException.class);
  }

  @Test
  void refusesAnEmptyPasswordFile(@TempDir Path folder) throws Exception {
    Path empty = Files.writeString(folder.resolve("store.password"), "\n");

    assertThatThrownBy(() ->
            TlsMaterial.serverContext(TestTls.directory().resolve("seatwave-valid.p12"), empty))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("empty");
  }

  @Test
  void readsTheSettingsAndListsEveryProblem() {
    Properties good = new Properties();
    good.setProperty("tls.directory", "build/tls");
    good.setProperty("tls.server.protocols", "TLSv1.3");
    good.setProperty("tls.old.client.protocols", "TLSv1.2");
    good.setProperty("client.timeout.millis", "5000");
    Properties bad = new Properties();
    bad.putAll(good);
    bad.setProperty("tls.server.protocols", "SSLv3");
    bad.remove("client.timeout.millis");

    TlsSettings read = TlsSettings.from(new SettingsReader(good)).discardError().orElseThrow();
    Result<TlsSettings, List<String>> refused = TlsSettings.from(new SettingsReader(bad));

    assertThat(read.timeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(read.certificateAuthority()).hasFileName("seatwave-test-ca.pem");
    assertThat(((Result.Error<TlsSettings, List<String>>) refused).error())
        .containsExactly(
            "tls.server.protocols has SSLv3, but allows only TLSv1.3, TLSv1.2",
            "client.timeout.millis is missing");
  }

  @Test
  void refusesUnknownOrEmptyVersionsInCode() {
    Path folder = Path.of("build/tls");
    Duration timeout = Duration.ofSeconds(1);

    assertThatThrownBy(() -> new TlsSettings(folder, List.of(), List.of("TLSv1.2"), timeout))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new TlsSettings(folder, List.of("TLSv1.0"), List.of("TLSv1.2"), timeout))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

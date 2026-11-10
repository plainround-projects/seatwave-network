package io.plainround.seatwave.network.timing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.network.link.DelayLink;
import io.plainround.seatwave.network.link.HandshakeDelay;
import io.plainround.seatwave.network.link.LinkSettings;
import io.plainround.seatwave.network.report.ReuseSample;
import io.plainround.seatwave.network.server.SeatWaveServer;
import io.plainround.seatwave.network.tls.TestTls;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReuseProbeTest {

  private static final TimingSettings.Timeouts TIMEOUTS =
      new TimingSettings.Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
  private static final int RUNS = 3;

  @Test
  void opensOneConnectionPerRunAndReusesItForTheSecondRequest() throws Exception {
    try (SeatWaveServer server =
            SeatWaveServer.startTls(0, TestTls.validServer(), List.of("TLSv1.3"));
        DelayLink link =
            DelayLink.start(
                0, server.port(), new LinkSettings(Duration.ZERO, HandshakeDelay.NONE, 8))) {
      ReuseProbe probe = new ReuseProbe(TestTls.client(), TIMEOUTS);
      URI uri = URI.create("https://127.0.0.1:" + link.port() + "/events/1/seats?section=A");

      List<ReuseSample> samples = probe.measure(uri, HttpClient.Version.HTTP_2, RUNS);

      // Two requests per run, but one connection per run: the second request reused it.
      assertThat(samples).hasSize(RUNS);
      assertThat(link.connectionsAccepted()).isEqualTo(RUNS);
      assertThat(samples).allSatisfy(sample -> assertThat(sample.protocol()).isEqualTo("HTTP/1.1"));
    }
  }

  @Test
  void stopsRatherThanTimeAnErrorResponse() throws Exception {
    try (SeatWaveServer server = SeatWaveServer.startPlain(0)) {
      ReuseProbe probe = new ReuseProbe(TestTls.client(), TIMEOUTS);
      URI missing = server.baseUri().resolve("events/9/seats?section=A");

      assertThatThrownBy(() -> probe.measure(missing, HttpClient.Version.HTTP_1_1, 1))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("answered 404");
    }
  }

  @Test
  void refusesZeroRuns() throws Exception {
    ReuseProbe probe = new ReuseProbe(TestTls.client(), TIMEOUTS);

    assertThatThrownBy(
            () -> probe.measure(URI.create("http://127.0.0.1:1/"), HttpClient.Version.HTTP_1_1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

package io.plainround.seatwave.network.timing;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import io.plainround.seatwave.network.link.HandshakeDelay;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class TimingSettingsTest {

  @Test
  void readsTheProjectsTimingSettings() throws Exception {
    TimingSettings settings =
        TimingSettings.from(
                new SettingsReader(SettingsReader.load(Path.of("config/timing.properties"))))
            .discardError()
            .orElseThrow();

    assertThat(settings.versions())
        .containsExactly(HttpClient.Version.HTTP_1_1, HttpClient.Version.HTTP_2);
    assertThat(settings.scenarios())
        .extracting(Scenario::name)
        .containsExactly("simple", "local-tls", "distant-http", "distant-tls");
    Scenario distant = settings.scenarios().getLast();
    assertThat(distant.scheme()).isEqualTo(Scheme.HTTPS);
    assertThat(distant.oneWayDelay()).isEqualTo(Duration.ofMillis(40));
    assertThat(distant.handshakeDelay()).isEqualTo(HandshakeDelay.ONE_ROUND_TRIP);
  }

  @Test
  void listsEveryInvalidKey() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/timing.properties"));
    properties.setProperty("probe.path", "/../etc/passwd");
    properties.setProperty("probe.runs", "2");
    properties.setProperty("scenario.simple.scheme", "ftp");

    Result<TimingSettings, List<String>> read = TimingSettings.from(new SettingsReader(properties));

    assertThat(((Result.Error<TimingSettings, List<String>>) read).error())
        .containsExactly(
            "probe.path must match /[a-z0-9/]*(\\?[a-z]+=[A-Z0-9]+)?, but was /../etc/passwd",
            "probe.runs must be from 3 to 1000, but was 2",
            "scenario.simple.scheme has an unknown value: ftp");
  }

  @Test
  void runsEveryScenarioOnThisMachineAndCountsConnections() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/timing.properties"));
    properties.setProperty("tls.directory", System.getProperty("seatwave.tls.directory"));
    properties.setProperty("probe.warmup.runs", "0");
    properties.setProperty("scenario.names", "simple,local-tls");
    TimingSettings settings =
        TimingSettings.from(new SettingsReader(properties)).discardError().orElseThrow();

    String table = TimingCli.run(settings);

    assertThat(table)
        .contains("every request: GET /events/1/seats?section=A")
        .containsPattern("simple +HTTP_1_1 +HTTP/1.1 +7 .* 7\\n")
        .containsPattern("local-tls +HTTP_2 +HTTP/1.1 +7 .* 7\\n");
  }
}

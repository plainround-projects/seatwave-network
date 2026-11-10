package io.plainround.seatwave.network.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import io.plainround.seatwave.network.report.ConnectionKind;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class PageLoadModelTest {

  private static final double ROUND_TRIP = 80;

  @Test
  void countsTheRoundTripsOfANewConnectionWithNoLoss() throws Exception {
    PageLoadModel model = new PageLoadModel(settings("config/round-trips.properties"));

    // Setup (TCP 1 + TLS 1.3 1, or QUIC 1) plus the rounds of slow start the page needs. On one
    // HTTP/1.1 connection the five small replies grow the window only to 20 segments, so the
    // 26-segment seat map still needs two rounds.
    assertThat(pageRoundTrips(model, Protocol.HTTP1_ONE_CONNECTION, ConnectionKind.NEW))
        .isEqualTo(9);
    assertThat(pageRoundTrips(model, Protocol.HTTP1_SIX_CONNECTIONS, ConnectionKind.NEW))
        .isEqualTo(4);
    assertThat(pageRoundTrips(model, Protocol.HTTP2, ConnectionKind.NEW)).isEqualTo(5);
    assertThat(pageRoundTrips(model, Protocol.HTTP3, ConnectionKind.NEW)).isEqualTo(4);
  }

  @Test
  void needsOneRoundTripOnAWarmReusedConnectionUnlessRequestsWaitForEachOther() throws Exception {
    PageLoadModel model = new PageLoadModel(settings("config/round-trips.properties"));

    assertThat(pageRoundTrips(model, Protocol.HTTP1_ONE_CONNECTION, ConnectionKind.REUSED))
        .isEqualTo(6);
    assertThat(pageRoundTrips(model, Protocol.HTTP2, ConnectionKind.REUSED)).isEqualTo(1);
    assertThat(model.setupRoundTrips(Protocol.HTTP3, ConnectionKind.REUSED)).isZero();
  }

  @Test
  void findsHeadOfLineBlockingOnlyWhereOneOrderedStreamCarriesEveryResponse() throws Exception {
    PageLoadModel model = new PageLoadModel(settings("config/page-model.properties"));

    assertThat(model.summarize(Protocol.HTTP2, ConnectionKind.NEW).heldBackPercent()).isPositive();
    assertThat(model.summarize(Protocol.HTTP3, ConnectionKind.NEW).heldBackPercent()).isZero();
    assertThat(
            model.summarize(Protocol.HTTP1_SIX_CONNECTIONS, ConnectionKind.NEW).heldBackPercent())
        .isZero();
    assertThat(model.summarize(Protocol.HTTP1_ONE_CONNECTION, ConnectionKind.NEW).heldBackPercent())
        .isZero();
  }

  @Test
  void neverMakesALossyPageFasterThanALosslessOne() throws Exception {
    PageLoadModel lossless = new PageLoadModel(settings("config/round-trips.properties"));
    PageLoadModel lossy = new PageLoadModel(settings("config/page-model.properties"));

    for (Protocol protocol : Protocol.values()) {
      double floor = lossless.summarize(protocol, ConnectionKind.NEW).page().p50();
      PageLoadModel.Summary summary = lossy.summarize(protocol, ConnectionKind.NEW);

      assertThat(summary.page().p50()).as(protocol.label()).isGreaterThanOrEqualTo(floor);
      assertThat(summary.page().p95()).as(protocol.label()).isGreaterThan(floor);
    }
  }

  @Test
  void givesTheSameAnswerForTheSameSeed() throws Exception {
    PageLoadModel model = new PageLoadModel(settings("config/page-model.properties"));

    PageLoadModel.PageTimes first =
        model.simulate(Protocol.HTTP2, ConnectionKind.NEW, new SplittableRandom(1));
    PageLoadModel.PageTimes second =
        model.simulate(Protocol.HTTP2, ConnectionKind.NEW, new SplittableRandom(1));

    assertThat(first).isEqualTo(second);
  }

  @Test
  void printsTheAssumptionsAboveTheTable() throws Exception {
    String text = PageModelReport.render(settings("config/round-trips.properties"));

    assertThat(text)
        .startsWith("MODEL, not a measurement. Distance 8,000 km")
        .contains("speed-of-light floor in fiber is 80.0 ms")
        .contains("HTTP/1.1, 6 connections  new                  2       320");
  }

  @Test
  void treatsAOneResponsePageAsItsOwnSmallResponse() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/round-trips.properties"));
    properties.setProperty("response.bytes", "1000");
    PageLoadModel model = new PageLoadModel(read(properties));

    PageLoadModel.PageTimes times =
        model.simulate(Protocol.HTTP3, ConnectionKind.NEW, new SplittableRandom(1));

    assertThat(times.smallResponsesMillis())
        .isEqualTo(times.pageMillis())
        .isEqualTo(2 * ROUND_TRIP);
  }

  @Test
  void listsInvalidSettingsAndRefusesImpossibleValues() throws Exception {
    Properties properties = SettingsReader.load(Path.of("config/page-model.properties"));
    properties.setProperty("loss.percent", "60");
    properties.setProperty("protocols", "http4");

    Result<ModelSettings, List<String>> read = ModelSettings.from(new SettingsReader(properties));

    assertThat(((Result.Error<ModelSettings, List<String>>) read).error())
        .containsExactly(
            "loss.percent must be from 0.0 to 50.0, but was 60",
            "protocols has an unknown value: http4");
    assertThatThrownBy(() -> new ModelSettings.Windows(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ModelSettings.Loss(51, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ModelSettings.Trials(0, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static long pageRoundTrips(PageLoadModel model, Protocol protocol, ConnectionKind kind) {
    return Math.round(model.summarize(protocol, kind).page().p50() / ROUND_TRIP);
  }

  private static ModelSettings settings(String file) throws Exception {
    return read(SettingsReader.load(Path.of(file)));
  }

  private static ModelSettings read(Properties properties) {
    return ModelSettings.from(new SettingsReader(properties)).discardError().orElseThrow();
  }
}

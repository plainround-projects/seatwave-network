package io.plainround.seatwave.network.model;

import io.plainround.seatwave.network.report.ConnectionKind;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Writes the page model's assumptions and its table as plain text. Thread-safe: it holds no state.
 */
public final class PageModelReport {

  // Light in glass fiber covers about 200 km per millisecond, two thirds of its speed in a vacuum.
  private static final double FIBER_KM_PER_MILLI = 200.0;
  private static final String HEADER =
      "protocol                 connection  setup RTTs  page p50  page p95  RTTs at p50"
          + "  small p50  small p95  largest p50  largest p95  held back %";
  private static final String ROW =
      "%-24s %-10s %11d %9.0f %9.0f %12.1f %10.0f %10.0f %12.0f %12.0f %12.1f%n";

  private PageModelReport() {}

  /**
   * Runs the model for every protocol, over new and over reused connections, and writes the table.
   *
   * @param settings the model's inputs
   * @return the text
   */
  public static String render(ModelSettings settings) {
    Objects.requireNonNull(settings, "settings");
    PageLoadModel model = new PageLoadModel(settings);
    StringBuilder text = new StringBuilder(assumptions(settings));
    text.append(HEADER).append(System.lineSeparator());
    for (ConnectionKind kind : List.of(ConnectionKind.NEW, ConnectionKind.REUSED)) {
      for (Protocol protocol : settings.protocols()) {
        PageLoadModel.Summary summary = model.summarize(protocol, kind);
        text.append(
            String.format(
                Locale.ROOT,
                ROW,
                protocol.label(),
                kind.name().toLowerCase(Locale.ROOT),
                summary.setupRoundTrips(),
                summary.page().p50(),
                summary.page().p95(),
                summary.page().p50() / settings.roundTripMillis(),
                summary.smallResponses().p50(),
                summary.smallResponses().p95(),
                summary.largestResponse().p50(),
                summary.largestResponse().p95(),
                summary.heldBackPercent()));
      }
    }
    return text.toString();
  }

  private static String assumptions(ModelSettings settings) {
    double floorMillis = 2 * settings.distanceKm() / FIBER_KM_PER_MILLI;
    return String.format(
        Locale.ROOT,
        "MODEL, not a measurement. Distance %,d km: the speed-of-light floor in fiber is %.1f ms"
            + " round trip; the model uses %.1f ms.%n"
            + "Page: %d responses of %s bytes; %d-byte segments; window %d segments new, %d"
            + " reused; TLS %d round trip(s) over TCP.%n"
            + "Loss %.1f percent per packet; repair after %.0f ms (3 packets behind) or %.0f ms"
            + " (tail); %,d pages per row, seed %d. Times in ms after the click.%n%n",
        settings.distanceKm(),
        floorMillis,
        settings.roundTripMillis(),
        settings.responseBytes().size(),
        settings.responseBytes().stream()
            .map(String::valueOf)
            .reduce((first, second) -> first + ", " + second)
            .orElseThrow(),
        settings.segmentBytes(),
        settings.windows().initialSegments(),
        settings.windows().warmSegments(),
        settings.tlsRoundTrips(),
        settings.loss().percent(),
        settings.loss().fastRecoveryMillis(),
        settings.loss().tailRecoveryMillis(),
        settings.trials().count(),
        settings.trials().seed());
  }
}

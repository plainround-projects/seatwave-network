package io.plainround.seatwave.network.model;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import java.util.List;
import java.util.Objects;

/**
 * The inputs of the page model, read from a settings file with no defaults. Every value is an
 * assumption the report prints, not a measurement.
 *
 * @param distanceKm the straight-line distance to the fan, for the speed-of-light floor
 * @param roundTripMillis the round-trip time the model uses
 * @param responseBytes the size of each response of the page, in request order
 * @param segmentBytes the payload of one TCP segment or QUIC packet
 * @param windows the congestion windows of a new and of a reused connection
 * @param tlsRoundTrips the TLS handshake's round trips over TCP: 1 for TLS 1.3, 2 for TLS 1.2
 * @param loss how often a packet is lost and how long each kind of recovery takes
 * @param trials how many pages are simulated per protocol, and the seed of the random losses
 * @param protocols the protocols to compare, in order
 */
public record ModelSettings(
    int distanceKm,
    double roundTripMillis,
    List<Integer> responseBytes,
    int segmentBytes,
    Windows windows,
    int tlsRoundTrips,
    Loss loss,
    Trials trials,
    List<Protocol> protocols) {

  private static final int MAX_DISTANCE_KM = 40_000;
  private static final int MAX_ROUND_TRIP_MILLIS = 2_000;
  private static final int MAX_RESPONSE_BYTES = 100_000_000;
  // A segment larger than a jumbo frame's payload is a typo.
  private static final int MAX_SEGMENT_BYTES = 9_000;
  private static final int MAX_WINDOW_SEGMENTS = 100_000;
  private static final int MAX_TLS_ROUND_TRIPS = 2;
  // Half the packets lost is no longer a network a page can load over.
  private static final double MAX_LOSS_PERCENT = 50;
  private static final int MAX_RECOVERY_MILLIS = 10_000;
  private static final int MAX_TRIALS = 1_000_000;

  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if a value is outside its range
   */
  public ModelSettings {
    Objects.requireNonNull(windows, "windows");
    Objects.requireNonNull(loss, "loss");
    Objects.requireNonNull(trials, "trials");
    responseBytes = List.copyOf(responseBytes);
    protocols = List.copyOf(protocols);
    if (responseBytes.isEmpty() || protocols.isEmpty()) {
      throw new IllegalArgumentException("a page needs responses, and a run needs protocols");
    }
    if (roundTripMillis <= 0 || segmentBytes < 1 || distanceKm < 0) {
      throw new IllegalArgumentException("the round trip and segment must be positive");
    }
    if (tlsRoundTrips < 1 || tlsRoundTrips > MAX_TLS_ROUND_TRIPS) {
      throw new IllegalArgumentException("TLS takes 1 or 2 round trips");
    }
  }

  /**
   * Reads the settings.
   *
   * @param reader the settings reader
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<ModelSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    int distance = reader.intBetween("distance.km", 0, MAX_DISTANCE_KM);
    double roundTrip = reader.decimalBetween("rtt.millis", 1, MAX_ROUND_TRIP_MILLIS);
    List<Integer> responses = reader.intListBetween("response.bytes", 1, MAX_RESPONSE_BYTES);
    int segment = reader.intBetween("segment.bytes", 1, MAX_SEGMENT_BYTES);
    int initial = reader.intBetween("initial.window.segments", 1, MAX_WINDOW_SEGMENTS);
    int warm = reader.intBetween("warm.window.segments", 1, MAX_WINDOW_SEGMENTS);
    int tls = reader.intBetween("tls.handshake.round.trips", 1, MAX_TLS_ROUND_TRIPS);
    double lossPercent = reader.decimalBetween("loss.percent", 0, MAX_LOSS_PERCENT);
    double fast = reader.decimalBetween("fast.recovery.millis", 0, MAX_RECOVERY_MILLIS);
    double tail = reader.decimalBetween("tail.recovery.millis", 0, MAX_RECOVERY_MILLIS);
    int trialCount = reader.intBetween("trials", 1, MAX_TRIALS);
    int seed = reader.intBetween("random.seed", 0, Integer.MAX_VALUE);
    List<Protocol> protocols = reader.enumList("protocols", Protocol.class);
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new ModelSettings(
            distance,
            roundTrip,
            responses,
            segment,
            new Windows(initial, warm),
            tls,
            new Loss(lossPercent, fast, tail),
            new Trials(trialCount, seed),
            protocols));
  }

  /**
   * The congestion window, in segments, that the first round of data may use.
   *
   * @param initialSegments a new connection's window: 10 under RFC 6928
   * @param warmSegments a reused connection's window, already grown by earlier traffic
   */
  public record Windows(int initialSegments, int warmSegments) {

    /** Checks the fields. */
    public Windows {
      if (initialSegments < 1 || warmSegments < 1) {
        throw new IllegalArgumentException("a window holds at least one segment");
      }
    }
  }

  /**
   * How packets are lost and recovered.
   *
   * @param percent the chance that any one packet is lost, 0 to 50
   * @param fastRecoveryMillis the extra delay of a loss that three later packets reveal
   * @param tailRecoveryMillis the extra delay of a loss with fewer than three packets behind it
   */
  public record Loss(double percent, double fastRecoveryMillis, double tailRecoveryMillis) {

    /** Checks the fields. */
    public Loss {
      if (!(percent >= 0 && percent <= MAX_LOSS_PERCENT)
          || fastRecoveryMillis < 0
          || tailRecoveryMillis < 0) {
        throw new IllegalArgumentException("loss must be 0 to 50 percent, recovery 0 or more");
      }
    }
  }

  /**
   * How many pages to simulate.
   *
   * @param count pages per protocol and connection kind
   * @param seed the seed of the random losses, so two runs print the same numbers
   */
  public record Trials(int count, int seed) {

    /** Checks the fields. */
    public Trials {
      if (count < 1 || count > MAX_TRIALS) {
        throw new IllegalArgumentException("trials must be from 1 to 1,000,000");
      }
    }
  }
}

package io.plainround.seatwave.network.model;

import io.plainround.seatwave.network.report.ConnectionKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * A model of one page load: how many round trips each protocol needs, and how a lost packet delays
 * the responses behind it. It is arithmetic on a timeline, not a network measurement; Lab 3 of
 * Module 5 says how to measure the same thing with a real lossy link.
 *
 * <p>The rules, each a stated simplification:
 *
 * <ul>
 *   <li>Setup: a new TCP connection costs one round trip, then TLS costs one (TLS 1.3) or two (TLS
 *       1.2); a new QUIC connection costs one round trip for both. A reused connection costs none.
 *   <li>Slow start: the server sends one window of segments per round trip, and the window grows by
 *       one segment for every segment acknowledged (RFC 5681), so a full round doubles it and a
 *       round of two segments adds two. A new connection starts at the initial window; a reused one
 *       at the warm one. The fan's bandwidth is never the limit, and a loss does not shrink the
 *       window.
 *   <li>Loss: each packet is lost independently. A loss with at least three packets behind it in
 *       the same round is repaired after the fast-recovery delay (three duplicate ACKs, RFC 5681;
 *       the packet threshold of QUIC, RFC 9002); any other loss, and a lost repair, after the
 *       tail-recovery delay.
 *   <li>Delivery: TCP hands bytes to HTTP/1.1 and HTTP/2 only in order, so a lost packet holds back
 *       every later packet of its connection. QUIC hands each stream its own bytes in order, so a
 *       lost packet holds back only its own stream.
 *   <li>The browser asks for all responses at once (HTTP/2, HTTP/3, six connections), or one after
 *       another on one connection. A real page first fetches its document, which adds a round trip
 *       to every protocol alike.
 * </ul>
 *
 * <p>Thread-safe: it holds only immutable settings; each simulation uses its own random source.
 */
public final class PageLoadModel {

  // Three duplicate ACKs trigger a fast retransmit (RFC 5681); QUIC's packet threshold is also 3.
  private static final int PACKETS_BEHIND_FOR_FAST_RECOVERY = 3;
  private static final double PERCENT = 100.0;
  private static final double HALF = 0.5;
  private static final double NINETY_FIFTH = 0.95;

  private final ModelSettings settings;
  private final int largestResponse;
  private final double oneWayMillis;

  /**
   * Creates the model.
   *
   * @param settings the page, the network and the loss assumptions
   */
  public PageLoadModel(ModelSettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
    List<Integer> sizes = settings.responseBytes();
    int largest = 0;
    for (int responseIndex = 1; responseIndex < sizes.size(); responseIndex++) {
      if (sizes.get(responseIndex) > sizes.get(largest)) {
        largest = responseIndex;
      }
    }
    this.largestResponse = largest;
    this.oneWayMillis = settings.roundTripMillis() / 2;
  }

  /**
   * Returns the round trips a protocol spends before the first request can leave.
   *
   * @param protocol the protocol
   * @param kind a new or a reused connection
   * @return 0 for a reused connection; 1 for new QUIC; 1 plus the TLS round trips for new TCP
   */
  public int setupRoundTrips(Protocol protocol, ConnectionKind kind) {
    if (kind == ConnectionKind.REUSED) {
      return 0;
    }
    return switch (protocol) {
      case HTTP3 -> 1;
      case HTTP1_ONE_CONNECTION, HTTP1_SIX_CONNECTIONS, HTTP2 -> 1 + settings.tlsRoundTrips();
    };
  }

  /**
   * Simulates many pages and summarizes them.
   *
   * @param protocol the protocol
   * @param kind a new or a reused connection
   * @return p50 and p95 of the whole page, of the small responses and of the largest response
   */
  public Summary summarize(Protocol protocol, ConnectionKind kind) {
    int count = settings.trials().count();
    double[] pages = new double[count];
    double[] small = new double[count];
    double[] largest = new double[count];
    int heldBack = 0;
    // The same seed for every protocol: each one meets the same luck, so the gaps are its own.
    SplittableRandom random = new SplittableRandom(settings.trials().seed());
    for (int trialIndex = 0; trialIndex < count; trialIndex++) {
      PageTimes times = simulate(protocol, kind, random);
      pages[trialIndex] = times.pageMillis();
      small[trialIndex] = times.smallResponsesMillis();
      largest[trialIndex] = times.largestResponseMillis();
      heldBack += times.isHeldBackByAnotherResponse() ? 1 : 0;
    }
    return new Summary(
        protocol,
        kind,
        setupRoundTrips(protocol, kind),
        Percentiles.of(pages),
        Percentiles.of(small),
        Percentiles.of(largest),
        PERCENT * heldBack / count);
  }

  /**
   * Simulates one page.
   *
   * @param protocol the protocol
   * @param kind a new or a reused connection
   * @param random the source of the losses
   * @return when the page, its small responses and its largest response were complete
   */
  public PageTimes simulate(Protocol protocol, ConnectionKind kind, RandomGenerator random) {
    Objects.requireNonNull(random, "random");
    double requestArrives =
        setupRoundTrips(protocol, kind) * settings.roundTripMillis() + oneWayMillis;
    int window =
        kind == ConnectionKind.NEW
            ? settings.windows().initialSegments()
            : settings.windows().warmSegments();
    return switch (protocol) {
      case HTTP1_ONE_CONNECTION -> oneAfterAnother(requestArrives, window, random);
      case HTTP1_SIX_CONNECTIONS -> sideBySide(requestArrives, window, random);
      case HTTP2 ->
          times(
              delivered(send(interleaved(), requestArrives, window, random), Ordering.CONNECTION));
      case HTTP3 ->
          times(delivered(send(interleaved(), requestArrives, window, random), Ordering.STREAM));
    };
  }

  private PageTimes oneAfterAnother(double requestArrives, int window, RandomGenerator random) {
    int responses = settings.responseBytes().size();
    double[] done = new double[responses];
    double nextRequestArrives = requestArrives;
    int currentWindow = window;
    for (int responseIndex = 0; responseIndex < responses; responseIndex++) {
      Sent sent =
          send(segmentsOf(List.of(responseIndex)), nextRequestArrives, currentWindow, random);
      done[responseIndex] = delivered(sent, Ordering.CONNECTION).done()[responseIndex];
      currentWindow = sent.windowAfter();
      // The next request leaves when this reply is complete, and reaches the server later.
      nextRequestArrives = done[responseIndex] + oneWayMillis;
    }
    // One response at a time: a loss can only delay its own response, then everything after it.
    return times(new Delivery(done, false));
  }

  private PageTimes sideBySide(double requestArrives, int window, RandomGenerator random) {
    int responses = settings.responseBytes().size();
    double[] done = new double[responses];
    for (int responseIndex = 0; responseIndex < responses; responseIndex++) {
      Sent sent = send(segmentsOf(List.of(responseIndex)), requestArrives, window, random);
      done[responseIndex] = delivered(sent, Ordering.CONNECTION).done()[responseIndex];
    }
    // Six connections: each loss stays on its own connection.
    return times(new Delivery(done, false));
  }

  private PageTimes times(Delivery delivery) {
    double[] done = delivery.done();
    double page = Arrays.stream(done).max().orElseThrow();
    double small = 0;
    for (int responseIndex = 0; responseIndex < done.length; responseIndex++) {
      if (responseIndex != largestResponse) {
        small = Math.max(small, done[responseIndex]);
      }
    }
    // A page of one response has no small responses; the largest then stands for them.
    if (done.length == 1) {
      small = done[0];
    }
    return new PageTimes(page, small, done[largestResponse], delivery.isHeldBackByAnother());
  }

  private List<Integer> segmentsOf(List<Integer> responseIndexes) {
    List<Integer> owners = new ArrayList<>();
    for (int responseIndex : responseIndexes) {
      int count = segmentCount(responseIndex);
      for (int segmentIndex = 0; segmentIndex < count; segmentIndex++) {
        owners.add(responseIndex);
      }
    }
    return owners;
  }

  private List<Integer> interleaved() {
    // Streams share one connection round-robin, one segment each in turn, as HTTP/2 and HTTP/3
    // servers commonly do when every stream has the same priority.
    int responses = settings.responseBytes().size();
    int[] left = new int[responses];
    int total = 0;
    for (int responseIndex = 0; responseIndex < responses; responseIndex++) {
      left[responseIndex] = segmentCount(responseIndex);
      total += left[responseIndex];
    }
    List<Integer> owners = new ArrayList<>(total);
    while (owners.size() < total) {
      for (int responseIndex = 0; responseIndex < responses; responseIndex++) {
        if (left[responseIndex] > 0) {
          owners.add(responseIndex);
          left[responseIndex]--;
        }
      }
    }
    return owners;
  }

  private int segmentCount(int responseIndex) {
    int bytes = settings.responseBytes().get(responseIndex);
    return (bytes + settings.segmentBytes() - 1) / settings.segmentBytes();
  }

  private Sent send(
      List<Integer> owners, double requestArrives, int window, RandomGenerator random) {
    double[] arrivals = new double[owners.size()];
    double roundStarts = requestArrives;
    int currentWindow = window;
    int next = 0;
    while (next < owners.size()) {
      int inRound = Math.min(currentWindow, owners.size() - next);
      for (int positionInRound = 0; positionInRound < inRound; positionInRound++) {
        int packetsBehind = inRound - 1 - positionInRound;
        arrivals[next + positionInRound] =
            roundStarts + oneWayMillis + recoveryDelay(packetsBehind, random);
      }
      next += inRound;
      // The ACKs of this round come back one round trip later and open the next round; each one
      // grows the window by a segment, so the window grows only by what was really sent.
      roundStarts += settings.roundTripMillis();
      currentWindow = (int) Math.min((long) currentWindow + inRound, Integer.MAX_VALUE);
    }
    return new Sent(owners, arrivals, currentWindow);
  }

  private double recoveryDelay(int packetsBehind, RandomGenerator random) {
    double lossChance = settings.loss().percent() / PERCENT;
    double delay = 0;
    boolean isFirstAttempt = true;
    while (random.nextDouble() < lossChance) {
      boolean isRevealedByLaterPackets =
          isFirstAttempt && packetsBehind >= PACKETS_BEHIND_FOR_FAST_RECOVERY;
      delay +=
          isRevealedByLaterPackets
              ? settings.loss().fastRecoveryMillis()
              : settings.loss().tailRecoveryMillis();
      isFirstAttempt = false;
    }
    return delay;
  }

  private Delivery delivered(Sent sent, Ordering ordering) {
    int responses = settings.responseBytes().size();
    double[] done = new double[responses];
    double[] streamHandedOver = new double[responses];
    double connectionHandedOver = 0;
    int connectionLatestOwner = -1;
    boolean isHeldBackByAnother = false;
    for (int packetIndex = 0; packetIndex < sent.owners().size(); packetIndex++) {
      int owner = sent.owners().get(packetIndex);
      double arrival = sent.arrivals()[packetIndex];
      double handedOver;
      if (ordering == Ordering.CONNECTION) {
        // Head-of-line blocking: this packet is here, but TCP keeps it until an earlier,
        // later-arriving packet of another response is repaired.
        if (connectionHandedOver > arrival && connectionLatestOwner != owner) {
          isHeldBackByAnother = true;
        }
        if (arrival > connectionHandedOver) {
          connectionHandedOver = arrival;
          connectionLatestOwner = owner;
        }
        handedOver = connectionHandedOver;
      } else {
        streamHandedOver[owner] = Math.max(streamHandedOver[owner], arrival);
        handedOver = streamHandedOver[owner];
      }
      done[owner] = Math.max(done[owner], handedOver);
    }
    return new Delivery(done, isHeldBackByAnother);
  }

  private enum Ordering {
    CONNECTION,
    STREAM
  }

  private record Sent(List<Integer> owners, double[] arrivals, int windowAfter) {}

  private record Delivery(double[] done, boolean isHeldBackByAnother) {}

  /**
   * When one simulated page was complete, in milliseconds after the fan's click.
   *
   * @param pageMillis the last byte of the last response
   * @param smallResponsesMillis the last byte of every response but the largest
   * @param largestResponseMillis the last byte of the largest response
   * @param isHeldBackByAnotherResponse true if a packet that had arrived waited for a lost packet
   *     of another response: head-of-line blocking at the transport
   */
  public record PageTimes(
      double pageMillis,
      double smallResponsesMillis,
      double largestResponseMillis,
      boolean isHeldBackByAnotherResponse) {}

  /**
   * The 50th and 95th percentile of many simulated pages.
   *
   * @param p50 the median
   * @param p95 the value 95 out of 100 pages beat
   */
  public record Percentiles(double p50, double p95) {

    static Percentiles of(double[] values) {
      double[] sorted = values.clone();
      Arrays.sort(sorted);
      return new Percentiles(nearestRank(sorted, HALF), nearestRank(sorted, NINETY_FIFTH));
    }

    private static double nearestRank(double[] sorted, double fraction) {
      // Nearest rank: the smallest value with at least this fraction of values at or below it.
      int rank = (int) Math.ceil(fraction * sorted.length);
      return sorted[Math.max(rank, 1) - 1];
    }
  }

  /**
   * The model's answer for one protocol and connection kind.
   *
   * @param protocol the protocol
   * @param kind new or reused connections
   * @param setupRoundTrips round trips before the first request leaves
   * @param page the whole page
   * @param smallResponses every response but the largest
   * @param largestResponse the largest response, the seat map
   * @param heldBackPercent the share of pages with head-of-line blocking between responses
   */
  public record Summary(
      Protocol protocol,
      ConnectionKind kind,
      int setupRoundTrips,
      Percentiles page,
      Percentiles smallResponses,
      Percentiles largestResponse,
      double heldBackPercent) {}
}

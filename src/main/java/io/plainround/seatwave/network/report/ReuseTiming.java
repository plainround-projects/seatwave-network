package io.plainround.seatwave.network.report;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * One row of the keep-alive table: every run of one scenario with one requested HTTP version.
 *
 * @param scenario the scenario's name, for example {@code distant-tls}
 * @param requested the HTTP version the client asked for, for example {@code HTTP_2}
 * @param runs the runs, at least {@link Runs#MINIMUM_RUNS}, all answered with one protocol
 * @param connectionsOpened how many TCP connections the runs opened, counted by the link
 */
public record ReuseTiming(
    String scenario, String requested, List<ReuseSample> runs, int connectionsOpened) {

  /**
   * Creates the row.
   *
   * @throws IllegalArgumentException if there are too few runs, the runs used different protocols,
   *     or the count of connections is negative
   */
  public ReuseTiming {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(requested, "requested");
    runs = List.copyOf(runs);
    Runs.requireEnough(runs);
    if (runs.stream().map(ReuseSample::protocol).distinct().count() != 1) {
      throw new IllegalArgumentException("the runs of one row must use one protocol");
    }
    if (connectionsOpened < 0) {
      throw new IllegalArgumentException("the count of connections cannot be negative");
    }
  }

  /**
   * Returns the protocol the server answered with.
   *
   * @return for example {@code HTTP/1.1}
   */
  public String protocol() {
    return runs.getFirst().protocol();
  }

  /**
   * Returns the median time of a first request on a new connection.
   *
   * @return the median over all runs
   */
  public Duration medianNew() {
    return Runs.median(runs.stream().map(ReuseSample::newConnection).toList());
  }

  /**
   * Returns the median time of a request on a reused connection.
   *
   * @return the median over all runs
   */
  public Duration medianReused() {
    return Runs.median(runs.stream().map(ReuseSample::reusedConnection).toList());
  }
}

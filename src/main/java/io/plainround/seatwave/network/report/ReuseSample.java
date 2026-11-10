package io.plainround.seatwave.network.report;

import java.time.Duration;
import java.util.Objects;

/**
 * One run of the Java timing client: a first request on a new connection, then a second request on
 * the same, reused connection.
 *
 * @param newConnection the first request's time, handshakes included
 * @param reusedConnection the second request's time on the open connection
 * @param protocol the HTTP version the server answered with, for example {@code HTTP/1.1}
 */
public record ReuseSample(Duration newConnection, Duration reusedConnection, String protocol) {

  /**
   * Creates the sample.
   *
   * @throws IllegalArgumentException if a time is negative or the protocol is blank
   */
  public ReuseSample {
    Objects.requireNonNull(newConnection, "newConnection");
    Objects.requireNonNull(reusedConnection, "reusedConnection");
    Objects.requireNonNull(protocol, "protocol");
    if (newConnection.isNegative() || reusedConnection.isNegative()) {
      throw new IllegalArgumentException("a time cannot be negative");
    }
    if (protocol.isBlank()) {
      throw new IllegalArgumentException("the protocol is blank");
    }
  }
}

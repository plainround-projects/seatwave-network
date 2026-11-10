package io.plainround.seatwave.network.link;

import java.time.Duration;
import java.util.Objects;

/**
 * How a {@link DelayLink} behaves.
 *
 * @param oneWayDelay the delay added in each direction; the round-trip time is twice this
 * @param handshakeDelay whether the first client bytes also pay the TCP handshake's round trip
 * @param maxConnections how many connections may be open at once; more are closed at once
 */
public record LinkSettings(
    Duration oneWayDelay, HandshakeDelay handshakeDelay, int maxConnections) {

  // A one-way delay above 10 seconds is not a network; it is a typo.
  private static final Duration LONGEST_ONE_WAY_DELAY = Duration.ofSeconds(10);

  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if the delay is negative or too long, or no connection is
   *     allowed
   */
  public LinkSettings {
    Objects.requireNonNull(oneWayDelay, "oneWayDelay");
    Objects.requireNonNull(handshakeDelay, "handshakeDelay");
    if (oneWayDelay.isNegative() || oneWayDelay.compareTo(LONGEST_ONE_WAY_DELAY) > 0) {
      throw new IllegalArgumentException("the one-way delay must be from 0 to 10 seconds");
    }
    if (maxConnections < 1) {
      throw new IllegalArgumentException("at least one connection must be allowed");
    }
  }

  /**
   * Returns the round-trip time the link adds.
   *
   * @return twice the one-way delay
   */
  public Duration roundTrip() {
    return oneWayDelay.multipliedBy(2);
  }
}

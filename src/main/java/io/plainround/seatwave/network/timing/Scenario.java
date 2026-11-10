package io.plainround.seatwave.network.timing;

import io.plainround.seatwave.network.link.HandshakeDelay;
import java.time.Duration;
import java.util.Objects;

/**
 * One way of reaching SeatWave, for example plain HTTP on this machine, or HTTPS to a fan 80 ms
 * away.
 *
 * @param name a short name for the report, for example {@code distant-tls}
 * @param scheme plain HTTP or HTTPS
 * @param oneWayDelay the delay the link adds in each direction; zero for this machine
 * @param handshakeDelay whether the link also charges the TCP handshake's round trip
 */
public record Scenario(
    String name, Scheme scheme, Duration oneWayDelay, HandshakeDelay handshakeDelay) {

  /** Checks the fields. */
  public Scenario {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(scheme, "scheme");
    Objects.requireNonNull(oneWayDelay, "oneWayDelay");
    Objects.requireNonNull(handshakeDelay, "handshakeDelay");
    if (name.isBlank()) {
      throw new IllegalArgumentException("a scenario needs a name");
    }
  }
}

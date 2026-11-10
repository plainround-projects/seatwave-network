package io.plainround.seatwave.network.tls;

import java.util.Objects;

/**
 * What happened when the client tried one scenario.
 *
 * @param scenario the scenario
 * @param isAccepted true if the handshake completed and the request was answered
 * @param detail the TLS version and cipher suite when accepted, or the client's reason to refuse
 */
public record HandshakeOutcome(TlsScenario scenario, boolean isAccepted, String detail) {

  /** Checks the fields. */
  public HandshakeOutcome {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(detail, "detail");
  }
}

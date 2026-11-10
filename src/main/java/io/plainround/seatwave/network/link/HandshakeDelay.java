package io.plainround.seatwave.network.link;

/**
 * Whether a {@link DelayLink} also charges the round trip of the TCP handshake.
 *
 * <p>The operating system completes the TCP handshake with the link itself, on the loopback
 * address, before the link sees the connection, so the link cannot delay the handshake. With {@link
 * #ONE_ROUND_TRIP} it holds the first bytes the client sends one extra round trip instead, so the
 * total time of a new connection matches a real distant one. A timing tool then shows that round
 * trip inside the next phase (TLS, or the first byte), not as "connect".
 */
public enum HandshakeDelay {
  /** The link adds nothing for the TCP handshake: a connect through it costs no round trip. */
  NONE,
  /** The first client bytes wait one extra round trip, standing for the TCP handshake. */
  ONE_ROUND_TRIP
}

package io.plainround.seatwave.network.timing;

/** Plain HTTP or HTTP over TLS. */
public enum Scheme {
  /** Plain HTTP: no TLS handshake, nothing hidden or protected. */
  HTTP,
  /** HTTP over TLS. */
  HTTPS
}

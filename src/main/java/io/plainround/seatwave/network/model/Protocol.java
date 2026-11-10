package io.plainround.seatwave.network.model;

/** The four ways a browser can fetch one page, as the page model compares them. */
public enum Protocol {
  /** HTTP/1.1 on one keep-alive connection: one request at a time, each after the last reply. */
  HTTP1_ONE_CONNECTION("HTTP/1.1, 1 connection"),
  /** HTTP/1.1 on six connections in parallel, one request each: what browsers do. */
  HTTP1_SIX_CONNECTIONS("HTTP/1.1, 6 connections"),
  /** HTTP/2: every request at once as streams on one TCP connection, delivered in TCP order. */
  HTTP2("HTTP/2"),
  /** HTTP/3: every request at once as streams on one QUIC connection, each stream in its order. */
  HTTP3("HTTP/3");

  private final String label;

  Protocol(String label) {
    this.label = label;
  }

  /**
   * Returns the name a report prints.
   *
   * @return for example {@code HTTP/1.1, 6 connections}
   */
  public String label() {
    return label;
  }
}

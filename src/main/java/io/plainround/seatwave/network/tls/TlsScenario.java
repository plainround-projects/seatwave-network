package io.plainround.seatwave.network.tls;

/**
 * The four handshakes of failure mode 4 (Module 5, Section 9): one that must succeed and three that
 * a correct client must refuse.
 */
public enum TlsScenario {
  /** A certificate signed by the trusted CA, in date, for 127.0.0.1: accepted. */
  VALID("seatwave-valid.p12"),
  /** Signed by the trusted CA and for the right name, but it expired two days ago: refused. */
  EXPIRED("seatwave-expired.p12"),
  /** Signed by the trusted CA and in date, but only for seatwave.test: refused at 127.0.0.1. */
  WRONG_NAME("seatwave-wrong-name.p12"),
  /** The valid certificate, but the client offers only TLS 1.2 to a TLS 1.3 server: refused. */
  OLD_VERSION("seatwave-valid.p12");

  private final String keystoreFile;

  TlsScenario(String keystoreFile) {
    this.keystoreFile = keystoreFile;
  }

  /**
   * Returns the file name of the server keystore, inside the TLS folder.
   *
   * @return for example {@code seatwave-expired.p12}
   */
  public String keystoreFile() {
    return keystoreFile;
  }
}

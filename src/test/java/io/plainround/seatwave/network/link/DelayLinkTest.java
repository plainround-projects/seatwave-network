package io.plainround.seatwave.network.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.network.server.SeatWaveServer;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DelayLinkTest {

  private static final Duration ONE_WAY = Duration.ofMillis(20);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final int SOCKET_TIMEOUT_MILLIS = 5_000;

  @Test
  void addsAtLeastOneRoundTripToARequestAndCountsTheConnection() throws Exception {
    try (SeatWaveServer server = SeatWaveServer.startPlain(0);
        DelayLink link =
            DelayLink.start(0, server.port(), new LinkSettings(ONE_WAY, HandshakeDelay.NONE, 4));
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
      long startNanos = System.nanoTime();

      HttpResponse<byte[]> response =
          client.send(request(link), HttpResponse.BodyHandlers.ofByteArray());

      Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).hasSize(36_894);
      // A lower bound only: the link must have held the request and the reply 20 ms each.
      assertThat(elapsed).isGreaterThanOrEqualTo(ONE_WAY.multipliedBy(2));
      assertThat(link.connectionsAccepted()).isEqualTo(1);
    }
  }

  @Test
  void chargesTheHandshakesRoundTripToTheFirstBytesOfANewConnection() throws Exception {
    try (SeatWaveServer server = SeatWaveServer.startPlain(0);
        DelayLink link =
            DelayLink.start(
                0, server.port(), new LinkSettings(ONE_WAY, HandshakeDelay.ONE_ROUND_TRIP, 4));
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
      long startNanos = System.nanoTime();

      client.send(request(link), HttpResponse.BodyHandlers.discarding());

      // The handshake's round trip plus the request's: at least two round trips of 40 ms.
      assertThat(Duration.ofNanos(System.nanoTime() - startNanos))
          .isGreaterThanOrEqualTo(ONE_WAY.multipliedBy(4));
    }
  }

  @Test
  void closesAConnectionOverTheLimitAtOnce() throws Exception {
    try (SeatWaveServer server = SeatWaveServer.startPlain(0);
        DelayLink link =
            DelayLink.start(
                0, server.port(), new LinkSettings(Duration.ZERO, HandshakeDelay.NONE, 1));
        Socket first = connect(link);
        Socket second = connect(link)) {
      InputStream secondIn = second.getInputStream();

      // The link closes the second connection without a byte: the read sees the end at once.
      assertThat(secondIn.read()).isEqualTo(-1);
      assertThat(link.connectionsRefused()).isEqualTo(1);
      assertThat(first.isConnected()).isTrue();
    }
  }

  @Test
  void endsTheClientsConnectionWhenTheUpstreamIsNotListening() throws Exception {
    int deadPort;
    try (ServerSocket reserved = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      deadPort = reserved.getLocalPort();
    }
    try (DelayLink link =
            DelayLink.start(0, deadPort, new LinkSettings(Duration.ZERO, HandshakeDelay.NONE, 1));
        Socket client = connect(link)) {

      assertThat(client.getInputStream().read()).isEqualTo(-1);
      assertThat(link.connectionsFailed()).isEqualTo(1);
    }
  }

  @Test
  void refusesSettingsThatAreNotALink() {
    assertThatThrownBy(() -> new LinkSettings(Duration.ofMillis(-1), HandshakeDelay.NONE, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LinkSettings(Duration.ofSeconds(11), HandshakeDelay.NONE, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LinkSettings(Duration.ZERO, HandshakeDelay.NONE, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new LinkSettings(ONE_WAY, HandshakeDelay.NONE, 1).roundTrip())
        .isEqualTo(Duration.ofMillis(40));
  }

  private static HttpRequest request(DelayLink link) {
    return HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + link.port() + "/events/1/seats?section=A"))
        .timeout(TIMEOUT)
        .build();
  }

  private static Socket connect(DelayLink link) throws Exception {
    Socket socket = new Socket(InetAddress.getLoopbackAddress(), link.port());
    socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
    return socket;
  }
}

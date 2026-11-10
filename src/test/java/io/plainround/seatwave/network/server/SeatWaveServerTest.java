package io.plainround.seatwave.network.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.network.tls.TestTls;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SeatWaveServerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static SeatWaveServer server;
  private static HttpClient client;

  @BeforeAll
  static void start() throws Exception {
    server = SeatWaveServer.startPlain(0);
    client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  @AfterAll
  static void stop() {
    client.close();
    server.close();
  }

  @Test
  void servesTheSeatMapWithItsServerTime() throws Exception {
    HttpResponse<byte[]> response = get("events/1/seats?section=A");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).hasSize(36_894);
    assertThat(response.headers().firstValue("Server-Timing"))
        .hasValueSatisfying(value -> assertThat(value).matches("app;dur=\\d+\\.\\d{2}"));
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
  }

  @Test
  void servesTheEventListAndHealth() throws Exception {
    assertThat(get("events").statusCode()).isEqualTo(200);
    assertThat(new String(get("health").body(), java.nio.charset.StandardCharsets.UTF_8))
        .isEqualTo("{\"status\":\"up\"}");
  }

  @Test
  void answersHeadWithTheLengthAndNoBody() throws Exception {
    HttpRequest head =
        HttpRequest.newBuilder(server.baseUri().resolve("events/1/seats?section=B"))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .timeout(TIMEOUT)
            .build();

    HttpResponse<byte[]> response = client.send(head, HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().firstValue("Content-Length")).hasValue("36894");
  }

  @Test
  void refusesABadSectionBeforeAnyWork() throws Exception {
    for (String query : List.of("section=a", "section=AB", "section=", "seat=A", "")) {
      HttpResponse<byte[]> response = get("events/1/seats" + (query.isEmpty() ? "" : "?" + query));

      assertThat(response.statusCode()).as(query).isEqualTo(400);
      assertThat(response.headers().firstValue("Content-Type"))
          .hasValue("application/problem+json");
    }
  }

  @Test
  void answers404ForAnUnknownEventOrPath() throws Exception {
    assertThat(get("events/2/seats?section=A").statusCode()).isEqualTo(404);
    assertThat(get("holds").statusCode()).isEqualTo(404);
    assertThat(get("events?page=2").statusCode()).isEqualTo(404);
  }

  @Test
  void refusesAWriteWith405AndSaysWhatIsAllowed() throws Exception {
    HttpRequest post =
        HttpRequest.newBuilder(server.baseUri().resolve("events"))
            .POST(HttpRequest.BodyPublishers.ofString("x"))
            .timeout(TIMEOUT)
            .build();

    HttpResponse<byte[]> response = client.send(post, HttpResponse.BodyHandlers.ofByteArray());

    assertThat(response.statusCode()).isEqualTo(405);
    assertThat(response.headers().firstValue("Allow")).hasValue("GET, HEAD");
  }

  @Test
  void listensOnTheLoopbackAddressOnly() {
    assertThat(server.baseUri().getHost()).isEqualTo("127.0.0.1");
    assertThat(server.baseUri().getScheme()).isEqualTo("http");
  }

  @Test
  void speaksHttp11OverTlsEvenWhenTheClientAsksForHttp2() throws Exception {
    try (SeatWaveServer tls =
            SeatWaveServer.startTls(0, TestTls.validServer(), List.of("TLSv1.3"));
        HttpClient tlsClient =
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .sslContext(TestTls.client())
                .connectTimeout(TIMEOUT)
                .build()) {
      HttpRequest request =
          HttpRequest.newBuilder(tls.baseUri().resolve("health")).timeout(TIMEOUT).build();

      HttpResponse<String> response = tlsClient.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(tls.baseUri().getScheme()).isEqualTo("https");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
      assertThat(response.sslSession())
          .hasValueSatisfying(session -> assertThat(session.getProtocol()).isEqualTo("TLSv1.3"));
    }
  }

  @Test
  void refusesToStartTlsWithNoVersion() {
    assertThatThrownBy(() -> SeatWaveServer.startTls(0, TestTls.validServer(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static HttpResponse<byte[]> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(server.baseUri().resolve(URI.create(path))).timeout(TIMEOUT).build();
    return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
  }
}

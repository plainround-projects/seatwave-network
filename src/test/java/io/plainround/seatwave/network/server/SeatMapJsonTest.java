package io.plainround.seatwave.network.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SeatMapJsonTest {

  // Module 1's sample output: curl ... | wc -c printed 36894 for section A.
  private static final int MODULE_ONE_SEAT_MAP_BYTES = 36_894;

  @Test
  void buildsModuleOnesSeatMapByteForByte() {
    String seats = new String(SeatMapJson.seats("A"), StandardCharsets.UTF_8);

    assertThat(seats.getBytes(StandardCharsets.UTF_8)).hasSize(MODULE_ONE_SEAT_MAP_BYTES);
    assertThat(seats)
        .startsWith(
            "[{\"seat\":\"A-1\",\"state\":\"available\"},{\"seat\":\"A-10\",\"state\":\"available\"},"
                + "{\"seat\":\"A-100\",\"state\":\"available\"},{\"seat\":\"A-1000\"")
        .endsWith("{\"seat\":\"A-999\",\"state\":\"available\"}]");
  }

  @Test
  void buildsModuleOnesEventList() {
    assertThat(new String(SeatMapJson.events(), StandardCharsets.UTF_8))
        .isEqualTo("[{\"eventId\":1,\"name\":\"Arena Night\",\"startsAt\":1796761251783}]");
  }

  @Test
  void acceptsOnlyOneCapitalLetterAsASection() {
    assertThat(SeatMapJson.isValidSection("Z")).isTrue();
    assertThat(SeatMapJson.isValidSection("a")).isFalse();
    assertThat(SeatMapJson.isValidSection("AB")).isFalse();
    assertThat(SeatMapJson.isValidSection("")).isFalse();
    assertThatThrownBy(() -> SeatMapJson.seats("A' OR 1=1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

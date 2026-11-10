package io.plainround.seatwave.network.server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The two read bodies of SeatWave version 0 (Module 1), byte for byte: the event list and the seat
 * map of one section, every seat available.
 *
 * <p>Module 1 read these from its database. This module studies the bytes on the wire, not the
 * database, so the bodies are built in memory once; their size and shape are what matter here.
 * Thread-safe: it holds no state.
 */
public final class SeatMapJson {

  /** Module 1's one event. */
  public static final int EVENT_ID = 1;

  // Module 1 seeded 1,000 seats per section, labelled A-1 to A-1000.
  private static final int SEATS_PER_SECTION = 1_000;
  // Module 1 printed this start time in its sample output; it is reused so the body is the same.
  private static final long EVENT_STARTS_AT_EPOCH_MILLIS = 1_796_761_251_783L;
  // A section is one capital letter, as in Module 1's section=A.
  private static final Pattern SECTION = Pattern.compile("[A-Z]");

  private SeatMapJson() {}

  /**
   * Checks a section name against the allow-list: exactly one capital letter.
   *
   * @param section the text from the query string
   * @return true if the section is valid
   */
  public static boolean isValidSection(String section) {
    Objects.requireNonNull(section, "section");
    return SECTION.matcher(section).matches();
  }

  /**
   * Returns the event list.
   *
   * @return the UTF-8 bytes of the JSON array with Module 1's one event
   */
  public static byte[] events() {
    String json =
        String.format(
            Locale.ROOT,
            "[{\"eventId\":%d,\"name\":\"Arena Night\",\"startsAt\":%d}]",
            EVENT_ID,
            EVENT_STARTS_AT_EPOCH_MILLIS);
    return json.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Returns the seat map of one section, every seat available.
   *
   * @param section one capital letter
   * @return the UTF-8 bytes of the JSON array, in Module 1's order (labels sorted as text)
   * @throws IllegalArgumentException if the section is not one capital letter
   */
  public static byte[] seats(String section) {
    if (!isValidSection(section)) {
      throw new IllegalArgumentException("a section is one capital letter, but was " + section);
    }
    List<String> labels = new ArrayList<>(SEATS_PER_SECTION);
    for (int seatNumber = 1; seatNumber <= SEATS_PER_SECTION; seatNumber++) {
      labels.add(section + "-" + seatNumber);
    }
    // Module 1's database returned seats ordered by their text label: A-1, A-10, A-100, A-1000,
    // A-101.
    Collections.sort(labels);
    StringBuilder json = new StringBuilder("[");
    for (String label : labels) {
      if (json.length() > 1) {
        json.append(',');
      }
      json.append("{\"seat\":\"").append(label).append("\",\"state\":\"available\"}");
    }
    return json.append(']').toString().getBytes(StandardCharsets.UTF_8);
  }
}

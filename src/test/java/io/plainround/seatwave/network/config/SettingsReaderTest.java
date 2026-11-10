package io.plainround.seatwave.network.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.network.model.Protocol;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsReaderTest {

  private static final int TEN = 10;

  @Test
  void readsEveryKindOfValue() {
    SettingsReader reader =
        reader(
            "count",
            "7",
            "rates",
            "200, 320",
            "wait.millis",
            "40",
            "share",
            "0.05",
            "modes",
            "http2,http3");

    assertThat(reader.intBetween("count", 1, TEN)).isEqualTo(7);
    assertThat(reader.intListBetween("rates", 1, Integer.MAX_VALUE)).containsExactly(200, 320);
    assertThat(reader.millisBetween("wait.millis", 0, Integer.MAX_VALUE))
        .isEqualTo(Duration.ofMillis(40));
    assertThat(reader.decimalBetween("share", 0, 1)).isEqualTo(0.05);
    assertThat(reader.enumList("modes", Protocol.class))
        .containsExactly(Protocol.HTTP2, Protocol.HTTP3);
    assertThat(reader.problems()).isEmpty();
  }

  @Test
  void reportsEveryProblemInOneRun() {
    SettingsReader reader =
        reader(
            "count",
            "70",
            "word",
            "seven",
            "rates",
            "200,,320",
            "share",
            "2",
            "modes",
            "green",
            "list",
            "1,x",
            "big",
            "1,99",
            "decimal",
            "half");

    reader.intBetween("count", 1, TEN);
    reader.intBetween("word", 1, TEN);
    reader.intBetween("absent", 1, TEN);
    reader.intListBetween("rates", 1, TEN);
    reader.intListBetween("list", 1, TEN);
    reader.intListBetween("big", 1, TEN);
    reader.decimalBetween("share", 0, 1);
    reader.decimalBetween("decimal", 0, 1);
    reader.enumList("modes", Protocol.class);

    assertThat(reader.problems())
        .containsExactly(
            "count must be from 1 to 10, but was 70",
            "word must be a whole number, but was seven",
            "absent is missing",
            "rates has an empty item",
            "list must list whole numbers, but one was x",
            "every value of big must be from 1 to 10, but one was 99",
            "share must be from 0.0 to 1.0, but was 2",
            "decimal must be a decimal number, but was half",
            "modes has an unknown value: green");
  }

  @Test
  void readsWordsPatternsAndSingleConstants() {
    SettingsReader reader =
        reader("versions", "TLSv1.3, TLSv1.2", "path", "/events", "mode", "http3", "bad", "/x y");

    assertThat(reader.wordsIn("versions", List.of("TLSv1.3", "TLSv1.2")))
        .containsExactly("TLSv1.3", "TLSv1.2");
    assertThat(reader.textMatching("path", java.util.regex.Pattern.compile("/[a-z]+")))
        .isEqualTo("/events");
    assertThat(reader.enumValue("mode", Protocol.class, Protocol.HTTP2)).isEqualTo(Protocol.HTTP3);
    assertThat(reader.problems()).isEmpty();
    assertThat(reader.textMatching("bad", java.util.regex.Pattern.compile("/[a-z]+"))).isEmpty();
    assertThat(reader.wordsIn("versions", List.of("TLSv1.3"))).isEmpty();
    assertThat(reader.enumValue("versions", Protocol.class, Protocol.HTTP2))
        .isEqualTo(Protocol.HTTP2);
    assertThat(reader.textMatching("absent", java.util.regex.Pattern.compile("x"))).isEmpty();
    assertThat(reader.problems()).hasSize(4);
  }

  @Test
  void refusesTwoConstantsWhereOneIsExpected() {
    SettingsReader reader = reader("mode", "http2,http3");

    assertThat(reader.enumValue("mode", Protocol.class, Protocol.HTTP2)).isEqualTo(Protocol.HTTP2);
    assertThat(reader.problems()).containsExactly("mode takes one value, but has 2");
  }

  @Test
  void loadsAFileAsUtf8(@TempDir Path folder) throws Exception {
    Path file = folder.resolve("settings.properties");
    Files.writeString(file, "# a comment\nname=Arena Night\n", StandardCharsets.UTF_8);

    Properties loaded = SettingsReader.load(file);

    assertThat(new SettingsReader(loaded).text("name")).isEqualTo("Arena Night");
  }

  private static SettingsReader reader(String... keysAndValues) {
    Properties properties = new Properties();
    List<String> pairs = List.of(keysAndValues);
    for (int pairIndex = 0; pairIndex < pairs.size(); pairIndex += 2) {
      properties.setProperty(pairs.get(pairIndex), pairs.get(pairIndex + 1));
    }
    return new SettingsReader(properties);
  }
}

package io.plainround.seatwave.network.model;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Command-line entry point of the page model: round trips per protocol at zero loss, and the effect
 * of packet loss on each protocol. Exit status 2 means bad usage or settings.
 */
public final class PageModelCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;

  private PageModelCli() {}

  /**
   * Prints the model's table.
   *
   * @param arguments the settings file
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: pageModel <page-model.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (ModelSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<ModelSettings, List<String>>(ModelSettings settings) ->
            System.out.print(PageModelReport.render(settings));
        case Result.Error<ModelSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid setting: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException unreadable) {
      System.err.println("cannot read " + arguments[0] + ": " + unreadable.getMessage());
      System.exit(EXIT_BAD_USAGE);
    }
  }
}

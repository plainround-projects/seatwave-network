package io.plainround.seatwave.network.report;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line entry point of the report: reads the files {@code scripts/curl-timings.sh} wrote and
 * prints the phase table. Exit status 2 means bad usage or an unreadable file; 1 means at least one
 * line or group was refused, and the table says which.
 */
public final class ReportCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_REFUSED_LINES = 1;

  private ReportCli() {}

  /**
   * Prints the report.
   *
   * @param arguments one or more files of curl timing lines
   */
  public static void main(String[] arguments) {
    if (arguments.length == 0) {
      System.err.println("usage: report <curl-timings.txt>...");
      System.exit(EXIT_BAD_USAGE);
    }
    List<CurlSample> samples = new ArrayList<>();
    List<CurlProblem> problems = new ArrayList<>();
    for (String argument : arguments) {
      try {
        for (String line : Files.readAllLines(Path.of(argument), StandardCharsets.UTF_8)) {
          if (line.isBlank() || line.startsWith("#")) {
            continue;
          }
          switch (CurlLine.parse(line)) {
            case Result.Success<CurlSample, CurlProblem>(CurlSample sample) -> samples.add(sample);
            case Result.Error<CurlSample, CurlProblem>(CurlProblem problem) ->
                problems.add(problem);
          }
        }
      } catch (IOException unreadable) {
        System.err.println("cannot read " + argument + ": " + unreadable.getMessage());
        System.exit(EXIT_BAD_USAGE);
      }
    }
    System.out.print(TimingReport.renderPhases(samples, problems));
    if (!problems.isEmpty() || !TimingReport.group(samples).refused().isEmpty()) {
      System.exit(EXIT_REFUSED_LINES);
    }
  }
}

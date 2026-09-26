package ww86.hocon_fmt.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.DirectoryScanner;

/** What both goals share: which files to look at, and what the formatter makes of each. */
abstract class HoconFormatterMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
  private File baseDirectory;

  /** Files to examine, as Ant-style patterns relative to the project directory. */
  @Parameter(defaultValue = "src/**/*.conf,src/**/*.hocon")
  private String[] includes;

  /** Files to leave out of those matched by {@code includes}, in the same form. */
  @Parameter
  private String[] excludes;

  @Parameter(property = "hocon-formatter.skip", defaultValue = "false")
  private boolean skip;

  /** A matching file, the path it is reported under, and what the formatter makes of it. */
  record Examined(Path file, String relativePath, Outcome outcome) {}

  @Override
  public final void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("HOCON formatting skipped.");
      return;
    }

    var examined = new ArrayList<Examined>();
    for (String relativePath : matchingFiles()) {
      Path file = baseDirectory.toPath().resolve(relativePath);
      Outcome outcome = Outcome.of(read(file, relativePath));
      if (outcome instanceof Outcome.Refused refused) {
        getLog().warn("Leaving " + relativePath + " unchanged: " + refused.reason());
      }
      examined.add(new Examined(file, relativePath, outcome));
    }
    actOn(examined);
  }

  /** Receives every matching file; the ones the formatter refused are already reported. */
  abstract void actOn(List<Examined> examined)
      throws MojoExecutionException, MojoFailureException;

  static String summary(List<Examined> examined, String needsFormattingAs) {
    return "HOCON files: %d %s, %d already formatted, %d refused."
        .formatted(
            count(examined, Outcome.NeedsFormatting.class),
            needsFormattingAs,
            count(examined, Outcome.AlreadyFormatted.class),
            count(examined, Outcome.Refused.class));
  }

  private static long count(List<Examined> examined, Class<? extends Outcome> kind) {
    return examined.stream().filter(file -> kind.isInstance(file.outcome())).count();
  }

  /** Sorted, so the log lists files in the same order on every run and every file system. */
  private List<String> matchingFiles() {
    var scanner = new DirectoryScanner();
    scanner.setBasedir(baseDirectory);
    scanner.setIncludes(includes);
    scanner.setExcludes(excludes);
    scanner.scan();
    return Arrays.stream(scanner.getIncludedFiles()).sorted().toList();
  }

  private static byte[] read(Path file, String relativePath) throws MojoExecutionException {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new MojoExecutionException("Cannot read " + relativePath, e);
    }
  }
}

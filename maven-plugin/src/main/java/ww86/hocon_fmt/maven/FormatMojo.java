package ww86.hocon_fmt.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Rewrites the HOCON files that are not formatted. Files the formatter refuses are reported and
 * left untouched, without failing the build.
 */
// process-sources comes before process-resources, so the files copied into the build are the
// formatted ones.
@Mojo(name = "format", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true)
public final class FormatMojo extends HoconFormatterMojo {

  @Override
  void actOn(List<Examined> examined) throws MojoExecutionException {
    for (Examined file : examined) {
      if (file.outcome() instanceof Outcome.NeedsFormatting needed) {
        try {
          Files.writeString(file.file(), needed.formatted());
        } catch (IOException e) {
          throw new MojoExecutionException("Cannot write " + file.relativePath(), e);
        }
        getLog().info("Formatted " + file.relativePath());
      }
    }
    getLog().info(summary(examined, "reformatted"));
  }
}

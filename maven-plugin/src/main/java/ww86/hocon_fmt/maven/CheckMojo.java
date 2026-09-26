package ww86.hocon_fmt.maven;

import java.util.List;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Fails the build if any HOCON file is not formatted, after listing every such file. Writes
 * nothing. Files the formatter refuses are reported without failing the build.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class CheckMojo extends HoconFormatterMojo {

  @Override
  void actOn(List<Examined> examined) throws MojoFailureException {
    List<String> unformatted =
        examined.stream()
            .filter(file -> file.outcome() instanceof Outcome.NeedsFormatting)
            .map(Examined::relativePath)
            .toList();

    unformatted.forEach(path -> getLog().error("Not formatted: " + path));
    getLog().info(summary(examined, "not formatted"));
    if (!unformatted.isEmpty()) {
      throw new MojoFailureException(
          "HOCON files not formatted: "
              + unformatted.size()
              + ". Run mvn hocon-formatter:format to fix them.");
    }
  }
}

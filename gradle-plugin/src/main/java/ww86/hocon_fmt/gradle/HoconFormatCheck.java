package ww86.hocon_fmt.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Fails if any HOCON file is not formatted, after naming every such file. Writes nothing but its
 * report, which is what lets Gradle skip it while neither the files nor the formatter change.
 */
@DisableCachingByDefault(because = "Its only output is a marker; restoring that is no cheaper than checking.")
public abstract class HoconFormatCheck extends HoconFormatterTask {

    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    void check() {
        examine(true);
        try {
            Files.writeString(getReport().get().getAsFile().toPath(), "All HOCON files are formatted.\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

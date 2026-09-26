package ww86.hocon_fmt.gradle;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.workers.WorkerExecutor;

/** What both tasks share: the files, the formatter, and handing the work to it. */
@DisableCachingByDefault(because = "Each subclass decides.")
public abstract class HoconFormatterTask extends DefaultTask {

    @InputFiles
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSource();

    @Classpath
    public abstract ConfigurableFileCollection getFormatterClasspath();

    /** Where reported paths are relative to. */
    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    /**
     * Runs {@link FormatAction} in a class loader holding only the formatter and its Scala library, so
     * they cannot clash with whatever else the buildscript classpath carries.
     */
    void examine(boolean checkOnly) {
        getWorkerExecutor()
                .classLoaderIsolation(spec -> spec.getClasspath().from(getFormatterClasspath()))
                .submit(FormatAction.class, parameters -> {
                    parameters.getFiles().from(getSource());
                    parameters.getProjectDirectory().set(getProjectDirectory());
                    parameters.getCheckOnly().set(checkOnly);
                });
        getWorkerExecutor().await();
    }
}

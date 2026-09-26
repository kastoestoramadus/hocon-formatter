package ww86.hocon_fmt.gradle;

import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/** Rewrites the HOCON files that are not formatted; the ones the formatter refuses are left alone. */
@UntrackedTask(because = "It rewrites its own inputs.")
public abstract class HoconFormat extends HoconFormatterTask {

    @TaskAction
    void format() {
        examine(false);
    }
}

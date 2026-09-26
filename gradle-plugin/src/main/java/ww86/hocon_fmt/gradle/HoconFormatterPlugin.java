package ww86.hocon_fmt.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/**
 * Adds {@code hoconFormat}, which rewrites the HOCON files that are not formatted, and {@code
 * hoconFormatCheck}, which fails when one is not and runs as part of {@code check}.
 */
public final class HoconFormatterPlugin implements Plugin<Project> {

    static final String GROUP = "formatting";

    @Override
    public void apply(Project project) {
        HoconFormatterExtension extension =
                project.getExtensions().create("hoconFormatter", HoconFormatterExtension.class);
        extension.getSource().convention(project.fileTree("src", tree -> tree.include("**/*.conf", "**/*.hocon")));

        NamedDomainObjectProvider<DependencyScopeConfiguration> formatter = project.getConfigurations()
                .dependencyScope("hoconFormatter", configuration -> configuration.defaultDependencies(
                        dependencies -> dependencies.add(project.getDependencies().create(coreCoordinates()))));
        NamedDomainObjectProvider<ResolvableConfiguration> formatterClasspath = project.getConfigurations()
                .resolvable("hoconFormatterClasspath", configuration -> configuration.extendsFrom(formatter.get()));

        project.getTasks().withType(HoconFormatterTask.class).configureEach(task -> {
            task.setGroup(GROUP);
            task.getSource().from(extension.getSource());
            task.getFormatterClasspath().from(formatterClasspath);
            task.getProjectDirectory().set(project.getLayout().getProjectDirectory());
        });
        project.getTasks().register("hoconFormat", HoconFormat.class, task -> {
            task.setDescription("Rewrites the HOCON files that are not formatted.");
        });
        TaskProvider<HoconFormatCheck> check = project.getTasks().register("hoconFormatCheck", HoconFormatCheck.class, task -> {
            task.setDescription("Fails if any HOCON file is not formatted, naming each one.");
            task.getReport().set(project.getLayout().getBuildDirectory().file("hocon-formatter/check.txt"));
        });

        // Whenever `base` arrives, before or after this plugin: `check` is where Gradle users look.
        project.getPlugins().withType(LifecycleBasePlugin.class, base -> project.getTasks()
                .named(LifecycleBasePlugin.CHECK_TASK_NAME)
                .configure(task -> task.dependsOn(check)));
    }

    /** The core released together with this plugin; a build can override it in {@code hoconFormatter}. */
    static String coreCoordinates() {
        try (InputStream in = HoconFormatterPlugin.class.getResourceAsStream("formatter.properties")) {
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("coordinates");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

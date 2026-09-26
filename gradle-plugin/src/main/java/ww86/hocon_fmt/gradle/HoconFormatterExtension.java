package ww86.hocon_fmt.gradle;

import org.gradle.api.file.ConfigurableFileCollection;

/** The {@code hoconFormatter { }} block of a build script. */
public abstract class HoconFormatterExtension {

    /** The HOCON files to format: every {@code *.conf} and {@code *.hocon} under {@code src} by default. */
    public abstract ConfigurableFileCollection getSource();
}

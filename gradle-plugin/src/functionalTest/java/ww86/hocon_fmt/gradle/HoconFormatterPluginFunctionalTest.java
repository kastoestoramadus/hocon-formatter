package ww86.hocon_fmt.gradle;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.gradle.testkit.runner.TaskOutcome.FAILED;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HoconFormatterPluginFunctionalTest {

    static final String UNFORMATTED = "a=1\nb {c=2}\n";
    static final String FORMATTED = "a: 1\nb.c: 2\n";

    @TempDir
    Path projectDir;

    @BeforeEach
    void writeConsumerBuild() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"consumer\"\n");
        writeBuild("", "");
    }

    @Test
    void formatRewritesAnUnformattedFileAndLeavesAFormattedOneAlone() throws IOException {
        Path unformatted = write("src/main/resources/unformatted.conf", UNFORMATTED);
        Path formatted = write("src/main/resources/formatted.hocon", FORMATTED);
        FileTime longAgo = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));
        Files.setLastModifiedTime(formatted, longAgo);

        BuildResult result = build("hoconFormat");

        assertEquals(SUCCESS, result.task(":hoconFormat").getOutcome());
        assertEquals(FORMATTED, read(unformatted));
        assertEquals(FORMATTED, read(formatted));
        // Equal content cannot tell a skipped write from a rewrite with the same text.
        assertEquals(longAgo, Files.getLastModifiedTime(formatted));
    }

    @Test
    void checkListsEveryUnformattedFileWithoutWritingAndPassesAfterFormat() throws IOException {
        Path main = write("src/main/resources/main.conf", UNFORMATTED);
        Path test = write("src/test/resources/test.conf", UNFORMATTED);
        write("src/main/resources/fine.conf", FORMATTED);

        BuildResult failed = buildAndFail("hoconFormatCheck");

        assertEquals(FAILED, failed.task(":hoconFormatCheck").getOutcome());
        assertTrue(failed.getOutput().contains("src/main/resources/main.conf"), failed.getOutput());
        assertTrue(failed.getOutput().contains("src/test/resources/test.conf"), failed.getOutput());
        assertFalse(failed.getOutput().contains("fine.conf"), failed.getOutput());
        assertEquals(UNFORMATTED, read(main));
        assertEquals(UNFORMATTED, read(test));

        build("hoconFormat");
        BuildResult passed = build("hoconFormatCheck");

        assertEquals(SUCCESS, passed.task(":hoconFormatCheck").getOutcome());
    }

    @Test
    void aRefusedFileStaysByteForByteAndFailsNeitherTask() throws IOException {
        byte[] broken = "a : ${\n".getBytes(UTF_8);
        Path file = write("src/main/resources/broken.conf", broken);

        BuildResult format = build("hoconFormat");
        BuildResult check = build("hoconFormatCheck");

        assertEquals(SUCCESS, format.task(":hoconFormat").getOutcome());
        assertEquals(SUCCESS, check.task(":hoconFormatCheck").getOutcome());
        assertArrayEquals(broken, Files.readAllBytes(file));
        for (BuildResult result : List.of(format, check)) {
            assertTrue(result.getOutput().contains("src/main/resources/broken.conf"), result.getOutput());
            assertTrue(result.getOutput().contains("was not closed"), result.getOutput());
        }
    }

    @Test
    void aFileThatIsNotUtf8IsRefusedRatherThanRewrittenWithReplacementCharacters() throws IOException {
        byte[] latin1 = "name = \"café\"\n".getBytes(ISO_8859_1);
        Path file = write("src/main/resources/latin1.conf", latin1);

        BuildResult result = build("hoconFormat");

        assertArrayEquals(latin1, Files.readAllBytes(file));
        assertTrue(result.getOutput().contains("src/main/resources/latin1.conf"), result.getOutput());
    }

    @Test
    void anIncludeSurvivesFormatting() throws IOException {
        write("src/main/resources/other.conf", "b: 2\n");
        Path app = write("src/main/resources/app.conf", "include \"other.conf\"\na = 1\n");

        build("hoconFormat");

        String formatted = read(app);
        assertTrue(formatted.contains("include \"other.conf\"\n"), formatted);
        assertTrue(formatted.contains("a: 1\n"), formatted);
    }

    @Test
    void theFilesCanBeChosenInTheBuildScript() throws IOException {
        writeBuild("", "hoconFormatter {\n    source.setFrom(fileTree(\"config\") { include(\"**/*.conf\") })\n}\n");
        Path chosen = write("config/app.conf", UNFORMATTED);
        Path notChosen = write("src/main/resources/app.conf", UNFORMATTED);

        build("hoconFormat");

        assertEquals(FORMATTED, read(chosen));
        assertEquals(UNFORMATTED, read(notChosen));
    }

    @Test
    void checkRunsTheFormatCheckWhenBaseIsAppliedAfterThePlugin() throws IOException {
        writeBuild("    base\n", "");
        write("src/main/resources/app.conf", UNFORMATTED);

        BuildResult result = buildAndFail("check");

        assertEquals(FAILED, result.task(":hoconFormatCheck").getOutcome());
    }

    @Test
    void theCheckIsUpToDateUntilAFileChanges() throws IOException {
        Path file = write("src/main/resources/app.conf", FORMATTED);

        assertEquals(SUCCESS, build("hoconFormatCheck").task(":hoconFormatCheck").getOutcome());
        assertEquals(UP_TO_DATE, build("hoconFormatCheck").task(":hoconFormatCheck").getOutcome());

        Files.writeString(file, UNFORMATTED);

        assertEquals(FAILED, buildAndFail("hoconFormatCheck").task(":hoconFormatCheck").getOutcome());
    }

    @Test
    void aReusedConfigurationCacheEntrySeesTheFilesAsTheyAreNow() throws IOException {
        Path file = write("src/main/resources/app.conf", FORMATTED);

        BuildResult stored = build("hoconFormatCheck", "--configuration-cache");
        Files.writeString(file, UNFORMATTED);
        BuildResult reused = buildAndFail("hoconFormatCheck", "--configuration-cache");

        assertTrue(stored.getOutput().contains("Configuration cache entry stored."), stored.getOutput());
        assertTrue(reused.getOutput().contains("Reusing configuration cache."), reused.getOutput());
        assertEquals(FAILED, reused.task(":hoconFormatCheck").getOutcome());

        build("hoconFormat", "--configuration-cache");
        Files.writeString(file, UNFORMATTED);
        BuildResult reusedFormat = build("hoconFormat", "--configuration-cache");

        assertTrue(reusedFormat.getOutput().contains("Reusing configuration cache."), reusedFormat.getOutput());
        assertEquals(FORMATTED, read(file));
    }

    void writeBuild(String extraPlugins, String configuration) throws IOException {
        write(
                "build.gradle.kts",
                "plugins {\n"
                        + "    id(\"io.github.kastoestoramadus.hocon-formatter\")\n"
                        + extraPlugins
                        + "}\n"
                        + "\n"
                        + "repositories {\n"
                        + "    mavenLocal()\n"
                        + "    mavenCentral()\n"
                        + "}\n"
                        + configuration);
    }

    BuildResult build(String... arguments) {
        return runner(arguments).build();
    }

    BuildResult buildAndFail(String... arguments) {
        return runner(arguments).buildAndFail();
    }

    GradleRunner runner(String... arguments) {
        List<String> all = new ArrayList<>(List.of(arguments));
        // A deprecation in the plugin should fail here, not in a user's build after the next upgrade.
        all.add("--warning-mode=fail");
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(all);
    }

    Path write(String relativePath, String content) throws IOException {
        return write(relativePath, content.getBytes(UTF_8));
    }

    Path write(String relativePath, byte[] content) throws IOException {
        Path file = projectDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.write(file, content);
    }

    String read(Path file) throws IOException {
        return Files.readString(file);
    }
}

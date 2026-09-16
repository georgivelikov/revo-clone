package com.revolutclone.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Continuously enforces CLAUDE.md hard rule 9: no {@code localhost} (or {@code 127.0.0.1}) in
 * committed Spring service config, and local defaults only in a separate
 * {@code application-local.yml} rather than a {@code local} profile document embedded in
 * {@code application.yml}. A one-time manual grep catches today's violations; this test catches
 * the next service's, which is the point of doing this in Phase 00 rather than retrofitting it
 * across ten services later.
 *
 * <p>Scanned: every {@code *.yml}/{@code *.yaml}/{@code *.properties} in the repo except:
 * <ul>
 *   <li>files literally named {@code *-local.*} — the sanctioned home for local-dev endpoints
 *   <li>{@code src/test/resources/**} — test fixtures point Testcontainers at ephemeral local
 *       ports by design, which is a different concern from committed service config
 *   <li>{@code docker-compose*.yml} at the repo root — its job is to bind local infra ports,
 *       which is not the "service should read an env var instead" problem this rule targets
 *   <li>{@code target/}, {@code .git/}, {@code node_modules/} — build output and tooling, not
 *       committed config
 * </ul>
 */
class ConfigHygieneTest {

    private static final Pattern LOCALHOST = Pattern.compile("localhost|127\\.0\\.0\\.1", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFIG_FILE = Pattern.compile("(?i).*\\.(ya?ml|properties)$");

    @Test
    void noLocalhostOutsideALocalProfileFile() throws IOException {
        Path repoRoot = findRepoRoot();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(repoRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(ConfigHygieneTest::isCommittedConfigFile)
                 .forEach(file -> scanForLocalhost(file, violations));
        }

        assertThat(violations)
            .as("CLAUDE.md rule 9: localhost/127.0.0.1 belongs only in a *-local.* file, "
                + "a test fixture, or docker-compose*.yml. Violations found:%n%s",
                String.join(System.lineSeparator(), violations))
            .isEmpty();
    }

    private static boolean isCommittedConfigFile(Path file) {
        String unixPath = file.toString().replace('\\', '/');

        if (!CONFIG_FILE.matcher(file.getFileName().toString()).matches()) {
            return false;
        }
        if (unixPath.contains("/target/") || unixPath.contains("/.git/") || unixPath.contains("/node_modules/")) {
            return false;
        }
        if (unixPath.contains("/src/test/resources/")) {
            return false;
        }
        String fileName = file.getFileName().toString();
        if (fileName.matches("(?i).*-local\\..*")) {
            return false;
        }
        // docker-compose*.yml anywhere in the repo binds local infra ports by design - that is
        // its entire job, not the "service should read an env var" problem this rule targets.
        if (fileName.matches("(?i)docker-compose.*\\.ya?ml")) {
            return false;
        }
        return true;
    }

    private static void scanForLocalhost(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException | UncheckedIOException e) {
            // A binary or non-UTF8 file matching *.yml is not this test's concern.
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (LOCALHOST.matcher(lines.get(i)).find()) {
                violations.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
            }
        }
    }

    /**
     * Walks up from the test's working directory to the directory containing the project's
     * root {@code CLAUDE.md} - a stable, unambiguous marker regardless of which module's
     * surefire working directory this test happens to run from.
     */
    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("CLAUDE.md"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find repo root (no CLAUDE.md in any parent directory)");
    }
}

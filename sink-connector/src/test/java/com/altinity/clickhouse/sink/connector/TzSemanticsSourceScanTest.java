package com.altinity.clickhouse.sink.connector;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TZ-SEMANTICS guard (timezone hardening).
 *
 * <p>Source scan of {@code sink-connector/src/main/java}: no production line may take the JVM default
 * zone or a locale-dependent calendar, or bind a java.sql date from a local midnight. Each of these
 * resolves a zone implicitly, which is how the DATE, sameZone and _timestamp defects reached
 * production. A new occurrence fails the build until it is either rewritten with an explicit zone or
 * added to {@link #ALLOWLIST} with a written reason.
 *
 * <p>Guard self-test: a seeded violation must be detected, and every allowlist entry must still match
 * a real line (a stale entry would silently widen the guard).
 */
class TzSemanticsSourceScanTest {

    /** Patterns that take an implicit zone (JVM default) or a local-midnight java.sql conversion. */
    static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("ZoneId\\.systemDefault\\(\\)"),
            Pattern.compile("TimeZone\\.getDefault\\(\\)"),
            Pattern.compile("Calendar\\.getInstance\\(\\)"),
            Pattern.compile("\\bSimpleDateFormat\\("),
            Pattern.compile("java\\.sql\\.Date\\.valueOf\\("),
            Pattern.compile("\\bDate\\.valueOf\\("),
            Pattern.compile("Timestamp\\.valueOf\\("),
            Pattern.compile("LocalDateTime\\.now\\(\\)"),
            Pattern.compile("ZonedDateTime\\.now\\(\\)"),
            Pattern.compile("LocalDate\\.now\\(\\)"));

    /**
     * Allowlisted hits: "relative/path.java|pattern-regex" to written reason. Empty on purpose: every
     * production site was rewritten with an explicit zone. Add an entry only with a documented reason.
     */
    static final Map<String, String> ALLOWLIST = Map.of();

    /**
     * Main sources of this module. Maven passes the module directory as the {@code basedir} property, so the
     * scan does not depend on the directory the build was started from; the plain relative paths cover a
     * run from the module directory or from the repo root. It never silently scans nothing.
     */
    static Path mainSources() {
        String basedir = System.getProperty("basedir");
        List<String> candidates = basedir == null
                ? List.of("src/main/java", "sink-connector/src/main/java")
                : List.of(Paths.get(basedir, "src/main/java").toString(), "src/main/java",
                        "sink-connector/src/main/java");
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        throw new IllegalStateException("main sources not found from " + Paths.get("").toAbsolutePath());
    }

    /** One offending line: relative path, 1-based line number, the pattern that matched. */
    record Hit(String path, int line, String pattern, String text) {
        String key() {
            return path + "|" + pattern;
        }
    }

    static List<Hit> scan(Path root) throws IOException {
        List<Hit> hits = new ArrayList<>();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(root)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path file : sources) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String relative = root.relativize(file).toString().replace('\\', '/');
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String code = line.trim();
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                    continue;
                }
                for (Pattern pattern : FORBIDDEN) {
                    if (pattern.matcher(line).find()) {
                        hits.add(new Hit(relative, i + 1, pattern.pattern(), code));
                    }
                }
            }
        }
        return hits;
    }

    static List<Hit> unallowed(List<Hit> hits) {
        List<Hit> out = new ArrayList<>();
        for (Hit hit : hits) {
            if (!ALLOWLIST.containsKey(hit.key())) {
                out.add(hit);
            }
        }
        return out;
    }

    @Test
    void mainSourcesTakeNoImplicitZone() throws IOException {
        List<Hit> violations = unallowed(scan(mainSources()));
        assertTrue(violations.isEmpty(), () -> "implicit zone in main sources: " + violations);
    }

    @Test
    void everyAllowlistEntryStillMatchesARealLine() throws IOException {
        Set<String> live = new java.util.HashSet<>();
        for (Hit hit : scan(mainSources())) {
            live.add(hit.key());
        }
        for (String key : ALLOWLIST.keySet()) {
            assertTrue(live.contains(key), "stale allowlist entry (remove it): " + key);
        }
    }

    @Test
    void guardDetectsSeededViolationsOfEveryKind() throws IOException {
        Path seeded = Files.createTempDirectory("tz-guard");
        Path file = seeded.resolve("Seeded.java");
        Files.writeString(file, String.join("\n",
                "class Seeded {",
                "  void a() { java.time.ZoneId z = java.time.ZoneId.systemDefault(); }",
                "  void b() { java.util.TimeZone t = java.util.TimeZone.getDefault(); }",
                "  void c() { java.util.Calendar c = java.util.Calendar.getInstance(); }",
                "  void d() { new java.text.SimpleDateFormat(\"yyyy-MM-dd\"); }",
                "  void e() { java.sql.Date d = java.sql.Date.valueOf(day); }",
                "  void f() { java.time.LocalDateTime n = java.time.LocalDateTime.now(); }",
                "  // ZoneId.systemDefault() in a comment is documentation, not a violation",
                "}"), StandardCharsets.UTF_8);
        List<Hit> hits = scan(seeded);
        // Lines 2-7 each carry one seeded violation (java.sql.Date.valueOf matches two patterns); line 8 is a comment.
        assertEquals(List.of(2, 3, 4, 5, 6, 7), hits.stream().map(Hit::line).distinct().sorted().toList(),
                () -> "seeded hits: " + hits);
        assertFalse(hits.stream().anyMatch(h -> h.line() == 8), "comment lines must not be scanned");
    }

    @Test
    void guardAcceptsExplicitZones() throws IOException {
        Path explicit = Files.createTempDirectory("tz-guard-ok");
        Files.writeString(explicit.resolve("Explicit.java"), String.join("\n",
                "class Explicit {",
                "  java.time.ZoneId z = java.time.ZoneId.of(\"UTC\");",
                "  java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern(\"yyyy\")",
                "      .withZone(java.time.ZoneOffset.UTC);",
                "}"), StandardCharsets.UTF_8);
        assertEquals(0, scan(explicit).size());
    }
}

package com.altinity.clickhouse.debezium.embedded.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data-driven pins of the key classification of ConfigKeyCheck (spec 07, section 5).
 *
 * <p>The rows of {@code config-key-check/production-keys.tsv} and {@code config-key-check/shipped-keys.tsv} are the
 * reviewed expectations. A new deployment key, a new shipped key or a change of classification fails here until the
 * row is reviewed. The in-repo deploy template is read directly and must match its pinned rows.
 */
public class ConfigKeyPinTest {

    private static final Path DIR = Paths.get("src/test/resources/config-key-check");
    private static final Path DEPLOY_TEMPLATE = Paths.get("../deploy/ansible-systemd/templates/config.yml.j2");
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^([A-Za-z0-9_.-]+):");
    private static final String OK = "OK";

    @Test
    void productionKeySetsAreClassifiedAsPinned() throws Exception {
        Map<String, Map<String, String>> sources = readRows(DIR.resolve("production-keys.tsv"));
        Assertions.assertTrue(sources.containsKey("role-template"), "role-template rows are pinned");
        Assertions.assertTrue(sources.containsKey("repo-deploy-template"), "repo-deploy-template rows are pinned");
        for (Map.Entry<String, Map<String, String>> source : sources.entrySet()) {
            assertPinned(source.getKey(), source.getValue().keySet(), source.getValue());
        }
    }

    @Test
    void inRepoDeployTemplateKeysArePinned() throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : Files.readAllLines(DEPLOY_TEMPLATE)) {
            Matcher m = TOP_LEVEL_KEY.matcher(line);
            if (m.find()) {
                keys.add(m.group(1));
            }
        }
        Map<String, String> expected = readRows(DIR.resolve("production-keys.tsv")).get("repo-deploy-template");
        Assertions.assertEquals(expected.keySet(), keys,
                "keys of deploy/ansible-systemd/templates/config.yml.j2 must be the pinned repo-deploy-template rows");
        assertPinned("repo-deploy-template (file)", keys, expected);
    }

    @Test
    void shippedSampleFindingsArePinned() throws Exception {
        Map<String, Map<String, String>> files = readRows(DIR.resolve("shipped-keys.tsv"));
        Assertions.assertFalse(files.isEmpty(), "shipped sample rows are pinned");
        for (Map.Entry<String, Map<String, String>> file : files.entrySet()) {
            Properties props = ConfigKeyCheck.load(file.getKey());
            assertPinned(file.getKey(), props.stringPropertyNames(), file.getValue());
        }
    }

    /**
     * The pinned rows are the complete set of findings: a key outside the rows must be known, and a row must
     * produce exactly its kind. Under the default mode nothing is refused; under strict mode exactly the UNKNOWN and
     * RENAMED rows are refused.
     */
    private static void assertPinned(String label, Set<String> keys, Map<String, String> expected) {
        Map<String, String> expectedFindings = new TreeMap<>();
        Set<String> expectedRefused = new TreeSet<>();
        for (Map.Entry<String, String> row : expected.entrySet()) {
            if (!OK.equals(row.getValue())) {
                expectedFindings.put(row.getKey(), row.getValue());
                if (row.getValue().equals("UNKNOWN") || row.getValue().equals("RENAMED")) {
                    expectedRefused.add(row.getKey());
                }
            }
        }
        Map<String, String> actual = new TreeMap<>();
        Set<String> refused = new TreeSet<>();
        for (ConfigKeyCheck.Finding f : ConfigKeyCheck.findings(keys, true)) {
            actual.put(f.key(), f.kind().name());
            if (f.fatal()) {
                refused.add(f.key());
            }
        }
        Assertions.assertEquals(expectedFindings, actual, label + ": findings and their kinds");
        Assertions.assertEquals(expectedRefused, refused, label + ": keys refused under config.keys.strict=true");
        for (ConfigKeyCheck.Finding f : ConfigKeyCheck.findings(keys, false)) {
            Assertions.assertFalse(f.fatal(), label + ": the default mode must not refuse " + f.key());
        }
    }

    /** Reads a TSV file with the columns source-or-file, key, expected; groups the rows by the first column. */
    private static Map<String, Map<String, String>> readRows(Path file) throws Exception {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\t", -1);
            Assertions.assertEquals(3, cols.length, "three columns in " + file + ": " + line);
            if (cols[0].equals("source") || cols[0].equals("file")) {
                continue;
            }
            Map<String, String> rows = groups.computeIfAbsent(cols[0], k -> new LinkedHashMap<>());
            Assertions.assertNull(rows.put(cols[1], cols[2]), "duplicate row in " + file + ": " + line);
        }
        return groups;
    }
}

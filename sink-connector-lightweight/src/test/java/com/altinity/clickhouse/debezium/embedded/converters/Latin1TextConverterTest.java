package com.altinity.clickhouse.debezium.embedded.converters;

import io.debezium.spi.converter.CustomConverter;
import io.debezium.spi.converter.RelationalColumn;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.08. The oracle is MySQL 8.0.46's own conversion of every single latin1 byte
 * (HEX(CONVERT(b USING utf8mb4))), captured from MySQL 8.0.46.
 * Non-ASCII expectations are written as Unicode escapes so the source stays ASCII.
 */
class Latin1TextConverterTest {

    private static final String ORACLE = "/latin1/mysql_latin1_utf8_oracle.tsv";

    private static String hex(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private static List<String[]> oracleRows() throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = Latin1TextConverterTest.class.getResourceAsStream(ORACLE)) {
            assertTrue(in != null, "oracle fixture missing: " + ORACLE);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
                String line;
                while ((line = r.readLine()) != null) {
                    rows.add(line.split("\t"));
                }
            }
        }
        return rows;
    }

    @Test
    void everySingleByteMatchesMySql() throws IOException {
        List<String[]> rows = oracleRows();
        assertEquals(255, rows.size(), "oracle must hold ids 1..255");
        for (String[] row : rows) {
            int b = Integer.parseInt(row[0]);
            String decoded = Latin1TextConverter.decode(new byte[]{(byte) b});
            assertEquals(row[1], hex(decoded), "byte 0x" + String.format("%02X", b) + " differs from MySQL");
        }
    }

    @Test
    void undefinedBytesAreControlCharacters() {
        int[] undefined = {0x81, 0x8D, 0x8F, 0x90, 0x9D};
        for (int b : undefined) {
            String decoded = Latin1TextConverter.decode(new byte[]{(byte) b});
            assertEquals(1, decoded.length());
            assertEquals(b, decoded.charAt(0), "byte 0x" + String.format("%02X", b));
            assertFalse(decoded.contains("�"), "must not be U+FFFD");
        }
    }

    @Test
    void euroAndYDiaeresisMatchCp1252() {
        assertEquals("€", Latin1TextConverter.decode(new byte[]{(byte) 0x80}));
        assertEquals("Ÿ", Latin1TextConverter.decode(new byte[]{(byte) 0x9F}));
    }

    @Test
    void asciiAndHighLatinPassThrough() {
        byte[] cafe = {0x63, 0x61, 0x66, (byte) 0xE9};
        assertEquals("café", Latin1TextConverter.decode(cafe));
        assertEquals("", Latin1TextConverter.decode(new byte[0]));
    }

    @Test
    void convertPassesNonBytesThrough() {
        assertEquals("abc", Latin1TextConverter.convert("abc"));
        assertNull(Latin1TextConverter.convert(null));
        Integer i = 7;
        assertSame(i, Latin1TextConverter.convert(i));
        assertEquals("\u0081", Latin1TextConverter.convert(new byte[]{(byte) 0x81}));
    }

    @Test
    void converterRegisteredOnlyForLatin1Text() {
        assertTrue(Latin1TextConverter.isLatin1Text(column("VARCHAR", "latin1")));
        assertTrue(Latin1TextConverter.isLatin1Text(column("CHAR", "latin1")));
        assertTrue(Latin1TextConverter.isLatin1Text(column("TEXT", "latin1")));
        assertTrue(Latin1TextConverter.isLatin1Text(column("MEDIUMTEXT", "LATIN1")));
        assertTrue(Latin1TextConverter.isLatin1Text(column("VARCHAR(8)", "latin1")));
        assertFalse(Latin1TextConverter.isLatin1Text(column("VARCHAR", "utf8mb4")));
        assertFalse(Latin1TextConverter.isLatin1Text(column("ENUM", "latin1")));
        assertFalse(Latin1TextConverter.isLatin1Text(column("SET", "latin1")));
        assertFalse(Latin1TextConverter.isLatin1Text(column("VARCHAR", null)));
        assertFalse(Latin1TextConverter.isLatin1Text(column("VARBINARY", "binary")));

        AtomicReference<Object> registered = new AtomicReference<>();
        CustomConverter<SchemaBuilder, RelationalColumn> c = new Latin1TextConverter();
        c.converterFor(column("VARCHAR", "latin1"), (schema, conv) -> registered.set(conv));
        assertTrue(registered.get() != null, "latin1 VARCHAR must register a converter");

        registered.set(null);
        c.converterFor(column("VARCHAR", "utf8mb4"), (schema, conv) -> registered.set(conv));
        assertNull(registered.get(), "utf8mb4 must not register a converter");
    }

    @Test
    void registeredSchemaIsPlainString() {
        AtomicReference<SchemaBuilder> schema = new AtomicReference<>();
        new Latin1TextConverter().converterFor(column("VARCHAR", "latin1"),
                (s, conv) -> schema.set(s));
        assertEquals(Schema.Type.STRING, schema.get().type());
        assertFalse(schema.get().isOptional(), "optionality is applied by Debezium, not here");
    }

    @Test
    void ensureRegisteredRespectsOperatorConfig() {
        Properties blank = new Properties();
        Latin1TextConverter.ensureRegistered(blank);
        assertEquals("latin1text", blank.getProperty("converters"));
        assertEquals(Latin1TextConverter.class.getName(), blank.getProperty("latin1text.type"));

        Properties existing = new Properties();
        existing.setProperty("converters", "other");
        Latin1TextConverter.ensureRegistered(existing);
        assertEquals("other,latin1text", existing.getProperty("converters"));

        Properties explicit = new Properties();
        explicit.setProperty("latin1text.type", "operator.Own");
        Latin1TextConverter.ensureRegistered(explicit);
        assertEquals("operator.Own", explicit.getProperty("latin1text.type"));

        Properties already = new Properties();
        already.setProperty("converters", "latin1text");
        Latin1TextConverter.ensureRegistered(already);
        assertEquals("latin1text", already.getProperty("converters"));
    }

    @Test
    void cp1252ReferenceDiffersFromOracleOnFiveBytes() {
        // Documents why the fix exists: the JDK windows-1252 charset disagrees with MySQL on exactly five bytes.
        Charset cp1252 = Charset.forName("windows-1252");
        int differing = 0;
        for (int b = 1; b <= 255; b++) {
            String viaJdk = new String(new byte[]{(byte) b}, cp1252);
            if (!viaJdk.equals(Latin1TextConverter.decode(new byte[]{(byte) b}))) {
                differing++;
            }
        }
        assertEquals(5, differing);
    }

    private static RelationalColumn column(String typeName, String charsetName) {
        return new RelationalColumn() {
            @Override public int jdbcType() { return 12; }
            @Override public int nativeType() { return 0; }
            @Override public String typeName() { return typeName; }
            @Override public String typeExpression() { return typeName; }
            @Override public OptionalInt length() { return OptionalInt.empty(); }
            @Override public OptionalInt scale() { return OptionalInt.empty(); }
            @Override public boolean isOptional() { return true; }
            @Override public Object defaultValue() { return null; }
            @Override public boolean hasDefaultValue() { return false; }
            @Override public String charsetName() { return charsetName; }
            @Override public String name() { return "c"; }
            @Override public String dataCollection() { return "t"; }
        };
    }
}

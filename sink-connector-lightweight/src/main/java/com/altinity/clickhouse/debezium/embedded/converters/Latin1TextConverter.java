package com.altinity.clickhouse.debezium.embedded.converters;

import io.debezium.spi.converter.CustomConverter;
import io.debezium.spi.converter.RelationalColumn;
import org.apache.kafka.connect.data.SchemaBuilder;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Spec 07.08. Decodes latin1 text bytes the way MySQL does (cp1252 with the five
 * undefined bytes kept as C1 controls) instead of the JDK windows-1252 decoder that
 * Debezium uses, which turns those five bytes into U+FFFD.
 */
public class Latin1TextConverter implements CustomConverter<SchemaBuilder, RelationalColumn> {

    public static final String NAME = "latin1text";

    private static final String CONVERTERS_KEY = "converters";
    private static final Charset CP1252 = Charset.forName("windows-1252");
    private static final char[] LATIN1_TO_CHAR = buildLatin1Table();
    private static final Set<String> TEXT_TYPES =
            Set.of("CHAR", "VARCHAR", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT");

    @Override
    public void configure(Properties props) {
        // No configuration of its own.
    }

    @Override
    public void converterFor(RelationalColumn column, ConverterRegistration<SchemaBuilder> registration) {
        if (isLatin1Text(column)) {
            registration.register(SchemaBuilder.string(), Latin1TextConverter::convert);
        }
    }

    static boolean isLatin1Text(RelationalColumn column) {
        if (column == null || column.charsetName() == null || column.typeName() == null) {
            return false;
        }
        if (!"latin1".equalsIgnoreCase(column.charsetName().trim())) {
            return false;
        }
        String type = column.typeName();
        int paren = type.indexOf('(');
        String base = (paren >= 0 ? type.substring(0, paren) : type).trim().toUpperCase(Locale.ROOT);
        return TEXT_TYPES.contains(base);
    }

    /** Byte[] values are decoded; any other value (snapshot String, default, null) passes through. */
    static Object convert(Object value) {
        if (value instanceof byte[]) {
            return decode((byte[]) value);
        }
        return value;
    }

    static String decode(byte[] bytes) {
        char[] out = new char[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = LATIN1_TO_CHAR[bytes[i] & 0xFF];
        }
        return new String(out);
    }

    private static char[] buildLatin1Table() {
        char[] table = new char[256];
        for (int b = 0; b < 256; b++) {
            if (isUndefinedInCp1252(b)) {
                table[b] = (char) b;
            } else {
                table[b] = new String(new byte[]{(byte) b}, CP1252).charAt(0);
            }
        }
        return table;
    }

    private static boolean isUndefinedInCp1252(int b) {
        return b == 0x81 || b == 0x8D || b == 0x8F || b == 0x90 || b == 0x9D;
    }

    /**
     * Adds this converter to the Debezium configuration: lists it under {@code converters} and
     * sets {@code latin1text.type}. An operator's own list entry or type is kept.
     */
    public static void ensureRegistered(Properties props) {
        if (props == null) {
            return;
        }
        String list = props.getProperty(CONVERTERS_KEY);
        if (list == null || list.trim().isEmpty()) {
            props.setProperty(CONVERTERS_KEY, NAME);
        } else if (Arrays.stream(list.split(",")).map(String::trim).noneMatch(NAME::equals)) {
            props.setProperty(CONVERTERS_KEY, list.trim() + "," + NAME);
        }
        String typeKey = NAME + ".type";
        String type = props.getProperty(typeKey);
        if (type == null || type.trim().isEmpty()) {
            props.setProperty(typeKey, Latin1TextConverter.class.getName());
        }
    }
}

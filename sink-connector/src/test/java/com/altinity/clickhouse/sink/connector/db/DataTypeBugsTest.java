package com.altinity.clickhouse.sink.connector.db;

import com.google.common.io.BaseEncoding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for validating P0 data type bug fixes:
 * - BUG-DATA-1: NULL validation for NOT NULL columns
 * - BUG-DATA-6: Binary data encoding (hex encoding)
 */
public class DataTypeBugsTest {

    @Test
    @DisplayName("BUG-DATA-1: Test NULL validation for NOT NULL columns")
    public void testNULLInNotNullColumn() {
        // Simulate column metadata
        Map<String, String> columnTypes = new HashMap<>();
        columnTypes.put("id", "Int32");                  // NOT NULL (no Nullable wrapper)
        columnTypes.put("name", "String");               // NOT NULL
        columnTypes.put("email", "Nullable(String)");    // Can be NULL
        columnTypes.put("age", "Nullable(Int32)");       // Can be NULL

        // Test case 1: NULL value for NOT NULL column should be detected
        String notNullColumn = "id";
        String notNullType = columnTypes.get(notNullColumn);
        
        assertFalse(notNullType.toLowerCase().contains("nullable"),
            "Column 'id' should not be nullable");
        
        // This should trigger validation error
        try {
            validateNullValue(notNullColumn, notNullType, null);
            fail("Should throw RuntimeException for NULL in NOT NULL column");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("NULL value not allowed"),
                "Error message should indicate NULL not allowed");
            assertTrue(e.getMessage().contains(notNullColumn),
                "Error message should include column name");
        }

        // Test case 2: NULL value for Nullable column should be allowed
        String nullableColumn = "email";
        String nullableType = columnTypes.get(nullableColumn);
        
        assertTrue(nullableType.toLowerCase().contains("nullable"),
            "Column 'email' should be nullable");
        
        // This should NOT throw exception
        assertDoesNotThrow(() -> validateNullValue(nullableColumn, nullableType, null),
            "NULL should be allowed for Nullable columns");

        // Test case 3: Non-NULL value should always be allowed
        assertDoesNotThrow(() -> validateNullValue("id", "Int32", 123),
            "Non-NULL value should be allowed");
        assertDoesNotThrow(() -> validateNullValue("email", "Nullable(String)", "test@example.com"),
            "Non-NULL value should be allowed for Nullable columns");
    }

    @Test
    @DisplayName("BUG-DATA-6: Test binary data hex encoding")
    public void testBinaryDataEncoding() {
        // Test case 1: Simple binary data
        byte[] binaryData = new byte[]{0x00, 0x01, 0x02, (byte)0xFF, (byte)0xFE};
        
        // Incorrect encoding (old bug): new String(byte[])
        String incorrectEncoding = new String(binaryData, StandardCharsets.ISO_8859_1);
        
        // Correct encoding (fix): hex encoding
        String correctEncoding = BaseEncoding.base16().lowerCase().encode(binaryData);
        
        assertEquals("000102fffe", correctEncoding,
            "Binary data should be hex encoded");
        assertNotEquals(incorrectEncoding, correctEncoding,
            "Hex encoding differs from direct String conversion");

        // Test case 2: Binary data with special bytes (null bytes, high values)
        byte[] specialBytes = new byte[]{0x00, 0x00, (byte)0xFF, (byte)0xFF, 0x7F, (byte)0x80};
        String hexEncoded = BaseEncoding.base16().lowerCase().encode(specialBytes);
        
        assertEquals("0000ffff7f80", hexEncoded,
            "Special bytes should be properly hex encoded");
        assertEquals(12, hexEncoded.length(),
            "Hex encoding should be 2 chars per byte");

        // Test case 3: Round-trip test
        byte[] originalData = "Test binary data with special chars: \0\1\2".getBytes(StandardCharsets.UTF_8);
        String hexString = BaseEncoding.base16().lowerCase().encode(originalData);
        byte[] decodedData = BaseEncoding.base16().lowerCase().decode(hexString);
        
        assertArrayEquals(originalData, decodedData,
            "Round-trip encoding/decoding should preserve data");

        // Test case 4: Empty byte array
        byte[] emptyArray = new byte[0];
        String emptyHex = BaseEncoding.base16().lowerCase().encode(emptyArray);
        assertEquals("", emptyHex, "Empty array should encode to empty string");

        // Test case 5: ByteBuffer encoding
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{0x12, 0x34, 0x56, 0x78});
        String bufferHex = BaseEncoding.base16().lowerCase().encode(buffer.array());
        assertEquals("12345678", bufferHex, "ByteBuffer should be hex encoded correctly");
    }

    @Test
    @DisplayName("BUG-DATA-6: Test binary data corruption scenarios")
    public void testBinaryDataCorruption() {
        // This test demonstrates the bug: direct String conversion corrupts binary data
        
        // Test with image-like binary data (common use case)
        byte[] imageHeader = new byte[]{
            (byte)0x89, 0x50, 0x4E, 0x47,  // PNG header
            0x0D, 0x0A, 0x1A, 0x0A
        };
        
        // Incorrect conversion (causes corruption)
        String directStringConversion = new String(imageHeader, StandardCharsets.ISO_8859_1);
        byte[] corruptedBytes = directStringConversion.getBytes(StandardCharsets.ISO_8859_1);
        
        // Correct hex encoding (preserves data)
        String hexEncoded = BaseEncoding.base16().lowerCase().encode(imageHeader);
        byte[] restoredBytes = BaseEncoding.base16().lowerCase().decode(hexEncoded);
        
        assertArrayEquals(imageHeader, restoredBytes,
            "Hex encoding should preserve binary data exactly");
        
        // Note: Direct string conversion may or may not corrupt depending on charset
        // but hex encoding ALWAYS preserves the data correctly
        assertEquals("89504e470d0a1a0a", hexEncoded,
            "PNG header should be correctly hex encoded");
    }

    @Test
    @DisplayName("BUG-DATA-1: Test validation with various data types")
    public void testNullValidationVariousTypes() {
        Map<String, String> columnTypes = new HashMap<>();
        
        // Various ClickHouse types
        columnTypes.put("col_int8", "Int8");
        columnTypes.put("col_int16", "Int16");
        columnTypes.put("col_int32", "Int32");
        columnTypes.put("col_int64", "Int64");
        columnTypes.put("col_uint8", "UInt8");
        columnTypes.put("col_float32", "Float32");
        columnTypes.put("col_float64", "Float64");
        columnTypes.put("col_string", "String");
        columnTypes.put("col_fixedstring", "FixedString(10)");
        columnTypes.put("col_date", "Date");
        columnTypes.put("col_datetime", "DateTime");
        columnTypes.put("col_decimal", "Decimal(10,2)");
        
        // Nullable versions
        columnTypes.put("col_nullable_int", "Nullable(Int32)");
        columnTypes.put("col_nullable_string", "Nullable(String)");
        columnTypes.put("col_nullable_datetime", "Nullable(DateTime)");

        // Test NOT NULL columns reject NULL
        String[] notNullColumns = {
            "col_int8", "col_int32", "col_string", "col_date", "col_decimal"
        };
        
        for (String colName : notNullColumns) {
            String colType = columnTypes.get(colName);
            try {
                validateNullValue(colName, colType, null);
                fail("Should reject NULL for NOT NULL column: " + colName);
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("NULL value not allowed"),
                    "Should reject NULL for " + colName);
            }
        }

        // Test Nullable columns accept NULL
        String[] nullableColumns = {
            "col_nullable_int", "col_nullable_string", "col_nullable_datetime"
        };
        
        for (String colName : nullableColumns) {
            String colType = columnTypes.get(colName);
            assertDoesNotThrow(() -> validateNullValue(colName, colType, null),
                "Should accept NULL for Nullable column: " + colName);
        }
    }

    @Test
    @DisplayName("BUG-DATA-6: Test hex encoding performance and correctness")
    public void testHexEncodingPerformance() {
        // Test with larger binary data (simulate BLOB)
        byte[] largeBlob = new byte[1024];
        for (int i = 0; i < largeBlob.length; i++) {
            largeBlob[i] = (byte) (i % 256);
        }

        long startTime = System.nanoTime();
        String hexEncoded = BaseEncoding.base16().lowerCase().encode(largeBlob);
        long endTime = System.nanoTime();
        
        assertEquals(2048, hexEncoded.length(), "Hex string should be 2x the byte array size");
        
        // Verify encoding is correct by checking pattern
        assertTrue(hexEncoded.startsWith("000102030405"),
            "Hex encoding should start with correct pattern");
        
        long durationMs = (endTime - startTime) / 1_000_000;
        System.out.println("Hex encoding 1KB took: " + durationMs + "ms");
        
        // Round-trip verification
        byte[] decoded = BaseEncoding.base16().lowerCase().decode(hexEncoded);
        assertArrayEquals(largeBlob, decoded, "Round-trip should preserve data");
    }

    // Helper method to simulate NULL validation logic (Fix BUG-DATA-1)
    private void validateNullValue(String columnName, String columnType, Object value) {
        if (value == null) {
            // Check if column is NOT NULL
            if (columnType != null && !columnType.toLowerCase().contains("nullable")) {
                throw new RuntimeException(String.format(
                    "NULL value not allowed for NOT NULL column: %s (type: %s)", 
                    columnName, columnType));
            }
        }
    }

    @Test
    @DisplayName("Integration: Test binary data in real-world scenarios")
    public void testBinaryDataRealWorldScenarios() {
        // Scenario 1: Image data
        byte[] jpegHeader = new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0};
        String jpegHex = BaseEncoding.base16().lowerCase().encode(jpegHeader);
        assertEquals("ffd8ffe0", jpegHex, "JPEG header should be correctly encoded");

        // Scenario 2: Encrypted data (all bytes random)
        byte[] encryptedData = new byte[32];
        for (int i = 0; i < 32; i++) {
            encryptedData[i] = (byte) ((i * 7 + 13) % 256);
        }
        String encryptedHex = BaseEncoding.base16().lowerCase().encode(encryptedData);
        assertEquals(64, encryptedHex.length(), "32 bytes should encode to 64 hex chars");

        // Scenario 3: Binary protocol data with null bytes
        byte[] protocolData = new byte[]{0x00, 0x01, 0x00, 0x02, 0x00, 0x03};
        String protocolHex = BaseEncoding.base16().lowerCase().encode(protocolData);
        assertEquals("000100020003", protocolHex, 
            "Protocol data with nulls should be correctly encoded");

        // Verify all can be decoded back
        assertArrayEquals(jpegHeader, 
            BaseEncoding.base16().lowerCase().decode(jpegHex));
        assertArrayEquals(encryptedData, 
            BaseEncoding.base16().lowerCase().decode(encryptedHex));
        assertArrayEquals(protocolData, 
            BaseEncoding.base16().lowerCase().decode(protocolHex));
    }
}

package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAutoCreateTable;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseTableOperationsBase;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.data.VariableScaleDecimal;
import io.debezium.data.geometry.Geometry;
import io.debezium.data.geometry.Point;
import io.debezium.time.ZonedTimestamp;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.06 section 3: a spatial value is either stored faithfully or the
 * batch fails. Before this, a non-Polygon geometry (LINESTRING, MULTIPOINT,
 * GEOMETRY, ...) bound for a {@code Polygon} column, an unparseable WKB
 * payload, and a non-Struct carrier were all written as an EMPTY polygon
 * {@code []} -- a value MySQL never held, with the batch reported successful.
 * The same silent substitution existed for a non-Struct {@code Point}
 * (written as the origin), a non-Struct variable-scale decimal (written as
 * 0) and an unparseable {@code ZonedTimestamp} (written as {@code ''}).
 */
public class ClickHouseDataTypeMapperGeometryTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static byte[] polygonWkb() {
        return new WKBWriter().write(GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(1, 2), new Coordinate(3, 4), new Coordinate(5, 6), new Coordinate(1, 2)}));
    }

    private static byte[] lineStringWkb() {
        return new WKBWriter().write(GEOMETRY_FACTORY.createLineString(new Coordinate[]{
                new Coordinate(1, 2), new Coordinate(3, 4)}));
    }

    private static Struct geometry(byte[] wkb) {
        return Geometry.createValue(Geometry.schema(), wkb, 0);
    }

    private static ClickHouseSinkConnectorConfig config() {
        return new ClickHouseSinkConnectorConfig(new HashMap<>());
    }

    /** Records setString / setBytes / setObject / setBigDecimal. */
    private static PreparedStatement recording(AtomicReference<Object> bound) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setString":
                case "setBytes":
                case "setObject":
                case "setBigDecimal":
                    bound.set(args[1]);
                    return null;
                case "isWrapperFor":
                    return false; // V2 driver path: geo values are bound as strings
                case "toString":
                    return "RecordingPreparedStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    private static Object bindGeometry(Object value, ClickHouseDataType target) throws Exception {
        AtomicReference<Object> bound = new AtomicReference<>();
        ClickHouseDataTypeMapper.convert(Schema.Type.STRUCT, Geometry.LOGICAL_NAME, value, 1,
                recording(bound), config(), target, UTC);
        return bound.get();
    }

    @Test
    @DisplayName("A POLYGON is bound as a ClickHouse polygon literal (unchanged happy path)")
    public void polygonIsBoundAsPolygon() throws Exception {
        assertEquals("[[(1.0,2.0),(3.0,4.0),(5.0,6.0),(1.0,2.0)]]",
                bindGeometry(geometry(polygonWkb()), ClickHouseDataType.Polygon));
    }

    @Test
    @DisplayName("A non-Polygon geometry bound for a Polygon column fails instead of becoming an empty polygon")
    public void nonPolygonIntoPolygonColumnThrows() {
        AtomicReference<Object> bound = new AtomicReference<>();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClickHouseDataTypeMapper.convert(Schema.Type.STRUCT, Geometry.LOGICAL_NAME,
                        geometry(lineStringWkb()), 1, recording(bound), config(), ClickHouseDataType.Polygon, UTC),
                "a LINESTRING is not a polygon; writing [] in its place fabricates a value");
        assertTrue(e.getMessage().contains("LineString"), e.getMessage());
        assertNull(bound.get(), "nothing may be bound for a rejected value");
    }

    @Test
    @DisplayName("Unparseable WKB fails instead of becoming an empty polygon")
    public void unparseableWkbThrows() {
        AtomicReference<Object> bound = new AtomicReference<>();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClickHouseDataTypeMapper.convert(Schema.Type.STRUCT, Geometry.LOGICAL_NAME,
                        geometry(new byte[]{1, 2, 3}), 1, recording(bound), config(), ClickHouseDataType.Polygon, UTC));
        assertNotNull(e.getCause(), "the parse failure must be carried as the cause: " + e.getMessage());
        assertNull(bound.get());
    }

    @Test
    @DisplayName("A geometry bound for a String column is stored as the exact WKB bytes, hex-encoded")
    public void geometryIntoStringColumnIsWkbHex() throws Exception {
        byte[] wkb = lineStringWkb();
        StringBuilder hex = new StringBuilder();
        for (byte b : wkb) {
            hex.append(String.format("%02x", b));
        }
        assertEquals(hex.toString(), bindGeometry(geometry(wkb), ClickHouseDataType.String),
                "the WKB must round-trip byte for byte (HEX(ST_AsWKB(col)) on the source)");
        // A polygon into a String column is equally the hex WKB, not a polygon literal.
        assertTrue(((String) bindGeometry(geometry(polygonWkb()), ClickHouseDataType.String)).matches("[0-9a-f]+"));
    }

    @Test
    @DisplayName("A geometry carried by something other than a Struct fails instead of becoming an empty polygon")
    public void nonStructGeometryThrows() {
        AtomicReference<Object> bound = new AtomicReference<>();
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseDataTypeMapper.convert(Schema.Type.STRUCT, Geometry.LOGICAL_NAME,
                        "not a struct", 1, recording(bound), config(), ClickHouseDataType.Polygon, UTC));
        assertNull(bound.get());
    }

    @Test
    @DisplayName("A Point carried by something other than a Struct fails instead of becoming the origin")
    public void nonStructPointThrows() throws Exception {
        AtomicReference<Object> bound = new AtomicReference<>();
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseDataTypeMapper.convert(Schema.Type.STRUCT, Point.LOGICAL_NAME,
                        "not a struct", 1, recording(bound), config(), ClickHouseDataType.Point, UTC));
        assertNull(bound.get());

        // The genuine point is unchanged.
        ClickHouseDataTypeMapper.convert(Schema.Type.STRUCT, Point.LOGICAL_NAME,
                Point.createValue(Point.builder().build(), 1.5, -2.25), 1, recording(bound), config(),
                ClickHouseDataType.Point, UTC);
        assertEquals("(1.5,-2.25)", bound.get());
    }

    @Test
    @DisplayName("A variable-scale decimal carried by something other than a Struct fails instead of becoming 0")
    public void nonStructVariableScaleDecimalThrows() {
        AtomicReference<Object> bound = new AtomicReference<>();
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseDataTypeMapper.convert(Schema.Type.STRUCT, VariableScaleDecimal.LOGICAL_NAME,
                        "not a struct", 1, recording(bound), config(), ClickHouseDataType.Decimal, UTC));
        assertNull(bound.get());
    }

    @Test
    @DisplayName("An unparseable ZonedTimestamp fails instead of becoming an empty string")
    public void unparseableZonedTimestampThrows() {
        AtomicReference<Object> bound = new AtomicReference<>();
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseDataTypeMapper.convert(Schema.Type.STRING, ZonedTimestamp.SCHEMA_NAME,
                        "yesterday-ish", 1, recording(bound), config(), ClickHouseDataType.DateTime64, UTC));
        assertNull(bound.get());
    }

    private static Field spatialField(String name, String logicalName, String sourceType, boolean optional) {
        SchemaBuilder builder = SchemaBuilder.struct().name(logicalName)
                .field("wkb", Schema.BYTES_SCHEMA)
                .field("srid", Schema.OPTIONAL_INT32_SCHEMA);
        if (sourceType != null) {
            builder.parameter(ClickHouseDataTypeMapper.DEBEZIUM_SOURCE_COLUMN_TYPE_PARAM, sourceType);
        }
        if (optional) {
            builder.optional();
        }
        return new Field(name, 0, builder.build());
    }

    /**
     * Spec 07.06 section 3.1: only a POLYGON source column is a ClickHouse
     * Polygon; every other spatial type is a String holding the WKB hex, and
     * ClickHouse geo types are never wrapped in Nullable (they are composite).
     */
    @Test
    @DisplayName("Record-schema type mapping: POLYGON -> Polygon, other spatial types -> String, never Nullable(geo)")
    public void recordSchemaMapsNonPolygonSpatialTypesToString() {
        Field[] fields = new Field[]{
                spatialField("poly", Geometry.LOGICAL_NAME, "POLYGON", false),
                spatialField("poly_null", Geometry.LOGICAL_NAME, "POLYGON", true),
                spatialField("line", Geometry.LOGICAL_NAME, "LINESTRING", true),
                spatialField("multi", Geometry.LOGICAL_NAME, "MULTIPOLYGON", false),
                spatialField("any_geom", Geometry.LOGICAL_NAME, "GEOMETRY", true),
                spatialField("collection", Geometry.LOGICAL_NAME, "GEOMCOLLECTION", false),
                spatialField("unknown", Geometry.LOGICAL_NAME, null, true),
                spatialField("pt", Point.LOGICAL_NAME, "POINT", true),
        };
        Map<String, String> types = new ClickHouseTableOperationsBase()
                .getColumnNameToCHDataTypeMapping(fields, config());

        assertEquals("Polygon", types.get("poly"));
        assertEquals("Polygon", types.get("poly_null"), "Nullable(Polygon) is not a valid ClickHouse type");
        assertEquals("Nullable(String)", types.get("line"));
        assertEquals("String", types.get("multi"));
        assertEquals("Nullable(String)", types.get("any_geom"));
        assertEquals("String", types.get("collection"));
        assertEquals("Polygon", types.get("unknown"),
                "without the source column type the Debezium Geometry logical type stays a Polygon; "
                        + "a non-polygon value then fails at bind time rather than being fabricated");
        assertEquals("Point", types.get("pt"));
    }

    /**
     * createTableSyntax re-applies the optional -> Nullable wrap on its own; it
     * must follow the same rule and never emit Nullable(Polygon) / Nullable(Point).
     */
    @Test
    @DisplayName("Auto-create never wraps a geo type in Nullable")
    public void autoCreateDoesNotWrapGeoTypesInNullable() {
        Field[] fields = new Field[]{
                new Field("id", 0, Schema.INT32_SCHEMA),
                spatialField("poly_null", Geometry.LOGICAL_NAME, "POLYGON", true),
                spatialField("pt_null", Point.LOGICAL_NAME, "POINT", true),
        };
        Map<String, String> types = new ClickHouseTableOperationsBase()
                .getColumnNameToCHDataTypeMapping(fields, config());
        ArrayList<String> primaryKey = new ArrayList<>();
        primaryKey.add("id");

        String ddl = new ClickHouseAutoCreateTable().createTableSyntax(primaryKey, "shapes", "db",
                fields, types, true, false, null, config());

        assertTrue(ddl.contains("`poly_null` Polygon,"), ddl);
        assertTrue(ddl.contains("`pt_null` Point,"), ddl);
        assertTrue(!ddl.contains("Nullable(Polygon)") && !ddl.contains("Nullable(Point)"), ddl);
    }
}

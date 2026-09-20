package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a requested type for a <b>sorting-key</b> column can be
 * satisfied by the column's existing ClickHouse type (Spec 06.05 §3.4).
 *
 * <p>ClickHouse rejects every type change of a sorting-key column with
 * {@code Code: 524 ALTER_OF_COLUMN_IS_FORBIDDEN} -- widening, narrowing, and
 * adding {@code Nullable} alike; only re-stating the identical type succeeds
 * (measured on 24.8.14). The translator therefore never emits a MODIFY for a
 * key column. What it must decide is whether <em>keeping</em> the existing
 * column is loss-free:</p>
 * <ul>
 *   <li>{@link Verdict#SAME_OR_NARROWER}: every value of the requested source
 *       type fits the existing ClickHouse type, so the clause is skipped and
 *       nothing is lost.</li>
 *   <li>{@link Verdict#WIDER}: the source can now hold values the existing
 *       column cannot; the change must be refused loudly (Invariant I9).</li>
 *   <li>{@link Verdict#NOT_COMPARABLE}: no width order is defined between the
 *       two types; treated like {@code WIDER} by the caller.</li>
 * </ul>
 *
 * <p>Width order: {@code Int8 < Int16 < Int32 < Int64 < Int128 < Int256} and
 * likewise for {@code UInt}; an unsigned {@code UIntN} fits a signed
 * {@code IntM} only when {@code N < M} (same width counts as widening the
 * maximum); a signed type never fits an unsigned column; {@code Decimal(p, s)}
 * is comparable only at equal scale, then by precision; everything else is
 * comparable only when the normalised type strings are identical.</p>
 */
final class KeyColumnTypeChange {

    /** Outcome of comparing the requested type with the existing one. */
    enum Verdict { SAME_OR_NARROWER, WIDER, NOT_COMPARABLE }

    private static final Pattern INTEGER = Pattern.compile("^(U?)Int(8|16|32|64|128|256)$");
    private static final Pattern DECIMAL = Pattern.compile("^Decimal\\((\\d+),(\\d+)\\)$");
    private static final Pattern DATETIME64_ZERO_TZ = Pattern.compile("^DateTime64\\((\\d+),0\\)$");

    private KeyColumnTypeChange() {
    }

    /**
     * @param existingType  the key column's type as rendered by
     *                      {@code system.columns} (may be {@code Nullable(...)}).
     * @param requestedType the type the translator would have emitted.
     * @return the verdict; {@code NOT_COMPARABLE} when either side is null.
     */
    static Verdict compare(String existingType, String requestedType) {
        if (existingType == null || requestedType == null) {
            return Verdict.NOT_COMPARABLE;
        }
        String existing = normalise(existingType);
        String requested = normalise(requestedType);
        if (existing.equals(requested)) {
            return Verdict.SAME_OR_NARROWER;
        }

        Matcher ei = INTEGER.matcher(existing);
        Matcher ri = INTEGER.matcher(requested);
        if (ei.matches() && ri.matches()) {
            boolean existingUnsigned = !ei.group(1).isEmpty();
            boolean requestedUnsigned = !ri.group(1).isEmpty();
            int existingBits = Integer.parseInt(ei.group(2));
            int requestedBits = Integer.parseInt(ri.group(2));
            if (existingUnsigned == requestedUnsigned) {
                return requestedBits <= existingBits ? Verdict.SAME_OR_NARROWER : Verdict.WIDER;
            }
            if (requestedUnsigned) {
                // UIntN fits IntM only when N < M: UInt16 fits Int32, UInt32 does not.
                return requestedBits < existingBits ? Verdict.SAME_OR_NARROWER : Verdict.WIDER;
            }
            // A signed source type can hold negatives an unsigned column cannot.
            return Verdict.WIDER;
        }

        Matcher ed = DECIMAL.matcher(existing);
        Matcher rd = DECIMAL.matcher(requested);
        if (ed.matches() && rd.matches()) {
            if (!ed.group(2).equals(rd.group(2))) {
                return Verdict.NOT_COMPARABLE;
            }
            return Integer.parseInt(rd.group(1)) <= Integer.parseInt(ed.group(1))
                    ? Verdict.SAME_OR_NARROWER : Verdict.WIDER;
        }

        return Verdict.NOT_COMPARABLE;
    }

    /**
     * Strips whitespace and the {@code Nullable}/{@code LowCardinality}
     * wrappers, and renders the translator's {@code DateTime64(p, 0)} the way
     * {@code system.columns} renders it ({@code DateTime64(p)}), so that a
     * re-declaration of an unchanged type compares equal.
     */
    static String normalise(String type) {
        String t = type.replaceAll("\\s+", "");
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String wrapper : new String[] {"Nullable(", "LowCardinality("}) {
                if (t.startsWith(wrapper) && t.endsWith(")")) {
                    t = t.substring(wrapper.length(), t.length() - 1);
                    stripped = true;
                }
            }
        }
        Matcher dt = DATETIME64_ZERO_TZ.matcher(t);
        if (dt.matches()) {
            t = "DateTime64(" + dt.group(1) + ")";
        }
        return t;
    }
}

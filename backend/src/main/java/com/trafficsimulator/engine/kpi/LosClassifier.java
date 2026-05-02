package com.trafficsimulator.engine.kpi;

/**
 * Density-based Level of Service classifier per CONTEXT.md §D-07.
 *
 * <p>Single-table simplification (highway = arterial) for v3.0 MVP; v4.0 OSM phase may split
 * tables. Thresholds are vehicles per kilometre per lane.
 *
 * <p>Boundaries (inclusive upper):
 * <ul>
 *   <li>A ≤ 7
 *   <li>B ≤ 11
 *   <li>C ≤ 16
 *   <li>D ≤ 22
 *   <li>E ≤ 28
 *   <li>F &gt; 28
 * </ul>
 */
public final class LosClassifier {
    private LosClassifier() {}

    /**
     * Classifies a per-lane density into a Level of Service letter A..F.
     *
     * @param densityPerKmPerLane density in vehicles per kilometre per lane
     * @return single character "A".."F" per D-07 boundaries
     */
    public static String classify(double densityPerKmPerLane) {
        if (densityPerKmPerLane <= 7) return "A";
        if (densityPerKmPerLane <= 11) return "B";
        if (densityPerKmPerLane <= 16) return "C";
        if (densityPerKmPerLane <= 22) return "D";
        if (densityPerKmPerLane <= 28) return "E";
        return "F";
    }

    /**
     * Returns the lexicographically-greater (worse) LOS letter. {@code null} arguments are treated
     * as "no opinion" — the non-null operand wins.
     *
     * <p>Examples: {@code worse("B", "D") == "D"}, {@code worse("A", null) == "A"}.
     */
    public static String worse(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) > 0 ? a : b;
    }
}

package com.bmsedge.inventory.util;

import java.util.*;

public class UnitOfMeasurementUtil {

    // Standard units mapped to their variations
    private static final Map<String, Set<String>> UNIT_VARIATIONS = new HashMap<>();
    private static final Set<String> STANDARD_UNITS = new HashSet<>();

    static {
        // Pieces/Numbers
        addUnit("pcs", "pcs", "pc", "piece", "pieces", "no", "nos", "no's", "numbers", "units", "unit");

        // Liters
        addUnit("liters", "ltr", "lts", "litres", "liters", "liter", "litre");

        // Bottles
        addUnit("bottles", "bottle", "bottles", "btl", "btls");

        // Packets
        addUnit("packets", "packet", "packets", "pkt", "pkts", "pack", "packs");

        // Kilograms
        addUnit("kg", "kg", "kgs", "kg's", "kilogram", "kilograms", "kilo", "kilos");

        // Boxes
        addUnit("box", "box", "boxes", "bx", "bxs", "carton", "cartons");

        // Pairs
        addUnit("pair", "pair", "pairs", "pr", "prs");

        // Rolls
        addUnit("roll", "roll", "rolls", "rl", "rls");

        // Additional common units
        addUnit("grams", "gram", "grams", "g", "gm", "gms");
        addUnit("meters", "meter", "meters", "metre", "metres", "m", "mt", "mtr");
        addUnit("sets", "set", "sets", "st", "sts");
        addUnit("dozen", "dozen", "doz", "dzn");
        addUnit("cans", "can", "cans");
        addUnit("jars", "jar", "jars");
        addUnit("tubes", "tube", "tubes");
        addUnit("sheets", "sheet", "sheets", "sht", "shts");
    }

    private static void addUnit(String standardUnit, String... variations) {
        STANDARD_UNITS.add(standardUnit);
        Set<String> variationSet = new HashSet<>(Arrays.asList(variations));
        UNIT_VARIATIONS.put(standardUnit, variationSet);
    }

    /**
     * Normalize a unit of measurement to its standard form
     * Returns values that are likely to pass database constraints
     */
    public static String normalizeUnit(String inputUnit) {
        if (inputUnit == null || inputUnit.trim().isEmpty()) {
            return "pcs"; // Default to pieces
        }

        String cleanInput = inputUnit.toLowerCase().trim().replaceAll("[^a-z0-9]", "");

        // Return exact input for common database values to avoid constraint issues
        String trimmedInput = inputUnit.trim();

        // Check if it's already a common database value
        String[] commonDbValues = {"pcs", "Ltr", "Lts", "LITRES", "No's", "Nos", "Bottles", "Packets", "Kgs", "Kg's", "Roll", "Box", "Boxes", "Pair", "Pairs"};
        for (String dbValue : commonDbValues) {
            if (trimmedInput.equalsIgnoreCase(dbValue)) {
                return dbValue; // Return as-is to match database constraint
            }
        }

        // Only normalize if we're confident about the mapping
        if (cleanInput.matches("ltr|lts|litres?|liters?")) {
            return "Ltr";
        } else if (cleanInput.matches("no'?s?|nos?|pieces?|pcs")) {
            return "pcs";
        } else if (cleanInput.matches("bottles?")) {
            return "Bottles";
        } else if (cleanInput.matches("packets?")) {
            return "Packets";
        } else if (cleanInput.matches("kgs?|kg'?s?|kilograms?")) {
            return "Kgs";
        } else if (cleanInput.matches("rolls?")) {
            return "Roll";
        } else if (cleanInput.matches("boxe?s?")) {
            return "Box";
        } else if (cleanInput.matches("pairs?")) {
            return "Pair";
        } else {
            // Return "pcs" as safe default for unknown units
            return "pcs";
        }
    }

    /**
     * Get all standard units
     */
    public static Set<String> getStandardUnits() {
        return new HashSet<>(STANDARD_UNITS);
    }

    /**
     * Check if a unit is valid (either standard or custom)
     */
    public static boolean isValidUnit(String unit) {
        if (unit == null || unit.trim().isEmpty()) {
            return false;
        }

        // Allow any non-empty string as a unit for flexibility
        String trimmed = unit.trim();
        return trimmed.length() > 0 && trimmed.length() <= 50;
    }

    /**
     * Get suggestions for similar units
     */
    public static List<String> getSuggestions(String inputUnit) {
        if (inputUnit == null || inputUnit.trim().isEmpty()) {
            return new ArrayList<>(STANDARD_UNITS);
        }

        String cleanInput = inputUnit.toLowerCase().trim();
        List<String> suggestions = new ArrayList<>();

        // Find units that start with or contain the input
        for (String standardUnit : STANDARD_UNITS) {
            if (standardUnit.toLowerCase().startsWith(cleanInput) ||
                    standardUnit.toLowerCase().contains(cleanInput)) {
                suggestions.add(standardUnit);
            }
        }

        // If no suggestions, return most common units
        if (suggestions.isEmpty()) {
            suggestions.addAll(Arrays.asList("pcs", "liters", "kg", "box", "packets"));
        }

        return suggestions;
    }

    /**
     * Get display name for a unit
     */
    public static String getDisplayName(String unit) {
        String normalized = normalizeUnit(unit);
        switch (normalized) {
            case "pcs": return "Pieces";
            case "liters": return "Liters";
            case "bottles": return "Bottles";
            case "packets": return "Packets";
            case "kg": return "Kilograms";
            case "box": return "Boxes";
            case "pair": return "Pairs";
            case "roll": return "Rolls";
            case "grams": return "Grams";
            case "meters": return "Meters";
            case "sets": return "Sets";
            case "dozen": return "Dozen";
            case "cans": return "Cans";
            case "jars": return "Jars";
            default: return normalized;
        }
    }
}
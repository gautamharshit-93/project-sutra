package com.sutra.extraction.service.internal;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Dependency-free token similarity, used when the Python matcher is unavailable. */
public final class TextSimilarity {

    private static final Set<String> STOP_WORDS = Set.of("a", "an", "and", "the", "of", "for", "with", "to", "in",
            "on", "by", "per", "as", "or", "x", "nos", "no", "pcs", "pc", "qty", "unit", "units", "each");

    private TextSimilarity() {
    }

    /**
     * Blend of Jaccard overlap and containment (how much of the shorter text appears in the longer one),
     * so "Dell Latitude 5440 laptop" scores high against "Laptop - Dell Latitude 5440, 16GB RAM".
     */
    public static double score(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0.0;
        }
        long common = ta.stream().filter(tb::contains).count();
        double jaccard = (double) common / (ta.size() + tb.size() - common);
        double containment = (double) common / Math.min(ta.size(), tb.size());
        return Math.round((0.4 * jaccard + 0.6 * containment) * 1000.0) / 1000.0;
    }

    static Set<String> tokens(String text) {
        if (text == null) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9.]+"))
                .map(t -> t.replaceAll("^\\.+|\\.+$", ""))
                .filter(t -> !t.isEmpty() && !STOP_WORDS.contains(t))
                .map(t -> t.length() > 3 && t.endsWith("s") ? t.substring(0, t.length() - 1) : t)
                .collect(Collectors.toSet());
    }
}

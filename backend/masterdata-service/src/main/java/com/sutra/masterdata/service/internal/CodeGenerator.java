package com.sutra.masterdata.service.internal;

import java.util.Locale;
import java.util.function.Predicate;

/** Makes the short codes of entities, sub-entities, customers and vendors, so people only type a name. */
public final class CodeGenerator {

    private CodeGenerator() {
    }

    /**
     * A short unique code made from a name: "Axiom Auto Components" -> "AXIOMAUTOC", then "AXIOMAUTOC-2" if that is
     * taken.
     */
    public static String fromName(String name, Predicate<String> taken) {
        String letters = name == null ? "" : name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        String base = letters.length() >= 2 ? letters.substring(0, Math.min(10, letters.length())) : "CODE" + letters;
        String code = base;
        for (int n = 2; taken.test(code); n++) {
            code = base + "-" + n;
        }
        return code;
    }

    /** A typed code, normalised: trimmed and upper case. */
    public static String normalise(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}

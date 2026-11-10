package com.sutra.api.rules;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds commercial terms a vendor left open, e.g. "UPS warranty not specified in this quotation" or
 * "delivery to be confirmed", and conditional wording such as "prices subject to stock availability".
 */
public final class TermsCheck {

    private static final Pattern INCOMPLETE = Pattern.compile(
            "\\b(not\\s+(specified|stated|provided|mentioned|included|confirmed)|to\\s+be\\s+(confirmed|advised|decided)"
                    + "|tbc|tba|unspecified)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONDITIONAL = Pattern.compile("\\bsubject\\s+to\\b", Pattern.CASE_INSENSITIVE);

    private TermsCheck() {
    }

    /** The sentence that leaves the term open, if any. */
    public static Optional<String> incomplete(String text) {
        return sentenceMatching(text, INCOMPLETE);
    }

    /** The sentence with conditional wording ("subject to ..."), if any. */
    public static Optional<String> conditional(String text) {
        return sentenceMatching(text, CONDITIONAL);
    }

    private static Optional<String> sentenceMatching(String text, Pattern pattern) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (String sentence : text.split("(?<=[.;])\\s+")) {
            Matcher m = pattern.matcher(sentence);
            if (m.find()) {
                String s = sentence.trim();
                return Optional.of(s.length() > 160 ? s.substring(0, 157) + "..." : s);
            }
        }
        return Optional.empty();
    }

    public static String label(String field) {
        return switch (field) {
            case "deliveryTerms" -> "Delivery terms";
            case "paymentTerms" -> "Payment terms";
            case "warrantyTerms" -> "Warranty terms";
            default -> field.toLowerCase(Locale.ROOT);
        };
    }
}

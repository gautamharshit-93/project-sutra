package com.sutra.comparison.service.internal;

import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.comparison.GradeReport;
import com.sutra.api.rules.TermsCheck;
import com.sutra.api.comparison.GradeReport.GradeRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grades a vendor's quotation for the comparison with fixed, visible rules. The grade is advice: it does not rank
 * vendors for award, select a vendor or approve anything - an authorised approver decides.
 *
 * <table>
 *   <caption>Rules (100 points)</caption>
 *   <tr><td>PRICE</td><td>40</td><td>Lowest complete quotation 40; minus 2 points per 1% above it;
 *       0 when not ranked (incomplete or another currency)</td></tr>
 *   <tr><td>COVERAGE</td><td>15</td><td>Share of requested items quoted</td></tr>
 *   <tr><td>DELIVERY</td><td>10</td><td>Up to 14 days 10, up to 30 days 8, longer or no days stated 6,
 *       left open 4, missing 0</td></tr>
 *   <tr><td>PAYMENT</td><td>10</td><td>Credit terms (no advance) 10, part advance 7, full advance 4,
 *       left open 4, missing 0</td></tr>
 *   <tr><td>WARRANTY</td><td>10</td><td>Stated for all items 10, partly unspecified 5, missing 0</td></tr>
 *   <tr><td>VALIDITY</td><td>10</td><td>Valid 7+ more days 10, under 7 days 6, missing or expired 0</td></tr>
 *   <tr><td>DATA_QUALITY</td><td>5</td><td>Minus 1 per open review flag</td></tr>
 * </table>
 */
public final class VendorGrader {

    private static final Pattern DAYS = Pattern.compile("(\\d{1,3})\\s*(?:calendar\\s+|working\\s+|business\\s+)?days?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKS = Pattern.compile("(\\d{1,2})\\s*weeks?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADVANCE_PERCENT = Pattern.compile("(\\d{1,3})\\s*%\\s*(?:advance|in\\s+advance|upfront)",
            Pattern.CASE_INSENSITIVE);

    private VendorGrader() {
    }

    /**
     * @param differencePercent how far the quotation's comparable total is above the lowest, in % (null if unranked)
     */
    public static GradeReport grade(QuotationResponse q, Integer priceRank, BigDecimal differencePercent, int itemsQuoted,
                                    int itemsRequested, LocalDate today) {
        List<GradeRule> rules = new ArrayList<>();

        if (priceRank == null) {
            rules.add(GradeReport.rule("PRICE", "Price", 40, 0,
                    "Not ranked: not every requested item is priced in the request currency"));
        } else {
            double diff = differencePercent == null ? 0 : differencePercent.doubleValue();
            rules.add(GradeReport.rule("PRICE", "Price", 40, (40 - 2 * diff) / 40,
                    priceRank == 1 ? "Lowest complete quotation" : "Rank " + priceRank + ", " + percent(diff) + " above lowest"));
        }

        rules.add(GradeReport.rule("COVERAGE", "Requested items quoted", 15,
                itemsRequested == 0 ? 0 : (double) itemsQuoted / itemsRequested, itemsQuoted + " of " + itemsRequested));

        rules.add(delivery(q.deliveryTerms()));
        rules.add(payment(q.paymentTerms()));
        rules.add(warranty(q.warrantyTerms()));
        rules.add(validity(q.validUntil(), today));

        long open = ComparisonEngine.openFlags(q);
        rules.add(GradeReport.rule("DATA_QUALITY", "Open review flags", 5, (5 - open) / 5.0,
                open == 0 ? "None" : open + " open"));
        return GradeReport.of(rules);
    }

    static GradeRule delivery(String terms) {
        if (blank(terms)) {
            return GradeReport.rule("DELIVERY", "Delivery", 10, 0, "Not stated");
        }
        if (TermsCheck.incomplete(terms).isPresent()) {
            return GradeReport.rule("DELIVERY", "Delivery", 10, 0.4, "Left open: " + TermsCheck.incomplete(terms).get());
        }
        Integer days = days(terms);
        if (days == null) {
            return GradeReport.rule("DELIVERY", "Delivery", 10, 0.6, "No delivery time in days");
        }
        double fraction = days <= 14 ? 1 : days <= 30 ? 0.8 : 0.6;
        return GradeReport.rule("DELIVERY", "Delivery", 10, fraction, days + " days");
    }

    static GradeRule payment(String terms) {
        if (blank(terms)) {
            return GradeReport.rule("PAYMENT", "Payment", 10, 0, "Not stated");
        }
        if (TermsCheck.incomplete(terms).isPresent()) {
            return GradeReport.rule("PAYMENT", "Payment", 10, 0.4, "Left open: " + TermsCheck.incomplete(terms).get());
        }
        String lower = terms.toLowerCase(Locale.ROOT);
        Matcher m = ADVANCE_PERCENT.matcher(terms);
        if (m.find()) {
            int pct = Integer.parseInt(m.group(1));
            return pct >= 100
                    ? GradeReport.rule("PAYMENT", "Payment", 10, 0.4, "Full payment in advance")
                    : GradeReport.rule("PAYMENT", "Payment", 10, 0.7, pct + "% advance");
        }
        if (lower.contains("advance") || lower.contains("upfront")) {
            boolean full = lower.contains("100%") || lower.contains("full");
            return GradeReport.rule("PAYMENT", "Payment", 10, full ? 0.4 : 0.7, full ? "Full advance" : "Part advance");
        }
        return GradeReport.rule("PAYMENT", "Payment", 10, 1, "No advance");
    }

    static GradeRule warranty(String terms) {
        if (blank(terms)) {
            return GradeReport.rule("WARRANTY", "Warranty", 10, 0, "Not stated");
        }
        return TermsCheck.incomplete(terms)
                .map(s -> GradeReport.rule("WARRANTY", "Warranty", 10, 0.5, "Partly unspecified: " + s))
                .orElseGet(() -> GradeReport.rule("WARRANTY", "Warranty", 10, 1, "Stated"));
    }

    static GradeRule validity(LocalDate validUntil, LocalDate today) {
        if (validUntil == null) {
            return GradeReport.rule("VALIDITY", "Validity", 10, 0, "No validity date");
        }
        long left = ChronoUnit.DAYS.between(today, validUntil);
        if (left < 0) {
            return GradeReport.rule("VALIDITY", "Validity", 10, 0, "Expired on " + validUntil);
        }
        return GradeReport.rule("VALIDITY", "Validity", 10, left >= 7 ? 1 : 0.6, left + " day(s) left");
    }

    /** Delivery time in days from text such as "within 12 calendar days" or "2-3 weeks" (the longest figure). */
    static Integer days(String terms) {
        Integer best = null;
        Matcher d = DAYS.matcher(terms);
        while (d.find()) {
            int v = Integer.parseInt(d.group(1));
            best = best == null ? v : Math.max(best, v);
        }
        Matcher w = WEEKS.matcher(terms);
        while (w.find()) {
            int v = Integer.parseInt(w.group(1)) * 7;
            best = best == null ? v : Math.max(best, v);
        }
        return best;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String percent(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }
}

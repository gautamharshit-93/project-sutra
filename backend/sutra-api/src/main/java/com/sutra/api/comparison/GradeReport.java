package com.sutra.api.comparison;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A score out of 100 built from named rules, with a letter grade. Every rule shows the points earned, the maximum
 * and why, so a grade can always be explained. Grades are advice for reviewers; they never approve anything.
 *
 * <p>Letters: A 85-100, B 70-84, C 55-69, D 40-54, F below 40.</p>
 */
public record GradeReport(int score, String grade, List<GradeRule> rules) {

    public record GradeRule(String code, String label, BigDecimal points, int maxPoints, String detail) {
    }

    public static GradeReport of(List<GradeRule> rules) {
        BigDecimal earned = rules.stream().map(GradeRule::points).reduce(BigDecimal.ZERO, BigDecimal::add);
        int max = rules.stream().mapToInt(GradeRule::maxPoints).sum();
        int score = max == 0 ? 0 : earned.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(max), 0, RoundingMode.HALF_UP).intValue();
        return new GradeReport(score, letter(score), List.copyOf(rules));
    }

    public static String letter(int score) {
        if (score >= 85) {
            return "A";
        }
        if (score >= 70) {
            return "B";
        }
        if (score >= 55) {
            return "C";
        }
        if (score >= 40) {
            return "D";
        }
        return "F";
    }

    /** A rule worth {@code max} points, earning {@code fraction} (0..1) of them. */
    public static GradeRule rule(String code, String label, int max, double fraction, String detail) {
        double clamped = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
        BigDecimal points = BigDecimal.valueOf(max * clamped).setScale(1, RoundingMode.HALF_UP);
        return new GradeRule(code, label, points, max, detail);
    }
}

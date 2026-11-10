package com.sutra.extraction.service.internal;

import com.sutra.api.extraction.ExtractedQuotation.ExtractedItem;
import com.sutra.api.internal.InternalContracts.LineMatch;
import com.sutra.api.internal.InternalContracts.RequestedLine;
import com.sutra.extraction.client.DocumentProcessingClient;
import com.sutra.extraction.client.DocumentProcessingClient.MatchQuotedItem;
import com.sutra.extraction.client.DocumentProcessingClient.MatchRequestItem;
import com.sutra.extraction.client.DocumentProcessingClient.MatchScore;
import com.sutra.extraction.exception.QuotationProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Matches quoted lines to requested lines. Exact item codes first (e.g. IT-001). The remaining lines are scored
 * with the Python fuzzy matcher, falling back to local token similarity, then assigned greedily from the highest
 * score down, so each requested line is used at most once. Lines without a description are never matched.
 */
@Component
public class HybridItemMatcher {

    /** Score below which a quoted line is left unmatched. */
    public static final BigDecimal MINIMUM_SCORE = new BigDecimal("0.35");

    private static final Logger log = LoggerFactory.getLogger(HybridItemMatcher.class);

    private final DocumentProcessingClient documentClient;

    public HybridItemMatcher(DocumentProcessingClient documentClient) {
        this.documentClient = documentClient;
    }

    /** {@code LineMatch.index} is the position of the quoted line in {@code quoted}. */
    public List<LineMatch> match(List<RequestedLine> requested, List<ExtractedItem> quoted) {
        List<LineMatch> matches = new ArrayList<>();
        if (requested.isEmpty() || quoted.isEmpty()) {
            return matches;
        }
        Set<Integer> usedQuoted = new HashSet<>();
        Set<Long> usedRequested = new HashSet<>();
        for (int i = 0; i < quoted.size(); i++) {
            if (isBlank(quoted.get(i).description())) {
                usedQuoted.add(i); // skipped by the quotation service as well
            }
        }
        // 1. Exact item-code matches (vendor line "IT-001" -> request line "IT-001") are certain.
        Map<String, RequestedLine> byCode = new HashMap<>();
        for (RequestedLine r : requested) {
            String code = normaliseCode(r.itemCode());
            if (code != null) {
                byCode.putIfAbsent(code, r);
            }
        }
        for (int i = 0; i < quoted.size(); i++) {
            if (usedQuoted.contains(i)) {
                continue;
            }
            RequestedLine match = byCode.get(normaliseCode(quoted.get(i).itemCode().orElse(null)));
            if (match != null && !usedRequested.contains(match.id())) {
                matches.add(new LineMatch(i, match.id(), BigDecimal.ONE.setScale(3, RoundingMode.HALF_UP)));
                usedQuoted.add(i);
                usedRequested.add(match.id());
            }
        }
        if (usedQuoted.size() == quoted.size() || usedRequested.size() == requested.size()) {
            return matches;
        }
        // 2. Fuzzy description matching for the rest, highest score first.
        score(requested, quoted).stream()
                .filter(s -> s.score() >= MINIMUM_SCORE.doubleValue())
                .sorted(Comparator.comparingDouble(MatchScore::score).reversed().thenComparingInt(MatchScore::quotedIndex))
                .forEach(s -> {
                    if (usedQuoted.contains(s.quotedIndex()) || usedRequested.contains(s.requestItemId())) {
                        return;
                    }
                    matches.add(new LineMatch(s.quotedIndex(), s.requestItemId(),
                            BigDecimal.valueOf(Math.min(1.0, s.score())).setScale(3, RoundingMode.HALF_UP)));
                    usedQuoted.add(s.quotedIndex());
                    usedRequested.add(s.requestItemId());
                });
        matches.sort(Comparator.comparingInt(LineMatch::index));
        return matches;
    }

    /** "it-001", "IT 001" and "IT001" are the same code. */
    static String normaliseCode(String code) {
        if (code == null) {
            return null;
        }
        String normalised = code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return normalised.isEmpty() ? null : normalised;
    }

    List<MatchScore> score(List<RequestedLine> requested, List<ExtractedItem> quoted) {
        List<MatchRequestItem> req = requested.stream()
                .map(r -> new MatchRequestItem(r.id(), r.description(), r.specifications(), r.unitOfMeasure(), r.quantity()))
                .toList();
        List<MatchQuotedItem> quo = new ArrayList<>();
        for (int i = 0; i < quoted.size(); i++) {
            ExtractedItem q = quoted.get(i);
            if (!isBlank(q.description())) {
                quo.add(new MatchQuotedItem(i, q.description(), q.unitOfMeasure().orElse(null), q.quantity().orElse(null)));
            }
        }
        try {
            return documentClient.match(req, quo);
        } catch (QuotationProcessingException e) {
            log.warn("Python matcher unavailable, using local token similarity: {}", e.getMessage());
            return localScores(requested, quoted);
        }
    }

    static List<MatchScore> localScores(List<RequestedLine> requested, List<ExtractedItem> quoted) {
        List<MatchScore> scores = new ArrayList<>();
        for (int i = 0; i < quoted.size(); i++) {
            String description = quoted.get(i).description();
            if (isBlank(description)) {
                continue;
            }
            for (RequestedLine r : requested) {
                String target = r.description() + (r.specifications() == null ? "" : " " + r.specifications());
                double s = Math.max(TextSimilarity.score(description, r.description()),
                        0.9 * TextSimilarity.score(description, target));
                scores.add(new MatchScore(i, r.id(), s));
            }
        }
        return scores;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

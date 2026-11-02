package com.sutra.ai;

import com.sutra.ai.DocumentProcessingClient.MatchQuotedItem;
import com.sutra.ai.DocumentProcessingClient.MatchRequestItem;
import com.sutra.ai.DocumentProcessingClient.MatchScore;
import com.sutra.entity.QuotationItem;
import com.sutra.entity.RequestItem;
import com.sutra.exception.QuotationProcessingException;
import com.sutra.util.TextSimilarity;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Matches exact item codes first (e.g. IT-001). The remaining lines are scored with the Python fuzzy matcher,
 * falling back to local token similarity,
 * then assigns greedily from the highest score down so each requested line is used at most once.
 */
@Component
public class HybridItemMatcher implements ItemMatcher {

    private static final Logger log = LoggerFactory.getLogger(HybridItemMatcher.class);

    private final DocumentProcessingClient documentClient;

    public HybridItemMatcher(DocumentProcessingClient documentClient) {
        this.documentClient = documentClient;
    }

    @Override
    public void assign(List<RequestItem> requested, List<QuotationItem> quoted) {
        if (requested.isEmpty() || quoted.isEmpty()) {
            return;
        }
        Set<Integer> usedQuoted = new HashSet<>();
        Set<Long> usedRequested = new HashSet<>();

        // 1. Exact item-code matches (e.g. vendor line "IT-001" -> request line "IT-001") are certain.
        Map<String, RequestItem> byCode = new HashMap<>();
        for (RequestItem r : requested) {
            String code = normaliseCode(r.getItemCode());
            if (code != null) {
                byCode.putIfAbsent(code, r);
            }
        }
        for (int i = 0; i < quoted.size(); i++) {
            RequestItem match = byCode.get(normaliseCode(quoted.get(i).getItemCode()));
            if (match != null && !usedRequested.contains(match.getId())) {
                quoted.get(i).setRequestItem(match);
                quoted.get(i).setMatchConfidence(BigDecimal.ONE.setScale(3, RoundingMode.HALF_UP));
                usedQuoted.add(i);
                usedRequested.add(match.getId());
            }
        }
        if (usedQuoted.size() == quoted.size() || usedRequested.size() == requested.size()) {
            return;
        }

        // 2. Fuzzy description matching for the rest, highest score first.
        List<MatchScore> scores = score(requested, quoted);
        Map<Long, RequestItem> byId = requested.stream().collect(Collectors.toMap(RequestItem::getId, Function.identity()));
        scores.stream()
                .filter(s -> s.score() >= MINIMUM_SCORE.doubleValue())
                .sorted(Comparator.comparingDouble(MatchScore::score).reversed()
                        .thenComparingInt(MatchScore::quotedIndex))
                .forEach(s -> {
                    if (usedQuoted.contains(s.quotedIndex()) || usedRequested.contains(s.requestItemId())) {
                        return;
                    }
                    QuotationItem item = quoted.get(s.quotedIndex());
                    item.setRequestItem(byId.get(s.requestItemId()));
                    item.setMatchConfidence(BigDecimal.valueOf(Math.min(1.0, s.score())).setScale(3, RoundingMode.HALF_UP));
                    usedQuoted.add(s.quotedIndex());
                    usedRequested.add(s.requestItemId());
                });
    }

    /** "it-001", "IT 001" and "IT001" are the same code. */
    static String normaliseCode(String code) {
        if (code == null) {
            return null;
        }
        String normalised = code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return normalised.isEmpty() ? null : normalised;
    }

    List<MatchScore> score(List<RequestItem> requested, List<QuotationItem> quoted) {
        List<MatchRequestItem> req = requested.stream()
                .map(r -> new MatchRequestItem(r.getId(), r.getDescription(), r.getSpecifications(),
                        r.getUnitOfMeasure(), r.getQuantity()))
                .toList();
        List<MatchQuotedItem> quo = new ArrayList<>();
        for (int i = 0; i < quoted.size(); i++) {
            QuotationItem q = quoted.get(i);
            quo.add(new MatchQuotedItem(i, q.getDescription(), q.getUnitOfMeasure(), q.getQuantity()));
        }
        try {
            return documentClient.match(req, quo);
        } catch (QuotationProcessingException e) {
            log.warn("Python matcher unavailable, using local token similarity: {}", e.getMessage());
            return localScores(requested, quoted);
        }
    }

    static List<MatchScore> localScores(List<RequestItem> requested, List<QuotationItem> quoted) {
        List<MatchScore> scores = new ArrayList<>();
        for (int i = 0; i < quoted.size(); i++) {
            for (RequestItem r : requested) {
                String target = r.getDescription() + (r.getSpecifications() == null ? "" : " " + r.getSpecifications());
                double s = Math.max(TextSimilarity.score(quoted.get(i).getDescription(), r.getDescription()),
                        0.9 * TextSimilarity.score(quoted.get(i).getDescription(), target));
                scores.add(new MatchScore(i, r.getId(), s));
            }
        }
        return scores;
    }
}

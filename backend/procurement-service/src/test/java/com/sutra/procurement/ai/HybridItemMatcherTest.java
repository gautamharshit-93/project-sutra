package com.sutra.procurement.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sutra.procurement.ai.DocumentProcessingClient.MatchScore;
import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.QuotationItem;
import com.sutra.procurement.entity.RequestItem;
import com.sutra.common.security.RoleName;
import com.sutra.procurement.exception.QuotationProcessingException;
import com.sutra.procurement.support.Fixtures;
import com.sutra.procurement.util.TextSimilarity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HybridItemMatcherTest {

    private final DocumentProcessingClient client = mock(DocumentProcessingClient.class);
    private final HybridItemMatcher matcher = new HybridItemMatcher(client);
    private PurchaseRequest pr;
    private Quotation q;

    @BeforeEach
    void setUp() {
        pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
        q = Fixtures.quotation(pr, Fixtures.vendor("Alpha"));
    }

    private RequestItem laptops() {
        return pr.getItems().get(0);
    }

    private RequestItem monitors() {
        return pr.getItems().get(1);
    }

    @Test
    void assignsEachRequestLineAtMostOnceHighestScoreFirst() {
        QuotationItem a = Fixtures.item(q, null, "Laptop basic", "10", "1", null);
        QuotationItem b = Fixtures.item(q, null, "Laptop premium", "10", "1", null);
        when(client.match(any(), any())).thenReturn(List.of(
                new MatchScore(0, laptops().getId(), 0.70),
                new MatchScore(1, laptops().getId(), 0.90),
                new MatchScore(0, monitors().getId(), 0.20),
                new MatchScore(1, monitors().getId(), 0.10)));

        matcher.assign(pr.getItems(), q.getItems());

        assertThat(b.getRequestItem()).isSameAs(laptops());
        assertThat(b.getMatchConfidence()).isEqualByComparingTo("0.900");
        assertThat(a.getRequestItem()).as("laptop line already taken; monitor score too low").isNull();
    }

    @Test
    void fallsBackToLocalSimilarityWhenPythonServiceIsDown() {
        QuotationItem monitor = Fixtures.item(q, null, "Dell 24 inch LED Monitor P2422H", "10", "1", null);
        QuotationItem laptop = Fixtures.item(q, null, "Dell Latitude 5440 Laptop i5", "10", "1", null);
        when(client.match(any(), any())).thenThrow(new QuotationProcessingException("down"));

        matcher.assign(pr.getItems(), q.getItems());

        assertThat(laptop.getRequestItem()).isSameAs(laptops());
        assertThat(monitor.getRequestItem()).isSameAs(monitors());
    }

    @Test
    void identicalItemCodesMatchExactlyBeforeAnyFuzzyScoring() {
        laptops().setItemCode("IT-001");
        monitors().setItemCode("IT-002");
        QuotationItem monitor = Fixtures.item(q, null, "Some display", "10", "1", null);
        monitor.setItemCode("it 002");
        QuotationItem laptop = Fixtures.item(q, null, "Notebook computer", "10", "1", null);
        laptop.setItemCode("IT001");

        matcher.assign(pr.getItems(), q.getItems());

        assertThat(monitor.getRequestItem()).isSameAs(monitors());
        assertThat(laptop.getRequestItem()).isSameAs(laptops());
        assertThat(laptop.getMatchConfidence()).isEqualByComparingTo("1.000");
        org.mockito.Mockito.verifyNoInteractions(client); // nothing left for the fuzzy matcher
    }

    @Test
    void localSimilarityToleratesWordOrderAndExtraWords() {
        assertThat(TextSimilarity.score("Dell Latitude 5440 laptop", "Laptop - Dell Latitude 5440, 16GB RAM"))
                .isGreaterThan(0.7);
        assertThat(TextSimilarity.score("Office chair", "Laptop Dell Latitude")).isLessThan(0.2);
    }
}

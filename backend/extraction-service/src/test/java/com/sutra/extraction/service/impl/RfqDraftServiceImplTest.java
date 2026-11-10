package com.sutra.extraction.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sutra.extraction.client.DocumentProcessingClient;
import com.sutra.extraction.client.GroqClient;
import com.sutra.extraction.service.internal.PdfOcrReader;
import com.sutra.extraction.exception.AiServiceException;
import com.sutra.extraction.exception.InvalidQuotationFileException;
import com.sutra.extraction.client.DocumentStoreClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The model's RFQ draft is cleaned to fit SUTRA's limits; gaps become warnings the buyer sees, not errors. */
class RfqDraftServiceImplTest {

    private final RfqDraftServiceImpl service = new RfqDraftServiceImpl(mock(GroqClient.class),
            mock(DocumentProcessingClient.class), mock(PdfOcrReader.class), mock(DocumentStoreClient.class),
            JsonMapper.builder().build(), Clock.fixed(Instant.parse("2026-11-09T06:00:00Z"), ZoneOffset.UTC));

    @Test
    void cleansTheDraftAndWarnsAboutGaps() {
        var draft = service.parse("""
                {"title":"Production materials Q1","description":"Delivery to Chakan plant","requiredBy":"2026-12-15",
                 "currency":"inr","buyerName":"Axiom Auto","customerName":null,
                 "items":[
                   {"itemCode":"MT-101","description":"CRCA sheet 1.2 mm","quantity":12,"unitOfMeasure":"Tonne","specifications":null},
                   {"itemCode":null,"description":"Hex bolt M8","quantity":null,"unitOfMeasure":null,"specifications":"grade 8.8"},
                   {"itemCode":null,"description":"   ","quantity":5,"unitOfMeasure":"Nos","specifications":null}],
                 "warnings":["Quotation due date not stated"]}""", "rfq.pdf");

        assertThat(draft.title()).isEqualTo("Production materials Q1");
        assertThat(draft.currency()).isEqualTo("INR");
        assertThat(draft.requiredBy()).isEqualTo("2026-12-15");
        assertThat(draft.items()).hasSize(2);
        assertThat(draft.items().get(0).quantity()).isEqualByComparingTo(new BigDecimal("12"));
        assertThat(draft.items().get(1).quantity()).isNull();
        assertThat(draft.items().get(1).unitOfMeasure()).isEqualTo("Nos");
        assertThat(draft.warnings()).contains("Quotation due date not stated")
                .anyMatch(w -> w.contains("Hex bolt M8") && w.contains("no quantity"));
    }

    @Test
    void anUnreadableDateOrEmptyListIsAWarning() {
        var draft = service.parse("""
                {"title":"","description":null,"requiredBy":"next month","currency":"rupees","buyerName":null,
                 "customerName":null,"items":[],"warnings":[]}""", "pasted text");

        assertThat(draft.title()).isEqualTo("RFQ from pasted text");
        assertThat(draft.requiredBy()).isNull();
        assertThat(draft.currency()).isNull();
        assertThat(draft.warnings()).anyMatch(w -> w.contains("next month"))
                .anyMatch(w -> w.contains("No requested items"));
    }

    @Test
    void rejectsEmptyInputAndBadJson() {
        assertThatThrownBy(() -> service.fromText("  ")).isInstanceOf(InvalidQuotationFileException.class);
        assertThatThrownBy(() -> service.fromFiles(List.of())).isInstanceOf(InvalidQuotationFileException.class);
        assertThatThrownBy(() -> service.parse("not json", "x")).isInstanceOf(AiServiceException.class);
    }
}

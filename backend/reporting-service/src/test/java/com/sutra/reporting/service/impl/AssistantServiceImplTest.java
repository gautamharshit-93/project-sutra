package com.sutra.reporting.service.impl;

import com.sutra.api.internal.InternalContracts.AiCompletionRequest;
import com.sutra.api.internal.InternalContracts.AiCompletion;
import com.sutra.api.internal.InternalContracts.AiStatus;
import com.sutra.reporting.exception.AiServiceException;
import com.sutra.reporting.service.DashboardService;
import com.sutra.reporting.client.PlatformClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.web.PageResponse;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.reporting.dto.request.AssistantRequests.ChatRequest;
import com.sutra.reporting.dto.request.AssistantRequests.ChatTurn;
import com.sutra.api.decision.DecisionResponses.DashboardResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestSummary;
import com.sutra.api.quotation.QuotationResponses.QuotationSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The assistant reads data through the normal services, never invents links, and needs Groq. */
class AssistantServiceImplTest {

    private final PlatformClient platform = mock(PlatformClient.class);
    private final DashboardService dashboard = mock(DashboardService.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AssistantServiceImpl service =
            new AssistantServiceImpl(platform, dashboard, currentUser, mapper);

    private static final PurchaseRequestSummary PR = new PurchaseRequestSummary(7L, "PR-2026-00007", "Steel sheets",
            PurchaseRequestStatus.OPEN, "Sutra Auto", "priya", "INR", LocalDate.of(2026, 12, 1), 3, Instant.EPOCH, null);

    private static QuotationSummary quotation(long id, String vendor, QuotationStatus status) {
        return new QuotationSummary(id, 7L, "PR-2026-00007", 1L, vendor, "Q-" + id, status, "INR",
                new BigDecimal("118000.00"), null, null, null, 2, 3, null, 1, "priya", false, Instant.EPOCH);
    }

    @BeforeEach
    void setUp() {
        when(platform.aiStatus()).thenReturn(new AiStatus(true, "groq-test", "GROQ", "groq-test", true, true));
        when(currentUser.username()).thenReturn("priya");
        when(dashboard.summary()).thenReturn(new DashboardResponse(1L, 1L, null,
                Map.of("OPEN", 1L), Map.of("NEEDS_REVIEW", 1L), "RULES", "rules", true, true));
        when(platform.searchRfqs(isNull(), isNull(), eq(0), anyInt())).thenReturn(new PageResponse<>(List.of(PR), 0, 30, 1, 1));
        when(platform.quotationSummaries(7L)).thenReturn(List.of(
                quotation(11L, "Shakti Metals", QuotationStatus.NEEDS_REVIEW),
                quotation(12L, "Vertex Auto", QuotationStatus.READY_FOR_COMPARISON)));
        when(platform.comparisons(7L)).thenReturn(List.of());
    }

    private void groqAnswers(String content) {
        when(platform.complete(any())).thenReturn(new AiCompletion(content, "groq-test", 100, 20));
    }

    @Test
    void isDisabledWithoutGroq() {
        when(platform.aiStatus()).thenReturn(new AiStatus(false, null, "RULES", "rules v1", true, true));

        assertThat(service.status().enabled()).isFalse();
        assertThat(service.status().reason()).contains("GROQ_API_KEY");
        assertThatThrownBy(() -> service.chat(new ChatRequest("hello", null, null, null)))
                .isInstanceOf(AiServiceException.class);
        verify(platform, never()).complete(any());
    }

    @Test
    void answersFromTheSnapshotAndDropsInventedLinks() {
        groqAnswers("{\"reply\":\"Shakti Metals ka quotation review mein hai.\",\"links\":["
                + "{\"type\":\"QUOTATION\",\"id\":11},{\"type\":\"QUOTATION\",\"id\":999},"
                + "{\"type\":\"PURCHASE_REQUEST\",\"id\":7},{\"type\":\"QUOTATION\",\"id\":11}]}");

        var answer = service.chat(new ChatRequest("kaunsa quotation review mein hai?",
                List.of(new ChatTurn("user", "namaste"), new ChatTurn("assistant", "Namaste!")), null, null));

        assertThat(answer.reply()).isEqualTo("Shakti Metals ka quotation review mein hai.");
        assertThat(answer.links()).extracting(l -> l.type() + ":" + l.id() + ":" + l.label())
                .containsExactly("QUOTATION:11:Shakti Metals · PR-2026-00007", "PURCHASE_REQUEST:7:PR-2026-00007");

        ArgumentCaptor<AiCompletionRequest> input = ArgumentCaptor.forClass(AiCompletionRequest.class);
        verify(platform).complete(input.capture());
        String sent = input.getValue().messages().stream().map(p -> (String) p.get("content")).reduce("", String::concat);
        assertThat(sent).contains("READ-ONLY", "Hinglish", "PR-2026-00007", "\"quotationsNeedingAttention\"",
                "Shakti Metals", "User: namaste", "kaunsa quotation review mein hai?");
    }

    @Test
    void loadsDetailForTheRecordsTheUserNames() {
        groqAnswers("{\"reply\":\"ok\",\"links\":[]}");
        when(platform.rfq(7L)).thenReturn(new PurchaseRequestResponse(7L, "PR-2026-00007", "Steel sheets", null,
                PurchaseRequestStatus.OPEN, 1L, "Sutra Auto", null, null, null, null, 2L, "priya", "INR", null, true,
                List.of(), Instant.EPOCH, Instant.EPOCH, null));

        service.chat(new ChatRequest("pr-2026-00007 ka status batao", null, null, null));

        verify(platform).rfq(7L);
        assertThat(service.focusQuotations(new ChatRequest("कोटेशन नंबर 42 देखो", null, null, 5L))).containsExactly(5L, 42L);
    }

    @Test
    void answersInTheUsersLanguageAndScript() {
        assertThat(AssistantServiceImpl.languageDirective("किन कोटेशन को रिव्यू चाहिए?")).contains("Devanagari");
        assertThat(AssistantServiceImpl.languageDirective("sabse sasta quotation kiska hai?")).contains("Hinglish")
                .contains("ONLY Latin letters");
        assertThat(AssistantServiceImpl.languageDirective("Which vendor is the cheapest?")).isEqualTo("Reply in English.");
        assertThat(AssistantServiceImpl.conversation(new ChatRequest("kaunsa vendor sasta hai", null, null, null)))
                .endsWith("(The user wrote Hinglish in Latin letters. Reply in Hinglish using ONLY Latin letters - no Devanagari.)");
    }

    @Test
    void amountsArePreformattedWithIndianGroupingForRupees() {
        assertThat(AssistantServiceImpl.money(new BigDecimal("1778260"), "INR")).isEqualTo("₹17,78,260.00");
        assertThat(AssistantServiceImpl.money(new BigDecimal("123456789.5"), "INR")).isEqualTo("₹12,34,56,789.50");
        assertThat(AssistantServiceImpl.money(new BigDecimal("999"), "INR")).isEqualTo("₹999.00");
        assertThat(AssistantServiceImpl.money(new BigDecimal("1000"), "INR")).isEqualTo("₹1,000.00");
        assertThat(AssistantServiceImpl.money(new BigDecimal("-1180"), "INR")).isEqualTo("-₹1,180.00");
        assertThat(AssistantServiceImpl.money(new BigDecimal("1234567.891"), "usd")).isEqualTo("USD 1,234,567.89");
        assertThat(AssistantServiceImpl.money(null, "INR")).isNull();
    }

    @Test
    void showsPlainTextWhenTheModelDidNotReturnJson() {
        groqAnswers("Sorry, I could not format that.");

        var answer = service.chat(new ChatRequest("hi", null, null, null));

        assertThat(answer.reply()).isEqualTo("Sorry, I could not format that.");
        assertThat(answer.links()).isEmpty();
    }

    @Test
    void statusesUseTheAppsLabelsNotRawCodes() {
        assertThat(AssistantServiceImpl.label(QuotationStatus.READY_FOR_COMPARISON)).isEqualTo("Ready for comparison");
        assertThat(AssistantServiceImpl.label(QuotationStatus.NEEDS_REVIEW)).isEqualTo("Needs review");
        assertThat(AssistantServiceImpl.label(PurchaseRequestStatus.APPROVED)).isEqualTo("Awarded");
        assertThat(AssistantServiceImpl.label(PurchaseRequestStatus.UNDER_REVIEW)).isEqualTo("Comparing");
        assertThat(AssistantServiceImpl.label("OPEN")).isEqualTo("Open");
    }

    @Test
    void aPastedRfqComesBackAsADraftCardForBuyersOnly() {
        groqAnswers("{\"reply\":\"Draft ready, please check.\",\"links\":[],\"action\":\"DRAFT_RFQ\"}");
        var draft = new com.sutra.api.rfq.RfqDraftResponses.RfqDraft("Steel", null, null, "INR", null, null,
                List.of(), List.of(), "pasted text", null);
        when(platform.rfqDraftFromText("Need 12 tonne CRCA sheet")).thenReturn(draft);
        when(currentUser.hasRole(com.sutra.common.security.RoleName.PROCUREMENT)).thenReturn(true);

        var answer = service.chat(new ChatRequest("Need 12 tonne CRCA sheet", null, null, null));

        assertThat(answer.rfqDraft()).isSameAs(draft);
        assertThat(answer.reply()).isEqualTo("Draft ready, please check.");

        when(currentUser.hasRole(com.sutra.common.security.RoleName.PROCUREMENT)).thenReturn(false);
        var auditorAnswer = service.chat(new ChatRequest("Need 12 tonne CRCA sheet", null, null, null));
        assertThat(auditorAnswer.rfqDraft()).isNull();
        assertThat(auditorAnswer.reply()).contains("Only buyers and administrators");
    }
}

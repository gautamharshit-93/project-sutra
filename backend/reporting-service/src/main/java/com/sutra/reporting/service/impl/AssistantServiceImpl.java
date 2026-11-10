package com.sutra.reporting.service.impl;

import com.sutra.api.internal.InternalContracts.AiCompletionRequest;
import com.sutra.api.internal.InternalContracts.AiCompletion;
import com.sutra.api.internal.InternalContracts.AiStatus;
import com.sutra.common.error.ServiceUnavailableException;
import com.sutra.reporting.exception.AiServiceException;
import com.sutra.reporting.service.internal.AiMessages;
import com.sutra.reporting.client.PlatformClient;
import com.sutra.reporting.service.DashboardService;
import com.sutra.reporting.service.AssistantService;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.api.comparison.ComparisonResult;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.reporting.dto.request.AssistantRequests.ChatRequest;
import com.sutra.reporting.dto.request.AssistantRequests.ChatTurn;
import com.sutra.reporting.dto.response.AssistantResponses.AssistantLink;
import com.sutra.reporting.dto.response.AssistantResponses.AssistantStatus;
import com.sutra.reporting.dto.response.AssistantResponses.ChatResponse;
import com.sutra.api.decision.DecisionResponses.ComparisonReportResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestSummary;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The read-only Hindi/English assistant ("SUTRA Sahayak").
 *
 * <p>For each question it builds a compact JSON snapshot of the procurement data through the normal services (so
 * the usual access checks apply), sends it to Groq with the conversation, and returns the answer. It never
 * changes data: the services it calls are queries only. Totals in the snapshot are the backend's own figures, so the
 * model quotes them rather than calculating. Questions and answers are not logged.</p>
 */
@Service
public class AssistantServiceImpl implements AssistantService {

    /** Purchase requests in the overview, newest first. */
    static final int OVERVIEW_REQUESTS = 30;
    /** Of those, how many also list their quotations. */
    static final int OVERVIEW_WITH_QUOTATIONS = 15;
    /** Earlier turns sent to the model. */
    static final int HISTORY_TURNS = 12;
    /** Earlier turns are cut to this length (a pasted RFQ need not be resent with every question). */
    static final int TURN_CHARS = 1500;
    static final int MAX_OUTPUT_TOKENS = 4096;

    private static final Pattern REQUEST_NUMBER = Pattern.compile("(?i)\\bPR-\\d{4}-\\d{1,6}\\b");
    private static final Pattern QUOTATION_ID = Pattern.compile(
            "(?iu)(?:quotation|quote|qtn|कोटेशन|कोटेशन)\\s*(?:no\\.?|number|id|#|नंबर|संख्या)?\\s*#?\\s*(\\d{1,9})");
    private static final Logger log = LoggerFactory.getLogger(AssistantServiceImpl.class);

    static final String SYSTEM_PROMPT = """
            You are "SUTRA Sahayak", the assistant inside SUTRA, a vendor quotation management system.
            Users are procurement staff in India. You help them understand purchase requests (RFQs), vendor
            quotations, extraction flags, quality grades and vendor comparisons.

            LANGUAGE
            - Reply in the same language AND script as the user's latest message:
              * Devanagari (e.g. "किन कोटेशन को रिव्यू चाहिए?") -> reply in Hindi, Devanagari script.
              * Hinglish, i.e. Hindi words in Latin letters (e.g. "sabse sasta kaun hai?") -> reply in Hinglish,
                Latin letters only. Do NOT switch to Devanagari.
              * English -> reply in English.
            - Keep record numbers, vendor names, item codes and amounts exactly as in the data.
            - Statuses in DATA are already the app's labels (e.g. "Ready for comparison", "Awarded"). Use them as
              written; in Hindi you may add a short Hindi explanation, but never invent a status code.

            RULES
            - You are READ-ONLY. You cannot create, upload, edit, approve, reject or delete anything yourself. When the
              user asks you to do something, say you cannot do it from chat and tell them exactly where in SUTRA to do
              it using the SCREENS below. Only managers (APPROVER) and administrators record awards.
            - RFQ DRAFTS: when the user's latest message contains an RFQ, indent or list of items to buy (e.g. pasted
              from an e-mail), or asks you to create an RFQ from text they wrote, set "action" to "DRAFT_RFQ". SUTRA
              then reads it into a draft card below your reply, and the user checks it and clicks "Create RFQ". Your
              reply is then one or two sentences saying the draft is ready to check; never say it was created or
              saved, and do not list the items yourself. Otherwise "action" is "NONE".
            - Use ONLY the DATA below. Never invent records, amounts, vendors or dates. If the data does not contain
              the answer, say so and suggest where to look. The overview lists only the most recent requests.
            - Every amount in DATA was calculated by the backend and is already formatted (e.g. "₹17,78,260.00").
              Copy amounts exactly as written; never recalculate, add up, convert or reformat them.
            - Prices are only comparable WITHIN one purchase request (the same RFQ). Never compare or rank quotations
              from different purchase requests; when the user does not say which request, answer per request.
            - You may say which complete quotation is lowest and repeat the advisory vendor grade, but the decision
              belongs to an authorised approver. Never say a vendor is approved unless DATA shows an approval.
            - Status meanings: Uploaded = waiting; Processing = being read; Needs review = a person must check flags;
              Ready for comparison = reviewed; Failed = could not be read (retry with another engine or enter by hand).
              Requests: Draft, Open (taking quotations), Comparing (compared, awaiting award), Awarded, Rejected.
            - Be concise: short paragraphs or bullet lists, at most about 180 words unless the user asks for detail.
            - In "links", list the purchase requests and quotations your answer is about (id from DATA), at most 6.
              Do not write a list of links inside "reply"; the app shows them as buttons.

            SCREENS (use these exact names when telling the user where to go)
            - Sidebar: Dashboard, RFQs, Vendors, Award register, Audit log.
            - RFQs > open a request > tabs: "Requested items", "Quotations" (upload a quotation for a vendor; open a
              quotation), "Comparison" (generate a comparison once quotations are ready), "Award" (a manager records
              the award or rejection, with a reason), "History".
            - Quotation page: "Re-run extraction" (choose engine), "Cross-check extraction engines", edit lines or
              "Add line", "Accept" each flag after checking it, then "Mark ready".
            """;

    private final PlatformClient platform;
    private final DashboardService dashboardService;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;

    public AssistantServiceImpl(PlatformClient platform, DashboardService dashboardService,
                                CurrentUserProvider currentUser, ObjectMapper objectMapper) {
        this.platform = platform;
        this.dashboardService = dashboardService;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    public AssistantStatus status() {
        AiStatus ai = aiStatus();
        return ai.groqConfigured()
                ? new AssistantStatus(true, ai.groqModel(), null)
                : new AssistantStatus(false, null, "The assistant needs Groq: set the GROQ_API_KEY environment "
                        + "variable for the backend and restart it.");
    }

    public ChatResponse chat(ChatRequest request) {
        if (!aiStatus().groqConfigured()) {
            throw new AiServiceException(status().reason());
        }
        Context context = buildContext(request);
        List<Map<String, Object>> messages = List.of(
                AiMessages.message("system", SYSTEM_PROMPT
                        + "\nDATA (JSON, current as of this question):\n" + toJson(context.data)),
                AiMessages.message("user", conversation(request)));
        AiCompletion answer = platform.complete(new AiCompletionRequest(messages, responseFormat(), MAX_OUTPUT_TOKENS));
        log.info("Assistant answered for user {} (model {}, input {} tokens, output {} tokens)", currentUser.username(),
                answer.model(), answer.inputTokens(), answer.outputTokens());
        ChatResponse response = readAnswer(answer.content(), context);
        return wantsRfqDraft(answer.content()) ? withRfqDraft(response, request.message()) : response;
    }

    /** True when the model marked the message as an RFQ to draft. */
    boolean wantsRfqDraft(String text) {
        try {
            return "DRAFT_RFQ".equals(objectMapper.readTree(text).path("action").asString(""));
        } catch (JacksonException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Reads the pasted RFQ into a draft card. Only buyers and administrators create RFQs; the draft is never saved
     * here - the user confirms it in the app.
     */
    ChatResponse withRfqDraft(ChatResponse response, String message) {
        if (!currentUser.hasRole(RoleName.ADMIN) && !currentUser.hasRole(RoleName.PROCUREMENT)) {
            return new ChatResponse(response.reply() + "\n\nOnly buyers and administrators can create RFQs.",
                    response.links());
        }
        try {
            return new ChatResponse(response.reply(), response.links(), platform.rfqDraftFromText(message));
        } catch (RuntimeException e) {
            log.warn("RFQ draft from chat failed: {}", e.getMessage());
            return new ChatResponse(response.reply() + "\n\nI could not read it as an RFQ (" + e.getMessage()
                    + "). Try again, or drop the RFQ file on RFQs > New RFQ.", response.links());
        }
    }

    // ---------- context ----------

    /** What the model may see, plus the records it may link to. */
    record Context(Map<String, Object> data, Map<Long, String> requests, Map<Long, String> quotations) {
    }

    Context buildContext(ChatRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<Long, String> requestLabels = new LinkedHashMap<>();
        Map<Long, String> quotationLabels = new LinkedHashMap<>();

        data.put("user", Map.of("username", currentUser.username()));
        var dashboard = dashboardService.summary();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("purchaseRequestsByStatus", labelled(dashboard.purchaseRequestsByStatus()));
        totals.put("quotationsByStatus", labelled(dashboard.quotationsByStatus()));
        totals.put("vendors", dashboard.vendors() == null ? "unknown (master data service unavailable)" : dashboard.vendors());
        data.put("totals", totals);

        List<PurchaseRequestSummary> recent = platform.searchRfqs(null, null, 0, OVERVIEW_REQUESTS).content();
        List<Map<String, Object>> overview = new ArrayList<>();
        List<Map<String, Object>> attention = new ArrayList<>();
        for (int i = 0; i < recent.size(); i++) {
            PurchaseRequestSummary pr = recent.get(i);
            requestLabels.put(pr.id(), pr.requestNumber());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", pr.id());
            row.put("requestNumber", pr.requestNumber());
            row.put("title", pr.title());
            row.put("status", label(pr.status()));
            row.put("entity", pr.entityName());
            row.put("requestedBy", pr.requestedBy());
            row.put("currency", pr.currency());
            row.put("requiredBy", pr.requiredBy());
            row.put("itemCount", pr.itemCount());
            if (i < OVERVIEW_WITH_QUOTATIONS) {
                List<QuotationSummary> quotes = platform.quotationSummaries(pr.id());
                row.put("quotations", quotes.stream().map(q -> quotationRow(q, quotationLabels)).toList());
                quotes.stream()
                        .filter(q -> q.status() == QuotationStatus.NEEDS_REVIEW || q.status() == QuotationStatus.FAILED)
                        .forEach(q -> attention.add(Map.of("quotationId", q.id(), "requestNumber", q.requestNumber(),
                                "vendor", q.vendorName(), "status", label(q.status()), "openFlags", q.openFlags())));
            }
            overview.add(row);
        }
        data.put("recentPurchaseRequests", overview);
        data.put("quotationsNeedingAttention", attention);

        for (Long prId : focusRequests(request, recent)) {
            try {
                data.computeIfAbsent("focusPurchaseRequests", k -> new ArrayList<Object>());
                @SuppressWarnings("unchecked")
                List<Object> focus = (List<Object>) data.get("focusPurchaseRequests");
                focus.add(requestDetail(prId, requestLabels, quotationLabels));
            } catch (ResourceNotFoundException e) {
                // the page's record was deleted meanwhile, or the number does not exist: the model says so
            }
        }
        for (Long quotationId : focusQuotations(request)) {
            try {
                data.computeIfAbsent("focusQuotations", k -> new ArrayList<Object>());
                @SuppressWarnings("unchecked")
                List<Object> focus = (List<Object>) data.get("focusQuotations");
                focus.add(quotationDetail(platform.quotation(quotationId), quotationLabels));
            } catch (ResourceNotFoundException e) {
                // as above
            }
        }
        if (request.purchaseRequestId() != null || request.quotationId() != null) {
            data.put("userIsViewing", request.quotationId() != null
                    ? "quotation id " + request.quotationId() : "purchase request id " + request.purchaseRequestId());
        }
        return new Context(data, requestLabels, quotationLabels);
    }

    /** The page's request plus any request numbers in the message (at most three). */
    Set<Long> focusRequests(ChatRequest request, List<PurchaseRequestSummary> recent) {
        Set<Long> ids = new LinkedHashSet<>();
        if (request.purchaseRequestId() != null) {
            ids.add(request.purchaseRequestId());
        }
        Matcher m = REQUEST_NUMBER.matcher(request.message());
        while (m.find() && ids.size() < 3) {
            String number = m.group().toUpperCase();
            recent.stream().filter(p -> p.requestNumber().equalsIgnoreCase(number)).findFirst()
                    .or(() -> platform.searchRfqs(number, null, 0, 1).content().stream()
                            .filter(p -> p.requestNumber().equalsIgnoreCase(number)).findFirst())
                    .ifPresent(p -> ids.add(p.id()));
        }
        return ids;
    }

    /** The page's quotation plus quotation ids named in the message (at most three). */
    Set<Long> focusQuotations(ChatRequest request) {
        Set<Long> ids = new LinkedHashSet<>();
        if (request.quotationId() != null) {
            ids.add(request.quotationId());
        }
        Matcher m = QUOTATION_ID.matcher(request.message());
        while (m.find() && ids.size() < 3) {
            ids.add(Long.parseLong(m.group(1)));
        }
        return ids;
    }

    private Map<String, Object> quotationRow(QuotationSummary q, Map<Long, String> labels) {
        labels.put(q.id(), q.vendorName() + " · " + q.requestNumber());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", q.id());
        row.put("vendor", q.vendorName());
        row.put("quotationNumber", q.quotationNumber());
        row.put("status", label(q.status()));
        row.put("currency", q.currency());
        row.put("grandTotal", money(q.grandTotal(), q.currency()));
        row.put("validUntil", q.validUntil());
        row.put("openFlags", q.openFlags());
        row.put("itemCount", q.itemCount());
        row.put("extractedBy", q.extractionSource());
        if (q.failureReason() != null) {
            row.put("failureReason", q.failureReason());
        }
        return row;
    }

    private Map<String, Object> requestDetail(Long id, Map<Long, String> requestLabels, Map<Long, String> quotationLabels) {
        PurchaseRequestResponse pr = platform.rfq(id);
        requestLabels.put(pr.id(), pr.requestNumber());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", pr.id());
        d.put("requestNumber", pr.requestNumber());
        d.put("title", pr.title());
        d.put("description", pr.description());
        d.put("status", label(pr.status()));
        d.put("entity", pr.entityName());
        d.put("subEntity", pr.subEntityName());
        d.put("customer", pr.customerName());
        d.put("requestedBy", pr.requestedBy());
        d.put("currency", pr.currency());
        d.put("requiredBy", pr.requiredBy());
        d.put("items", pr.items().stream().map(i -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("line", i.lineNumber());
            item.put("itemCode", i.itemCode());
            item.put("description", i.description());
            item.put("quantity", i.quantity());
            item.put("unit", i.unitOfMeasure());
            item.put("specifications", i.specifications());
            return item;
        }).toList());
        d.put("quotations", platform.quotationSummaries(id).stream()
                .map(q -> quotationRow(q, quotationLabels)).toList());
        List<ComparisonReportResponse> reports = platform.comparisons(id);
        if (!reports.isEmpty()) {
            d.put("latestComparison", comparisonSummary(reports.get(0)));
        }
        return d;
    }

    private Map<String, Object> comparisonSummary(ComparisonReportResponse report) {
        ComparisonResult result = report.result();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("version", report.reportVersion());
        c.put("generatedBy", report.generatedBy());
        c.put("generatedAt", report.generatedAt());
        c.put("lowestCompleteQuotationId", report.lowestCompleteQuotationId());
        c.put("warnings", result.warnings());
        c.put("vendors", result.quotations().stream().map(col -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("quotationId", col.quotationId());
            v.put("vendor", col.vendorName());
            v.put("priceRank", col.priceRank());
            v.put("comparableTotal", money(col.comparableTotal(), col.currency()));
            v.put("grandTotal", money(col.grandTotal(), col.currency()));
            v.put("currency", col.currency());
            v.put("differenceFromLowest", money(col.differenceFromLowest(), col.currency()));
            v.put("differencePercent", col.differencePercent());
            v.put("deliveryTerms", col.deliveryTerms());
            v.put("paymentTerms", col.paymentTerms());
            v.put("warrantyTerms", col.warrantyTerms());
            v.put("validUntil", col.validUntil());
            v.put("missingInformation", col.missingInformation());
            v.put("advisoryGrade", col.grade() == null ? null : col.grade().grade() + " (" + col.grade().score() + "/100)");
            return v;
        }).toList());
        c.put("unmatchedItems", result.unmatchedItems().size());
        return c;
    }

    private Map<String, Object> quotationDetail(QuotationResponse q, Map<Long, String> labels) {
        labels.put(q.id(), q.vendorName() + " · " + q.requestNumber());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", q.id());
        d.put("purchaseRequestId", q.purchaseRequestId());
        d.put("requestNumber", q.requestNumber());
        d.put("vendor", q.vendorName());
        d.put("quotationNumber", q.quotationNumber());
        d.put("quotationDate", q.quotationDate());
        d.put("validUntil", q.validUntil());
        d.put("status", label(q.status()));
        d.put("currency", q.currency());
        d.put("calculated", Map.of("subtotal", nz(money(q.subtotal(), q.currency())),
                "discount", nz(money(q.discountTotal(), q.currency())), "tax", nz(money(q.taxTotal(), q.currency())),
                "grandTotal", nz(money(q.grandTotal(), q.currency()))));
        Map<String, Object> printed = new LinkedHashMap<>();
        printed.put("subtotal", money(q.statedSubtotal(), q.currency()));
        printed.put("tax", money(q.statedTaxTotal(), q.currency()));
        printed.put("grandTotal", money(q.statedGrandTotal(), q.currency()));
        d.put("printedOnDocument", printed);
        d.put("deliveryTerms", q.deliveryTerms());
        d.put("paymentTerms", q.paymentTerms());
        d.put("warrantyTerms", q.warrantyTerms());
        d.put("extractedBy", q.extractionSource());
        d.put("failureReason", q.failureReason());
        d.put("files", q.files().stream().map(f -> f.fileName()).toList());
        d.put("items", q.items().stream().map(i -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("line", i.lineNumber());
            item.put("matchesRequestLine", i.requestItemLineNumber());
            item.put("itemCode", i.itemCode());
            item.put("description", i.description());
            item.put("quantity", i.quantity());
            item.put("unit", i.unitOfMeasure());
            item.put("unitPrice", money(i.unitPrice(), q.currency()));
            item.put("lineTotal", money(i.lineTotal(), q.currency()));
            item.put("printedLineTotal", money(i.statedLineTotal(), q.currency()));
            return item;
        }).toList());
        d.put("openFlags", q.flags().stream().filter(f -> !f.resolved())
                .map(f -> f.severity() + " " + f.code() + ": " + f.message()).toList());
        d.put("resolvedFlags", q.flags().stream().filter(f -> f.resolved()).count());
        return d;
    }

    /**
     * An amount ready to quote: "₹17,78,260.00" for INR (Indian digit grouping), "USD 1,234.50" otherwise.
     * Formatting here means the model copies figures instead of reformatting (and possibly changing) them.
     */
    static String money(java.math.BigDecimal value, String currency) {
        if (value == null) {
            return null;
        }
        java.math.BigDecimal v = value.setScale(2, java.math.RoundingMode.HALF_UP);
        String sign = v.signum() < 0 ? "-" : "";
        String plain = v.abs().toPlainString();
        String whole = plain.substring(0, plain.indexOf('.'));
        String fraction = plain.substring(plain.indexOf('.'));
        boolean inr = currency == null || "INR".equalsIgnoreCase(currency);
        StringBuilder grouped = new StringBuilder();
        int n = whole.length();
        for (int i = 0; i < n; i++) {
            int fromEnd = n - i;
            grouped.append(whole.charAt(i));
            boolean boundary = inr ? fromEnd >= 4 && (fromEnd - 4) % 2 == 0
                    : fromEnd > 1 && (fromEnd - 1) % 3 == 0;
            if (boundary && i < n - 1) {
                grouped.append(',');
            }
        }
        return sign + (inr ? "₹" : currency.toUpperCase() + " ") + grouped + fraction;
    }

    private static Object nz(Object value) {
        return value == null ? "not available" : value;
    }

    /** The status as the app shows it (same names as the frontend's StatusBadge), never the raw code. */
    static String label(Object status) {
        if (status == null) {
            return null;
        }
        String code = String.valueOf(status);
        return switch (code) {
            case "APPROVED" -> "Awarded";
            case "UNDER_REVIEW" -> "Comparing";
            default -> {
                String words = code.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
                yield Character.toUpperCase(words.charAt(0)) + words.substring(1);
            }
        };
    }

    private static Map<String, Long> labelled(Map<String, Long> byStatus) {
        Map<String, Long> out = new LinkedHashMap<>();
        byStatus.forEach((code, count) -> out.put(label(code), count));
        return out;
    }

    // ---------- conversation and answer ----------

    static String conversation(ChatRequest request) {
        StringBuilder text = new StringBuilder("CONVERSATION\n");
        List<ChatTurn> history = request.history() == null ? List.of() : request.history();
        history.stream().skip(Math.max(0, history.size() - HISTORY_TURNS)).forEach(turn ->
                text.append("user".equals(turn.role()) ? "User: " : "Assistant: ")
                        .append(turn.text().length() > TURN_CHARS ? turn.text().substring(0, TURN_CHARS) + " [...]" : turn.text())
                        .append("\n\n"));
        text.append("User (latest message, answer this): ").append(request.message());
        text.append("\n\n(").append(languageDirective(request.message())).append(")");
        return text.toString();
    }

    private static final Pattern DEVANAGARI = Pattern.compile("[\\u0900-\\u097F]");
    /** Common Hindi words written in Latin letters; two or more mean the message is Hinglish. */
    private static final Set<String> HINGLISH = Set.of("hai", "hain", "kya", "kaun", "kaunsa", "kaunse", "kyun", "kyon",
            "kitna", "kitne", "kitni", "sabse", "mein", "me", "ka", "ki", "ke", "ko", "se", "pe", "par", "batao", "bata",
            "dikhao", "karo", "kar", "nahi", "nahin", "abhi", "kab", "kahan", "wala", "wali", "chahiye", "konsa", "kis",
            "kise", "kisko", "kiska", "kiski", "iska", "iski", "uska", "aur", "ya", "bhi", "sasta", "mehenga", "hua", "gaya");

    /** Tells the model which language and script to answer in, so Hinglish questions get Hinglish answers. */
    static String languageDirective(String message) {
        if (DEVANAGARI.matcher(message).find()) {
            return "Reply in Hindi using Devanagari script.";
        }
        long hindiWords = java.util.Arrays.stream(message.toLowerCase(java.util.Locale.ROOT).split("[^a-z]+"))
                .filter(HINGLISH::contains).count();
        return hindiWords >= 2
                ? "The user wrote Hinglish in Latin letters. Reply in Hinglish using ONLY Latin letters - no Devanagari."
                : "Reply in English.";
    }

    static Map<String, Object> responseFormat() {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("type", "object");
        link.put("properties", Map.of(
                "type", Map.of("type", "string", "enum", List.of("PURCHASE_REQUEST", "QUOTATION")),
                "id", Map.of("type", "integer")));
        link.put("required", List.of("type", "id"));
        link.put("additionalProperties", false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "reply", Map.of("type", "string", "description", "The answer shown to the user (plain text, "
                        + "may use '- ' bullets and **bold**)"),
                "links", Map.of("type", "array", "items", link),
                "action", Map.of("type", "string", "enum", List.of("NONE", "DRAFT_RFQ"),
                        "description", "DRAFT_RFQ when the message is an RFQ / item list to turn into a draft")));
        schema.put("required", List.of("reply", "links", "action"));
        schema.put("additionalProperties", false);
        return AiMessages.jsonSchema("assistant_answer", schema);
    }

    /** Reads the model's JSON; links are kept only for records that were in the context (no invented ids). */
    ChatResponse readAnswer(String text, Context context) {
        JsonNode root;
        try {
            root = objectMapper.readTree(text);
        } catch (JacksonException e) {
            // not JSON after all: show the text itself rather than failing
            return new ChatResponse(text == null || text.isBlank() ? "Sorry, no answer was returned. Please try again."
                    : text.strip(), List.of());
        }
        String reply = root.path("reply").asString("").strip();
        if (reply.isEmpty()) {
            reply = "Sorry, no answer was returned. Please try again.";
        }
        List<AssistantLink> links = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode link : root.path("links")) {
            String type = link.path("type").asString("");
            long id = link.path("id").asLong(-1);
            String label = "PURCHASE_REQUEST".equals(type) ? context.requests().get(id)
                    : "QUOTATION".equals(type) ? context.quotations().get(id) : null;
            if (label != null && seen.add(type + id) && links.size() < 6) {
                links.add(new AssistantLink(type, id, label));
            }
        }
        return new ChatResponse(reply, links);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new AiServiceException("Could not prepare the data for the assistant", e);
        }
    }

    /** Whether Groq is configured, as the extraction service (which holds the key) reports it. */
    private AiStatus aiStatus() {
        try {
            return platform.aiStatus();
        } catch (ServiceUnavailableException e) {
            return new AiStatus(false, null, "NONE", "none", false, false);
        }
    }
}

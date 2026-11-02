package com.sutra.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.sutra.ai.DocumentProcessingClient;
import com.sutra.ai.GeminiQuotationExtractor;
import com.sutra.ai.ExtractedQuotation;
import com.sutra.ai.ExtractedQuotation.ExtractedItem;
import com.sutra.entity.Approval;
import com.sutra.entity.enums.ApprovalDecision;
import com.sutra.exception.QuotationProcessingException;
import com.sutra.repository.ApprovalRepository;
import com.sutra.repository.ComparisonReportRepository;
import com.sutra.repository.PurchaseRequestRepository;
import com.sutra.repository.AppUserRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end workflow against PostgreSQL (database sutra_test, rebuilt from the migrations):
 * master data -> purchase request -> file upload/validation/storage -> extraction (success and failure) ->
 * review and manual correction -> comparison -> authorised approval -> audit trail.
 * The Python document service is mocked so the test does not depend on it running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CleanDatabaseConfig.class)
class QuotationWorkflowIntegrationTest {

    private static final Path SAMPLES = Path.of("..", "ai", "samples");
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired MockMvc mvc;
    @Autowired ApprovalRepository approvalRepository;
    @Autowired ComparisonReportRepository comparisonReportRepository;
    @Autowired PurchaseRequestRepository purchaseRequestRepository;
    @Autowired AppUserRepository userRepository;

    @MockitoBean DocumentProcessingClient documentClient;
    /**
     * The real Gemini engine with its calls stubbed: a spy keeps {@code source()}, which the engine registry reads
     * at start-up. The test profile runs Gemini only (see application-test.yml).
     */
    @MockitoSpyBean GeminiQuotationExtractor extractor;

    private String adminToken;

    @BeforeEach
    void login() throws Exception {
        adminToken = login("admin", "Admin@123");
        // The fuzzy matcher is "down": the backend must fall back to its local similarity matcher.
        when(documentClient.match(any(), any())).thenThrow(new QuotationProcessingException("matcher offline"));
        doReturn(true).when(extractor).isAvailable();
    }

    // ------------------------------------------------------------------ the full workflow

    @Test
    void fullQuotationWorkflow() throws Exception {
        String suffix = Long.toString(System.nanoTime() % 1_000_000);
        // --- users and roles
        createUser("buyer" + suffix, "PROCUREMENT");
        createUser("manager" + suffix, "APPROVER");
        createUser("viewer" + suffix, "VIEWER");
        String buyer = login("buyer" + suffix, "Passw0rd!");
        String manager = login("manager" + suffix, "Passw0rd!");
        String viewer = login("viewer" + suffix, "Passw0rd!");

        // --- master data (admin only)
        long entityId = id(call(adminToken, post("/api/entities"), "{\"code\":\"HQ" + suffix + "\",\"name\":\"Head Office\"}", 201));
        long subId = id(call(adminToken, post("/api/entities/" + entityId + "/sub-entities"),
                "{\"code\":\"IT\",\"name\":\"IT Department\"}", 201));
        long alpha = id(call(adminToken, post("/api/vendors"), "{\"code\":\"ALPHA" + suffix + "\",\"name\":\"Alpha Computers\"}", 201));
        long beta = id(call(adminToken, post("/api/vendors"), "{\"code\":\"BETA" + suffix + "\",\"name\":\"Beta Systems\"}", 201));
        call(viewer, post("/api/vendors"), "{\"code\":\"X" + suffix + "\",\"name\":\"Nope\"}", 403);
        call(buyer, post("/api/entities"), "{\"code\":\"Y" + suffix + "\",\"name\":\"Nope\"}", 403);

        // --- purchase request with items
        long prId = id(call(buyer, post("/api/purchase-requests"), """
                {"title":"Laptops for new joiners","entityId":%d,"subEntityId":%d,"currency":"INR"}
                """.formatted(entityId, subId), 201));
        call(buyer, post("/api/purchase-requests/" + prId + "/items"),
                "{\"description\":\"Laptop Dell Latitude 5440\",\"quantity\":10,\"unitOfMeasure\":\"Nos\",\"specifications\":\"i5, 16GB\"}", 201);
        call(buyer, post("/api/purchase-requests/" + prId + "/items"),
                "{\"description\":\"24 inch LED Monitor\",\"quantity\":10,\"unitOfMeasure\":\"Nos\"}", 201);
        call(buyer, patch("/api/purchase-requests/" + prId + "/status"), "{\"status\":\"OPEN\"}", 200);
        // the viewer can read but not change
        call(viewer, get("/api/purchase-requests/" + prId), null, 200);
        call(viewer, post("/api/purchase-requests/" + prId + "/items"),
                "{\"description\":\"x\",\"quantity\":1,\"unitOfMeasure\":\"Nos\"}", 403);

        // --- invalid files are rejected with clear messages
        MvcResult txt = upload(buyer, prId, alpha, new MockMultipartFile("files", "notes.txt", "text/plain", "hi".getBytes()), 415);
        assertThat(json(txt, "$.message", String.class)).contains("PDF, Excel");
        MvcResult fakePdf = upload(buyer, prId, alpha,
                new MockMultipartFile("files", "fake.pdf", "application/pdf", "MZ-not-a-pdf".getBytes()), 400);
        assertThat(json(fakePdf, "$.code", String.class)).isEqualTo("INVALID_QUOTATION_FILE");

        // --- vendor A: real sample Excel file, extraction succeeds
        byte[] alphaBytes = Files.readAllBytes(SAMPLES.resolve("vendor_a_alpha_computers.xlsx"));
        doReturn(extraction(
                "Alpha Computers", List.of(
                        item("Dell Latitude 5440 Laptop i5 16GB 512GB SSD", "10", "68500", "18", "808300"),
                        item("Dell 24 inch Full HD LED Monitor P2422H", "10", "11200", "18", "132160")),
                "940460")).when(extractor)
                .extract(argThat(in -> in != null && in.fileName().equals("vendor_a_alpha_computers.xlsx")));
        long quoteA = id(upload(buyer, prId, alpha, new MockMultipartFile("files", "vendor_a_alpha_computers.xlsx", XLSX, alphaBytes), 202));
        MvcResult a = awaitProcessed(buyer, quoteA);
        assertThat(json(a, "$.status", String.class)).isEqualTo("READY_FOR_COMPARISON");
        assertThat(json(a, "$.extractionSource", String.class)).isEqualTo("GEMINI");
        assertThat(new BigDecimal(json(a, "$.grandTotal", Object.class).toString())).isEqualByComparingTo("940460.00");
        assertThat(json(a, "$.items[0].requestItemLineNumber", Integer.class)).isEqualTo(1);
        assertThat(json(a, "$.files[0].sizeBytes", Integer.class)).isEqualTo(alphaBytes.length);

        // the original file is stored and retrievable byte-for-byte
        long fileA = ((Number) JsonPath.read(a.getResponse().getContentAsString(), "$.files[0].id")).longValue();
        MvcResult download = mvc.perform(get("/api/quotations/" + quoteA + "/files/" + fileA).header(HttpHeaders.AUTHORIZATION, bearer(viewer)))
                .andReturn();
        assertThat(download.getResponse().getStatus()).isEqualTo(200);
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(alphaBytes);

        // --- vendor B: extraction fails, the upload is preserved, data is entered manually
        byte[] betaBytes = Files.readAllBytes(SAMPLES.resolve("vendor_c_gamma_it.pdf"));
        doThrow(new QuotationProcessingException("Gemini is temporarily unavailable.")).when(extractor)
                .extract(argThat(in -> in != null && in.fileName().equals("beta.pdf")));
        long quoteB = id(upload(buyer, prId, beta, new MockMultipartFile("files", "beta.pdf", "application/pdf", betaBytes), 202));
        MvcResult b = awaitProcessed(buyer, quoteB);
        assertThat(json(b, "$.status", String.class)).isEqualTo("FAILED");
        assertThat(json(b, "$.failureReason", String.class)).contains("temporarily unavailable");
        assertThat(json(b, "$.files[0].fileName", String.class)).isEqualTo("beta.pdf");

        long[] requestItems = requestItemIds(buyer, prId);
        call(buyer, post("/api/quotations/" + quoteB + "/items"), """
                {"requestItemId":%d,"description":"Laptop Dell Latitude 5440","quantity":10,"unitOfMeasure":"Nos",
                 "unitPrice":66900,"taxPercent":18}""".formatted(requestItems[0]), 201);
        MvcResult manual = call(buyer, post("/api/quotations/" + quoteB + "/items"), """
                {"requestItemId":%d,"description":"LG 24 inch monitor","quantity":10,"unitOfMeasure":"Nos",
                 "unitPrice":10450,"taxPercent":18}""".formatted(requestItems[1]), 201);
        assertThat(json(manual, "$.extractionSource", String.class)).isEqualTo("MANUAL");
        // header details are missing, so the reviewer fills them in, then confirms
        call(buyer, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/quotations/" + quoteB), """
                {"vendorId":%d,"quotationNumber":"BSS-7781","validUntil":"%s","currency":"INR",
                 "paymentTerms":"50%% advance","deliveryTerms":"3 weeks"}""".formatted(beta, LocalDate.now().plusDays(20)), 200);
        MvcResult readyB = call(buyer, post("/api/quotations/" + quoteB + "/mark-ready"), null, 200);
        assertThat(json(readyB, "$.status", String.class)).isEqualTo("READY_FOR_COMPARISON");
        assertThat(new BigDecimal(json(readyB, "$.grandTotal", Object.class).toString())).isEqualByComparingTo("912730.00");

        // --- nothing has been approved automatically
        assertThat(approvalRepository.findByPurchaseRequestIdOrderByDecidedAtDesc(prId)).isEmpty();

        // --- comparison
        MvcResult comparison = call(buyer, post("/api/purchase-requests/" + prId + "/comparisons"), null, 201);
        long reportId = id(comparison);
        assertThat(json(comparison, "$.reportVersion", Integer.class)).isEqualTo(1);
        assertThat(json(comparison, "$.lowestCompleteQuotationId", Integer.class)).isEqualTo((int) quoteB);
        assertThat(json(comparison, "$.result.rows.length()", Integer.class)).isEqualTo(2);
        assertThat(json(comparison, "$.result.disclaimer", String.class)).contains("never selects or approves");
        assertThat(json(call(buyer, get("/api/purchase-requests/" + prId), null, 200), "$.status", String.class))
                .isEqualTo("UNDER_REVIEW");
        assertThat(approvalRepository.findByPurchaseRequestIdOrderByDecidedAtDesc(prId))
                .as("generating a comparison never approves").isEmpty();

        // --- approval: only an approver may decide
        String decision = "{\"comparisonReportId\":%d,\"decision\":\"APPROVED\",\"selectedQuotationId\":%d}";
        call(buyer, post("/api/purchase-requests/" + prId + "/approvals"), decision.formatted(reportId, quoteB), 403);
        call(viewer, post("/api/purchase-requests/" + prId + "/approvals"), decision.formatted(reportId, quoteB), 403);
        MvcResult approved = call(manager, post("/api/purchase-requests/" + prId + "/approvals"),
                decision.formatted(reportId, quoteB), 201);
        assertThat(json(approved, "$.decidedBy", String.class)).isEqualTo("manager" + suffix);
        assertThat(json(approved, "$.selectedVendorName", String.class)).isEqualTo("Beta Systems");
        assertThat(json(call(viewer, get("/api/purchase-requests/" + prId), null, 200), "$.status", String.class))
                .isEqualTo("APPROVED");

        // the request is now closed
        call(buyer, post("/api/purchase-requests/" + prId + "/items"),
                "{\"description\":\"x\",\"quantity\":1,\"unitOfMeasure\":\"Nos\"}", 422);

        // --- audit trail: approvers can read it, viewers cannot
        call(viewer, get("/api/audit-logs"), null, 403);
        MvcResult audit = call(manager, get("/api/audit-logs?entityType=QUOTATION&entityId=" + quoteB), null, 200);
        List<String> actions = JsonPath.read(audit.getResponse().getContentAsString(), "$.content[*].action");
        assertThat(actions).contains("UPLOADED", "EXTRACTION_FAILED", "ITEM_ADDED", "HEADER_CORRECTED", "MARKED_READY");
        MvcResult approvalAudit = call(adminToken, get("/api/audit-logs?entityType=APPROVAL"), null, 200);
        assertThat(approvalAudit.getResponse().getContentAsString()).contains("manager" + suffix);

        // --- per-record history: any signed-in user who can see the record sees its timeline
        MvcResult prHistory = call(viewer, get("/api/purchase-requests/" + prId + "/history"), null, 200);
        List<String> prActions = JsonPath.read(prHistory.getResponse().getContentAsString(), "$.content[*].action");
        assertThat(prActions).contains("CREATED", "UPLOADED", "MARKED_READY", "GENERATED", "APPROVED", "STATUS_CHANGED");
        List<String> types = JsonPath.read(prHistory.getResponse().getContentAsString(), "$.content[*].entityType");
        assertThat(types).contains("PURCHASE_REQUEST", "QUOTATION", "COMPARISON_REPORT", "APPROVAL");
        MvcResult quoteHistory = call(viewer, get("/api/quotations/" + quoteB + "/history"), null, 200);
        assertThat(JsonPath.<List<String>>read(quoteHistory.getResponse().getContentAsString(), "$.content[*].entityType"))
                .containsOnly("QUOTATION");
        call(viewer, get("/api/quotations/999999/history"), null, 404);

        // --- PostgreSQL CHECK constraint: a rejection must carry a reason, even if a bug bypassed the API
        Approval invalid = new Approval();
        invalid.setDecision(ApprovalDecision.REJECTED);
        invalid.setPurchaseRequest(purchaseRequestRepository.getReferenceById(prId));
        invalid.setComparisonReport(comparisonReportRepository.getReferenceById(reportId));
        invalid.setDecidedBy(userRepository.findByUsernameIgnoreCase("admin").orElseThrow());
        assertThatThrownBy(() -> approvalRepository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ smaller checks

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        assertThat(mvc.perform(get("/api/vendors")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/vendors").header(HttpHeaders.AUTHORIZATION, "Bearer forged.token.value"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void validationErrorsUseTheStandardErrorShape() throws Exception {
        MvcResult result = call(adminToken, post("/api/vendors"), "{\"code\":\"!\",\"name\":\"\"}", 400);
        assertThat(json(result, "$.code", String.class)).isEqualTo("VALIDATION_FAILED");
        List<String> fields = JsonPath.read(result.getResponse().getContentAsString(), "$.fieldErrors[*].field");
        assertThat(fields).contains("code", "name");
    }

    @Test
    void notFoundUsesCustomExceptionCodes() throws Exception {
        assertThat(json(call(adminToken, get("/api/vendors/999999"), null, 404), "$.code", String.class))
                .isEqualTo("VENDOR_NOT_FOUND");
        assertThat(json(call(adminToken, get("/api/quotations/999999"), null, 404), "$.code", String.class))
                .isEqualTo("QUOTATION_NOT_FOUND");
        assertThat(json(call(adminToken, get("/api/entities/999999"), null, 404), "$.code", String.class))
                .isEqualTo("ENTITY_NOT_FOUND");
    }

    @Test
    void entityRelationshipsAreEnforced() throws Exception {
        String code = "REL" + (System.nanoTime() % 100000);
        long entityId = id(call(adminToken, post("/api/entities"), "{\"code\":\"" + code + "\",\"name\":\"Parent\"}", 201));
        call(adminToken, post("/api/entities/" + entityId + "/sub-entities"), "{\"code\":\"S1\",\"name\":\"Child\"}", 201);
        call(adminToken, post("/api/entities/" + entityId + "/sub-entities"), "{\"code\":\"s1\",\"name\":\"Dup\"}", 409);
        call(adminToken, delete("/api/entities/" + entityId), null, 422);
        call(adminToken, post("/api/customers"), "{\"code\":\"C" + code + "\",\"name\":\"Cust\",\"entityId\":999999}", 404);
    }

    @Test
    void errorsCarryTheRequestIdThatIsAlsoReturnedAsAHeader() throws Exception {
        MvcResult result = mvc.perform(get("/api/vendors/999999").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                .header("X-Request-Id", "trace-12345678")).andReturn();
        assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo("trace-12345678");
        assertThat(json(result, "$.requestId", String.class)).isEqualTo("trace-12345678");
        MvcResult generated = mvc.perform(get("/api/vendors")).andReturn();
        assertThat(json(generated, "$.requestId", String.class)).hasSize(16)
                .isEqualTo(generated.getResponse().getHeader("X-Request-Id"));
    }

    @Test
    void repeatedFailedLoginsLockTheAccount() throws Exception {
        String username = "locked" + (System.nanoTime() % 100000);
        createUser(username, "VIEWER");
        String wrong = "{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}";
        for (int i = 0; i < 5; i++) {
            assertThat(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrong))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult blocked = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"Passw0rd!\"}")).andReturn();
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(json(blocked, "$.code", String.class)).isEqualTo("ACCOUNT_LOCKED");
        MvcResult audit = call(adminToken, get("/api/audit-logs?entityType=USER&performedBy=" + username), null, 200);
        assertThat(JsonPath.<List<String>>read(audit.getResponse().getContentAsString(), "$.content[*].action"))
                .contains("LOGIN_FAILED", "ACCOUNT_LOCKED", "LOGIN_BLOCKED");
    }

    // ------------------------------------------------------------------ helpers

    private static ExtractedQuotation extraction(String vendor, List<ExtractedItem> items, String grand) {
        return new ExtractedQuotation(Optional.of(vendor), Optional.of("AC/Q/118"), Optional.of(LocalDate.now().toString()),
                Optional.of(LocalDate.now().plusDays(30).toString()), Optional.of("INR"), items, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(new BigDecimal(grand)), Optional.of("2 weeks"),
                Optional.of("30 days"), Optional.of("3 years"), Optional.empty(), 0.8, List.of());
    }

    private static ExtractedItem item(String d, String qty, String price, String tax, String total) {
        return new ExtractedItem(Optional.empty(), d, Optional.of(new BigDecimal(qty)), Optional.of("Nos"), Optional.of(new BigDecimal(price)),
                Optional.empty(), Optional.empty(), Optional.of(new BigDecimal(tax)), Optional.empty(),
                Optional.of(new BigDecimal(total)), 0.9, Optional.empty());
    }

    private void createUser(String username, String role) throws Exception {
        call(adminToken, post("/api/users"), """
                {"username":"%s","email":"%s@test.local","fullName":"%s","password":"Passw0rd!","roles":["%s"]}
                """.formatted(username, username, username, role), 201);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).as("login " + username).isEqualTo(200);
        return json(result, "$.token", String.class);
    }

    private MvcResult call(String token, MockHttpServletRequestBuilder request, String body, int expectedStatus)
            throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " -> "
                        + result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return result;
    }

    private MvcResult upload(String token, long prId, long vendorId, MockMultipartFile file, int expectedStatus)
            throws Exception {
        MvcResult result = mvc.perform(multipart("/api/purchase-requests/" + prId + "/quotations")
                .file(file).param("vendorId", Long.toString(vendorId))
                .header(HttpHeaders.AUTHORIZATION, bearer(token))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return result;
    }

    /** Extraction runs asynchronously after the upload commits; poll until it settles. */
    private MvcResult awaitProcessed(String token, long quotationId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            MvcResult result = call(token, get("/api/quotations/" + quotationId), null, 200);
            String status = json(result, "$.status", String.class);
            if (!status.equals("UPLOADED") && !status.equals("PROCESSING")) {
                return result;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Quotation " + quotationId + " still " + status);
            }
            Thread.sleep(150);
        }
    }

    private long[] requestItemIds(String token, long prId) throws Exception {
        List<Integer> ids = JsonPath.read(call(token, get("/api/purchase-requests/" + prId), null, 200)
                .getResponse().getContentAsString(), "$.items[*].id");
        return ids.stream().mapToLong(Integer::longValue).toArray();
    }

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
    }

    private static <T> T json(MvcResult result, String path, Class<T> type) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
        return type.cast(value);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}

package com.sutra.procurement.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.sutra.common.security.JwtCodec;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.procurement.ai.DocumentProcessingClient;
import com.sutra.procurement.ai.ExtractedQuotation;
import com.sutra.procurement.client.MasterDataClient;
import com.sutra.procurement.exception.QuotationProcessingException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The procurement service against PostgreSQL (schema "procurement" in sutra_test, rebuilt from /database):
 * a buyer publishes an RFQ, two vendors log in through the vendor portal, one sends a real Excel quotation that is
 * stored and read (rule-based engine), the buyer compares and a manager awards it, and each vendor sees only its own
 * outcome. Master data and the Python document service are mocked; tokens are signed as the auth service would.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CleanDatabaseConfig.class)
class ProcurementWorkflowIntegrationTest {

    private static final Path DATA = Path.of("..", "..", "ai", "test-data");
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired MockMvc mvc;
    @Autowired JwtCodec jwt;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MasterDataClient masterData;
    @MockitoBean DocumentProcessingClient documentClient;

    private String buyer;
    private String manager;
    private String shakti;
    private String pioneer;

    @BeforeEach
    void setUp() {
        buyer = token(10L, "asha.buyer", RoleName.PROCUREMENT, null);
        manager = token(20L, "ravi.manager", RoleName.APPROVER, null);
        shakti = token(30L, "shakti.sales", RoleName.VENDOR, 7L);
        pioneer = token(31L, "pioneer.sales", RoleName.VENDOR, 8L);
        when(masterData.entity(1L)).thenReturn(new MasterDataClient.EntitySnapshot(1L, "AXM", "Axiom Auto Components", true));
        when(masterData.activeVendor(7L)).thenReturn(new MasterDataClient.VendorSnapshot(7L, "SHAKTI", "Shakti Metals", true));
        when(masterData.activeVendor(8L)).thenReturn(new MasterDataClient.VendorSnapshot(8L, "PIONEER", "Pioneer Industrial", true));
        // The fuzzy matcher is "down": the service falls back to its local matcher (item codes match exactly anyway)
        when(documentClient.match(any(), any())).thenThrow(new QuotationProcessingException("matcher offline"));
        when(documentClient.extractWithRules(any())).thenReturn(reading());
    }

    private String token(Long id, String username, RoleName role, Long vendorId) {
        return jwt.issue(new SutraUserPrincipal(id, username, null, true, Set.of(role), vendorId, 0));
    }

    /** What the rule-based reader returns for Shakti's material list (two of the lines, as printed). */
    private static ExtractedQuotation reading() {
        return new ExtractedQuotation(Optional.of("Shakti Metals & Components"), Optional.of("SMC/Q/26-27/041"),
                Optional.of("2026-11-03"), Optional.of("2026-12-03"), Optional.of("INR"),
                List.of(line("MT-101", "Cold-rolled steel sheet (CRCA), 1.2 mm", "12", "62500", "885000.00"),
                        line("MT-104", "Deep-groove ball bearing 6204-2RS", "2000", "54", "127440.00")),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(new BigDecimal("1012440.00")),
                Optional.of("Within 15 days"), Optional.of("30 days from invoice"), Optional.of("Mill test certificate"),
                Optional.empty(), 0.9, List.of());
    }

    private static ExtractedQuotation.ExtractedItem line(String code, String desc, String qty, String price, String total) {
        return new ExtractedQuotation.ExtractedItem(Optional.of(code), desc, Optional.of(new BigDecimal(qty)),
                Optional.of("Nos"), Optional.of(new BigDecimal(price)), Optional.empty(), Optional.empty(),
                Optional.of(new BigDecimal("18")), Optional.empty(), Optional.of(new BigDecimal(total)), 0.95, Optional.empty());
    }

    @Test
    void rfqToAwardThroughTheVendorPortal() throws Exception {
        // --- the buyer publishes the RFQ (the number comes from the schema's own sequence)
        MvcResult created = call(buyer, post("/api/purchase-requests"),
                "{\"title\":\"Production materials\",\"entityId\":1,\"currency\":\"INR\"}", 201);
        long prId = id(created);
        assertThat(json(created, "$.requestNumber", String.class)).matches("PR-\\d{4}-\\d{5}");
        assertThat(json(created, "$.entityName", String.class)).isEqualTo("Axiom Auto Components");
        call(buyer, post("/api/purchase-requests/" + prId + "/items"),
                "{\"itemCode\":\"MT-101\",\"description\":\"CRCA sheet 1.2 mm\",\"quantity\":12,\"unitOfMeasure\":\"Tonne\"}", 201);
        call(buyer, post("/api/purchase-requests/" + prId + "/items"),
                "{\"itemCode\":\"MT-104\",\"description\":\"Bearing 6204-2RS\",\"quantity\":2000,\"unitOfMeasure\":\"Nos\"}", 201);
        call(buyer, patch("/api/purchase-requests/" + prId + "/status"), "{\"status\":\"OPEN\"}", 200);

        // --- vendors see it in their portal, and cannot use the company API
        MvcResult rfqs = call(shakti, get("/api/vendor-portal/rfqs"), null, 200);
        assertThat(JsonPath.<List<Integer>>read(rfqs.getResponse().getContentAsString(), "$.content[*].id")).contains((int) prId);
        call(shakti, get("/api/purchase-requests/" + prId), null, 403);

        // --- Shakti sends its real Excel material list; it is stored byte-for-byte and read in the background
        byte[] xlsx = Files.readAllBytes(DATA.resolve("Vendor_1_Shakti_Metals_Material_List.xlsx"));
        MvcResult sent = mvc.perform(multipart("/api/vendor-portal/rfqs/" + prId + "/quotations")
                .file(new MockMultipartFile("files", "Vendor_1_Shakti_Metals_Material_List.xlsx", XLSX, xlsx))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + shakti)).andReturn();
        assertThat(sent.getResponse().getStatus()).as(sent.getResponse().getContentAsString()).isEqualTo(202);
        long quotationId = id(sent);
        MvcResult read = awaitProcessed(quotationId);
        assertThat(json(read, "$.extractionSource", String.class)).isEqualTo("RULES");
        assertThat(json(read, "$.submittedByVendor", Boolean.class)).isTrue();
        assertThat(new BigDecimal(json(read, "$.grandTotal", Object.class).toString())).isEqualByComparingTo("1012440.00");
        long fileId = ((Number) JsonPath.read(read.getResponse().getContentAsString(), "$.files[0].id")).longValue();
        MvcResult file = call(shakti, get("/api/vendor-portal/quotations/" + quotationId + "/files/" + fileId), null, 200);
        assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(xlsx);

        // --- another vendor cannot see or touch it
        call(pioneer, get("/api/vendor-portal/quotations/" + quotationId + "/files/" + fileId), null, 404);
        MvcResult pioneerOwn = call(pioneer, get("/api/vendor-portal/quotations"), null, 200);
        assertThat(pioneerOwn.getResponse().getContentAsString()).isEqualTo("[]");

        // --- buyer confirms and compares; the RFQ closes to new quotations
        if (json(read, "$.status", String.class).equals("NEEDS_REVIEW")) {
            call(buyer, post("/api/quotations/" + quotationId + "/mark-ready"), null, 200);
        }
        MvcResult report = call(buyer, post("/api/purchase-requests/" + prId + "/comparisons"), null, 201);
        mvc.perform(multipart("/api/vendor-portal/rfqs/" + prId + "/quotations")
                        .file(new MockMultipartFile("files", "late.xlsx", XLSX, xlsx))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pioneer))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(422));

        // --- the manager awards; the vendor learns it won
        call(buyer, post("/api/purchase-requests/" + prId + "/approvals"),
                "{\"comparisonReportId\":" + id(report) + ",\"decision\":\"APPROVED\",\"selectedQuotationId\":" + quotationId + "}", 403);
        call(manager, post("/api/purchase-requests/" + prId + "/approvals"),
                "{\"comparisonReportId\":" + id(report) + ",\"decision\":\"APPROVED\",\"selectedQuotationId\":" + quotationId + "}", 201);
        MvcResult mine = call(shakti, get("/api/vendor-portal/quotations"), null, 200);
        assertThat(json(mine, "$[0].outcome", String.class)).isEqualTo("AWARDED");

        // --- every step went to the outbox, grouped under the RFQ, for the audit service
        List<Map<String, Object>> events = jdbc.queryForList(
                "select action, performed_by from audit_outbox where purchase_request_id = ? order by id", prId);
        assertThat(events).extracting(e -> e.get("action"))
                .contains("CREATED", "ITEM_ADDED", "STATUS_CHANGED", "UPLOADED", "EXTRACTED", "GENERATED", "APPROVED");
        assertThat(events).anyMatch(e -> "UPLOADED".equals(e.get("action")) && "shakti.sales".equals(e.get("performed_by")));
        assertThat(events).anyMatch(e -> "EXTRACTED".equals(e.get("action")) && "system".equals(e.get("performed_by")));
    }

    @Test
    void usageIsReportedToOtherServicesOnlyWithAServiceToken() throws Exception {
        call(buyer, post("/api/purchase-requests"), "{\"title\":\"Usage check\",\"entityId\":1,\"currency\":\"INR\"}", 201);
        String service = jwt.issueService("masterdata-service");

        MvcResult usage = call(service, get("/internal/usage/entities/1"), null, 200);
        assertThat(json(usage, "$.purchaseRequests", Integer.class)).isPositive();
        call(buyer, get("/internal/usage/entities/1"), null, 403);
    }

    @Test
    void unauthenticatedAndUnknownTokensAreRejected() throws Exception {
        assertThat(mvc.perform(get("/api/purchase-requests")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/purchase-requests").header(HttpHeaders.AUTHORIZATION, "Bearer forged"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult call(String token, MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI() + " -> "
                        + result.getResponse().getContentAsString())
                .isEqualTo(expected);
        return result;
    }

    /** Extraction runs asynchronously after the upload commits; poll until it settles. */
    private MvcResult awaitProcessed(long quotationId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            MvcResult result = call(buyer, get("/api/quotations/" + quotationId), null, 200);
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

    private static long id(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static <T> T json(MvcResult result, String path, Class<T> type) throws Exception {
        Object value = JsonPath.read(result.getResponse().getContentAsString(), path);
        return type.cast(value instanceof Number n && type == Integer.class ? Integer.valueOf(n.intValue()) : value);
    }
}

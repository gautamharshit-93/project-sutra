package com.sutra.quotation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.sutra.api.document.DocumentContracts.StoredDocument;
import com.sutra.api.enums.ExtractionSource;
import com.sutra.api.enums.FileFormat;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.extraction.ExtractionChoice;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.RequestItemResponse;
import com.sutra.common.security.JwtCodec;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.quotation.client.DecisionClient;
import com.sutra.quotation.client.DocumentStoreClient;
import com.sutra.quotation.client.MasterDataClient;
import com.sutra.quotation.client.RfqClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
 * The quotation service against PostgreSQL (schema quotation_it in sutra_test, built from this service's
 * migrations, both V1 and the split's V2): a vendor submits through the portal, reading is queued after the upload
 * commits, the extraction service claims the quotation and hands back what it read, and the buyer sees backend
 * totals. The RFQ, document, extraction, comparison and approval services are mocked at their clients.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CleanDatabaseConfig.class)
class QuotationIntegrationTest {

    private static final long RFQ = 101L;
    private static final long CRCA = 1001L;
    private static final long BEARING = 1002L;

    @Autowired MockMvc mvc;
    @Autowired JwtCodec jwt;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RfqClient rfqs;
    @MockitoBean MasterDataClient masterData;
    @MockitoBean DocumentStoreClient documents;
    @MockitoBean DecisionClient decisions;

    private String buyer;
    private String shakti;
    private String pioneer;
    private String service;

    @BeforeEach
    void setUp() {
        buyer = jwt.issue(new SutraUserPrincipal(10L, "asha.buyer", null, true, Set.of(RoleName.PROCUREMENT), null, 0));
        shakti = jwt.issue(new SutraUserPrincipal(30L, "shakti.sales", null, true, Set.of(RoleName.VENDOR), 7L, 0));
        pioneer = jwt.issue(new SutraUserPrincipal(31L, "pioneer.sales", null, true, Set.of(RoleName.VENDOR), 8L, 0));
        service = jwt.issueService("extraction-service");
        PurchaseRequestResponse rfq = new PurchaseRequestResponse(RFQ, "PR-2026-00101", "Production materials", null,
                PurchaseRequestStatus.OPEN, 1L, "Axiom Auto Components", null, null, null, null, 10L, "asha.buyer", "INR",
                null, true, List.of(
                        new RequestItemResponse(CRCA, 1, "MT-101", "CRCA sheet 1.2 mm", new BigDecimal("12"), "Tonne", null),
                        new RequestItemResponse(BEARING, 2, "MT-104", "Bearing 6204-2RS", new BigDecimal("2000"), "Nos", null)),
                Instant.now(), Instant.now(), null);
        when(rfqs.get(RFQ)).thenReturn(rfq);
        when(rfqs.getMany(any())).thenReturn(java.util.Map.of(RFQ, rfq));
        when(masterData.activeVendor(7L)).thenReturn(new MasterDataClient.VendorSnapshot(7L, "SHAKTI", "Shakti Metals", true));
        when(documents.store(any(), anyLong(), any())).thenReturn(List.of(new StoredDocument(501L, "shakti.xlsx",
                FileFormat.XLSX.mimeType(), FileFormat.XLSX, 4L, "a".repeat(64), Instant.now())));
        when(decisions.comparisonReferences(anyLong())).thenReturn(0L);
    }

    @Test
    void aVendorQuotationIsStoredReadAndRecalculated() throws Exception {
        // --- the vendor submits through its portal; the bytes go to the document service
        MvcResult submitted = mvc.perform(multipart("/api/vendor-portal/rfqs/" + RFQ + "/quotations")
                        .file(new MockMultipartFile("files", "shakti.xlsx", "application/octet-stream", "xlsx".getBytes()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + shakti))
                .andReturn();
        assertThat(submitted.getResponse().getStatus()).as(submitted.getResponse().getContentAsString()).isEqualTo(202);
        long id = ((Number) JsonPath.read(body(submitted), "$.id")).longValue();
        verify(decisions).read(eq(id), eq(ExtractionChoice.AUTO)); // queued once the upload committed
        verify(documents).attach(List.of(501L), id);
        assertThat(jdbc.queryForObject("select document_id from quotation_file where quotation_id = ?", Long.class, id))
                .isEqualTo(501L);

        // --- the extraction service claims it and hands back what it read (lines matched to the RFQ)
        MvcResult claimed = call(service, post("/internal/quotations/" + id + "/extraction/claim"), null, 200);
        assertThat((Integer) JsonPath.read(body(claimed), "$.documents[0].documentId")).isEqualTo(501);
        assertThat((Integer) JsonPath.read(body(claimed), "$.requestedLines.length()")).isEqualTo(2);
        call(service, post("/internal/quotations/" + id + "/extraction/claim"), null, 409); // already being read
        call(service, post("/internal/quotations/" + id + "/extraction/result"), """
                {"data": {"vendorName": "Shakti Metals & Components", "quotationNumber": "SMC/Q/26-27/041",
                          "quotationDate": "2026-11-03", "validUntil": "2026-12-03", "currency": "INR",
                          "items": [
                            {"itemCode": "MT-101", "description": "Cold-rolled steel sheet (CRCA), 1.2 mm", "quantity": 12,
                             "unitOfMeasure": "Tonne", "unitPrice": 62500, "taxPercent": 18, "lineTotal": 885000.00,
                             "confidence": 0.95},
                            {"itemCode": "MT-104", "description": "Deep-groove ball bearing 6204-2RS", "quantity": 2000,
                             "unitOfMeasure": "Nos", "unitPrice": 54, "taxPercent": 18, "lineTotal": 127440.00,
                             "confidence": 0.95}],
                          "grandTotal": 1012440.00, "deliveryTerms": "Within 15 days", "paymentTerms": "30 days from invoice",
                          "warrantyTerms": "Mill test certificate", "overallConfidence": 0.9, "warnings": []},
                 "source": "RULES", "model": "rules v1",
                 "matches": [{"index": 0, "requestItemId": 1001, "score": 1.0}, {"index": 1, "requestItemId": 1002, "score": 1.0}],
                 "failureReason": null}""", 204);

        // --- the buyer sees backend-calculated totals and the matched RFQ lines
        MvcResult read = call(buyer, get("/api/quotations/" + id), null, 200);
        assertThat((String) JsonPath.read(body(read), "$.status")).isEqualTo("READY_FOR_COMPARISON");
        assertThat((String) JsonPath.read(body(read), "$.extractionSource")).isEqualTo(ExtractionSource.RULES.name());
        assertThat(new BigDecimal(JsonPath.read(body(read), "$.grandTotal").toString())).isEqualByComparingTo("1012440.00");
        assertThat((Integer) JsonPath.read(body(read), "$.items[1].requestItemLineNumber")).isEqualTo(2);

        // --- the other vendor cannot see it; the vendor sees it with its stage and total
        call(pioneer, get("/api/vendor-portal/quotations/" + id + "/files/1"), null, 404);
        MvcResult mine = call(shakti, get("/api/vendor-portal/quotations"), null, 200);
        assertThat((String) JsonPath.read(body(mine), "$[0].stage")).isEqualTo("ACCEPTED_FOR_COMPARISON");

        // --- rows, the one-per-vendor key and the audit trail are in the database
        assertThat(jdbc.queryForObject("select count(*) from quotation_item where quotation_id = ?", Long.class, id)).isEqualTo(2L);
        assertThat(jdbc.queryForList("select action from audit_outbox where entity_id = ? order by id", String.class, id))
                .containsSubsequence("UPLOADED", "EXTRACTED");
        MvcResult second = mvc.perform(multipart("/api/vendor-portal/rfqs/" + RFQ + "/quotations")
                        .file(new MockMultipartFile("files", "again.xlsx", "application/octet-stream", "other".getBytes()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + shakti))
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
    }

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

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }
}

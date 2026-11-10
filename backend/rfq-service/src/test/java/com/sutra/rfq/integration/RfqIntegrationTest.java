package com.sutra.rfq.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.sutra.api.internal.InternalContracts.RfqQuotationUsage;
import com.sutra.common.security.JwtCodec;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.rfq.client.MasterDataClient;
import com.sutra.rfq.client.QuotationClient;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The RFQ service against PostgreSQL (schema rfq_it in sutra_test, built from this service's migrations): a buyer
 * creates and opens an RFQ through the API, the comparison service moves it to review through the internal API
 * (compare-and-set), and the rows, the number sequence and the audit outbox are checked in the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CleanDatabaseConfig.class)
class RfqIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JwtCodec jwt;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean MasterDataClient masterData;
    @MockitoBean QuotationClient quotations;

    private String buyer;
    private String service;

    @BeforeEach
    void setUp() {
        buyer = jwt.issue(new SutraUserPrincipal(10L, "asha.buyer", null, true, Set.of(RoleName.PROCUREMENT), null, 0));
        service = jwt.issueService("comparison-service");
        when(masterData.entity(1L)).thenReturn(new MasterDataClient.EntitySnapshot(1L, "AXM", "Axiom Auto Components", true));
        when(quotations.usage(any())).thenReturn(new RfqQuotationUsage(0, List.of()));
    }

    @Test
    void anRfqIsCreatedOpenedAndMovedToReviewByTheComparisonService() throws Exception {
        MvcResult created = call(buyer, post("/api/purchase-requests/with-items"), """
                {"request": {"title": "Production materials", "entityId": 1, "currency": "INR"},
                 "items": [{"itemCode": "MT-101", "description": "CRCA sheet 1.2 mm", "quantity": 12, "unitOfMeasure": "Tonne"},
                           {"itemCode": "MT-104", "description": "Bearing 6204-2RS", "quantity": 2000, "unitOfMeasure": "Nos"}],
                 "open": true}""", 201);
        long id = ((Number) JsonPath.read(body(created), "$.id")).longValue();
        assertThat((String) JsonPath.read(body(created), "$.requestNumber")).matches("PR-\\d{4}-\\d{5}");
        assertThat((String) JsonPath.read(body(created), "$.status")).isEqualTo("OPEN");

        // the comparison service moves it OPEN -> UNDER_REVIEW; the same call again is harmless
        String change = "{\"from\":\"OPEN\",\"to\":\"UNDER_REVIEW\",\"actor\":\"asha.buyer\",\"details\":{\"comparisonVersion\":1}}";
        call(service, post("/internal/rfqs/" + id + "/status"), change, 200);
        call(service, post("/internal/rfqs/" + id + "/status"), change, 200);
        // an award from a status the RFQ no longer has is refused
        MvcResult refused = call(service, post("/internal/rfqs/" + id + "/status"),
                "{\"from\":\"OPEN\",\"to\":\"APPROVED\",\"actor\":\"ravi\"}", 422);
        assertThat((String) JsonPath.read(body(refused), "$.code")).isEqualTo("PR_STATUS_CHANGED");

        assertThat(jdbc.queryForObject("select status from purchase_request where id = ?", String.class, id))
                .isEqualTo("UNDER_REVIEW");
        assertThat(jdbc.queryForObject("select count(*) from request_item where purchase_request_id = ?", Long.class, id))
                .isEqualTo(2L);
        assertThat(jdbc.queryForList("select action from audit_outbox where entity_id = ? order by id", String.class, id))
                .containsSubsequence("CREATED", "ITEM_ADDED", "ITEM_ADDED", "STATUS_CHANGED", "STATUS_CHANGED");
        assertThat(jdbc.queryForObject("select count(*) from audit_outbox where correlation_id is null", Long.class))
                .as("every event carries a correlation id").isZero();
        // the other services read it in full through the internal API
        MvcResult internal = call(service, get("/internal/rfqs/" + id), null, 200);
        assertThat((Integer) JsonPath.read(body(internal), "$.items.length()")).isEqualTo(2);
    }

    @Test
    void aBuyerCannotUseTheInternalApiAndAServiceCannotUseThePublicOne() throws Exception {
        call(buyer, get("/internal/rfqs/1"), null, 403);
        call(service, get("/api/purchase-requests"), null, 403);
    }

    @Test
    void itemsChangeAndTheQuotationServiceIsAskedToReCheckAfterCommit() throws Exception {
        MvcResult created = call(buyer, post("/api/purchase-requests"),
                "{\"title\":\"Bearings\",\"entityId\":1,\"currency\":\"INR\"}", 201);
        long id = ((Number) JsonPath.read(body(created), "$.id")).longValue();

        call(buyer, post("/api/purchase-requests/" + id + "/items"),
                "{\"description\":\"Bearing 6204\",\"quantity\":100,\"unitOfMeasure\":\"Nos\"}", 201);
        call(buyer, patch("/api/purchase-requests/" + id + "/status"), "{\"status\":\"OPEN\"}", 200);

        verify(quotations).revalidate(id);
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

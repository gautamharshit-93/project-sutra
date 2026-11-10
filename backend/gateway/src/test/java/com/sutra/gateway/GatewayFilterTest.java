package com.sutra.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sutra.common.security.JwtCodec;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SecurityProperties;
import com.sutra.common.security.SutraUserPrincipal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** Edge checks and routing at the gateway. */
class GatewayFilterTest {

    private final JwtCodec jwt = new JwtCodec(new SecurityProperties("test-secret-test-secret-test-secret-123", 60, List.of()));
    private final TokenStateCache states = mock(TokenStateCache.class);
    private final GatewayFilter filter = new GatewayFilter(jwt, states, JsonMapper.builder().build(),
            new PublicEndpointRateLimiter(20, java.time.Clock.systemUTC()));

    private MockHttpServletResponse run(String path, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private String tokenFor(long id, long version) {
        return jwt.issue(new SutraUserPrincipal(id, "u" + id, null, true, Set.of(RoleName.PROCUREMENT), null, version));
    }

    @Test
    void internalApisDoNotExistFromOutside() throws Exception {
        assertThat(run("/internal/stats", null).getStatus()).isEqualTo(404);
    }

    @Test
    void currentTokenOfAnActiveUserPasses() throws Exception {
        when(states.get(1L)).thenReturn(new TokenStateCache.TokenState(true, true, 2));

        assertThat(run("/api/purchase-requests", tokenFor(1L, 2)).getStatus()).isEqualTo(200);
    }

    @Test
    void tokenOfAChangedOrDeactivatedUserIsRefused() throws Exception {
        when(states.get(1L)).thenReturn(new TokenStateCache.TokenState(true, true, 3));
        when(states.get(2L)).thenReturn(new TokenStateCache.TokenState(true, false, 0));

        MockHttpServletResponse changed = run("/api/purchase-requests", tokenFor(1L, 2));
        assertThat(changed.getStatus()).isEqualTo(401);
        assertThat(changed.getContentAsString()).contains("SESSION_EXPIRED");
        assertThat(run("/api/purchase-requests", tokenFor(2L, 0)).getStatus()).isEqualTo(401);
    }

    @Test
    void serviceTokensCannotBeUsedFromOutside() throws Exception {
        assertThat(run("/api/users", jwt.issueService("auth-service")).getStatus()).isEqualTo(401);
    }

    @Test
    void authServiceDownMeansTryAgainNotSignedOut() throws Exception {
        when(states.get(1L)).thenReturn(null);

        assertThat(run("/api/dashboard", tokenFor(1L, 0)).getStatus()).isEqualTo(503);
    }

    @Test
    void requestsWithoutATokenAreLeftToTheServices() throws Exception {
        assertThat(run("/api/auth/login", null).getStatus()).isEqualTo(200);
    }

    @Test
    void routesAreMatchedInOrderSoHistoryGoesToTheAuditService() {
        var props = new GatewayProperties(List.of(
                new GatewayProperties.Route("audit service", "http://audit", List.of("/api/purchase-requests/*/history")),
                new GatewayProperties.Route("quotation service", "http://quotation", List.of("/api/purchase-requests/*/quotations")),
                new GatewayProperties.Route("RFQ service", "http://rfq", List.of("/api/purchase-requests/**"))),
                List.of(), "http://auth", 15, 600);
        RouteTable routes = new RouteTable(props);

        assertThat(routes.find("/api/purchase-requests/12/history")).get()
                .extracting(GatewayProperties.Route::service).isEqualTo("audit service");
        assertThat(routes.find("/api/purchase-requests/12/quotations")).get()
                .extracting(GatewayProperties.Route::service).isEqualTo("quotation service");
        assertThat(routes.find("/api/purchase-requests/12")).get()
                .extracting(GatewayProperties.Route::service).isEqualTo("RFQ service");
        assertThat(routes.find("/api/unknown")).isEmpty();
    }
}

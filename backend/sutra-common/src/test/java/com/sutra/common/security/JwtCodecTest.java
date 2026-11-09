package com.sutra.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tokens every service verifies on its own. */
class JwtCodecTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-123";
    private final JwtCodec jwt = new JwtCodec(new SecurityProperties(SECRET, 60, List.of()));

    @Test
    void userTokenCarriesIdRolesVendorAndVersion() {
        var vendorUser = new SutraUserPrincipal(7L, "brakeline", "hash", true, Set.of(RoleName.VENDOR), 42L, 3);

        var token = jwt.verify(jwt.issue(vendorUser)).orElseThrow();

        assertThat(token.isService()).isFalse();
        assertThat(token.principal().getId()).isEqualTo(7L);
        assertThat(token.principal().getUsername()).isEqualTo("brakeline");
        assertThat(token.principal().getRoles()).containsExactly(RoleName.VENDOR);
        assertThat(token.principal().getVendorId()).isEqualTo(42L);
        assertThat(token.principal().getTokenVersion()).isEqualTo(3);
        assertThat(token.principal().getPassword()).as("no password hash in a token").isNull();
    }

    @Test
    void serviceTokenIsMarkedAsSuch() {
        var token = jwt.verify(jwt.issueService("procurement-service")).orElseThrow();

        assertThat(token.isService()).isTrue();
        assertThat(token.principal().hasRole(RoleName.SERVICE)).isTrue();
        assertThat(token.principal().getUsername()).isEqualTo("procurement-service");
    }

    @Test
    void userTokenCanNeverClaimTheServiceRole() {
        var forged = new SutraUserPrincipal(1L, "mallory", null, true, Set.of(RoleName.SERVICE, RoleName.APPROVER));

        var token = jwt.verify(jwt.issue(forged)).orElseThrow();

        assertThat(token.principal().getRoles()).containsExactly(RoleName.APPROVER);
    }

    @Test
    void tokensSignedWithAnotherSecretOrTamperedAreRejected() {
        String other = new JwtCodec(new SecurityProperties("another-secret-another-secret-12345", 60, List.of()))
                .issue(new SutraUserPrincipal(1L, "admin", null, true, Set.of(RoleName.ADMIN)));

        assertThat(jwt.verify(other)).isEmpty();
        assertThat(jwt.verify("not.a.token")).isEmpty();
    }

    @Test
    void shortSecretIsRefusedAtStartUp() {
        assertThatThrownBy(() -> new JwtCodec(new SecurityProperties("short", 60, List.of())))
                .isInstanceOf(IllegalStateException.class);
    }
}

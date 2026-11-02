package com.sutra.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;

/**
 * Signs and verifies HS256 tokens. User tokens carry the user id, roles, vendor id and token version, so every
 * service can authorise a request without calling the auth service. Service tokens live five minutes.
 */
public class JwtCodec {

    public static final String TYPE_USER = "user";
    public static final String TYPE_SERVICE = "service";
    static final long SERVICE_TOKEN_MINUTES = 5;

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtCodec(SecurityProperties properties) {
        String secret = properties.jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("sutra.security.jwt-secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = properties.jwtExpirationMinutes();
    }

    /** The verified content of a token. */
    public record Token(String type, SutraUserPrincipal principal, Instant expiresAt) {

        public boolean isService() {
            return TYPE_SERVICE.equals(type);
        }
    }

    public String issue(SutraUserPrincipal user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("typ", TYPE_USER)
                .claim("uid", user.getId())
                .claim("roles", user.getRoles().stream().map(Enum::name).sorted().toList())
                .claim("vid", user.getVendorId())
                .claim("ver", user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public String issueService(String serviceName) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(serviceName)
                .claim("typ", TYPE_SERVICE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(SERVICE_TOKEN_MINUTES, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /** The token's content if it is correctly signed and unexpired. */
    public Optional<Token> verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Instant expires = c.getExpiration().toInstant();
            if (TYPE_SERVICE.equals(c.get("typ", String.class))) {
                return Optional.of(new Token(TYPE_SERVICE, SutraUserPrincipal.service(c.getSubject()), expires));
            }
            Set<RoleName> roles = EnumSet.noneOf(RoleName.class);
            if (c.get("roles") instanceof Collection<?> list) {
                for (Object r : list) {
                    RoleName role = RoleName.valueOf(String.valueOf(r));
                    if (role != RoleName.SERVICE) {
                        roles.add(role); // a user token can never claim to be a service
                    }
                }
            }
            Number uid = c.get("uid", Number.class);
            Number vid = c.get("vid", Number.class);
            Number ver = c.get("ver", Number.class);
            SutraUserPrincipal principal = new SutraUserPrincipal(uid == null ? null : uid.longValue(), c.getSubject(),
                    null, true, roles, vid == null ? null : vid.longValue(), ver == null ? 0 : ver.longValue());
            return Optional.of(new Token(TYPE_USER, principal, expires));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

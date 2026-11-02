package com.sutra.gateway;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/** Finds the service that owns a path. */
@Component
public class RouteTable {

    private final GatewayProperties properties;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public RouteTable(GatewayProperties properties) {
        this.properties = properties;
    }

    public Optional<GatewayProperties.Route> find(String path) {
        return properties.routes().stream()
                .filter(r -> r.paths().stream().anyMatch(p -> matcher.match(p, path)))
                .findFirst();
    }
}

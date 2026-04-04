package com.users.gateway.routing;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRouter {

	@Bean
	public RouteLocator routes(RouteLocatorBuilder builder) {
		return builder.routes()
		.route("user-service", route -> route.path("/users/**").uri("lb://user-service"))
        .route("authentication-server", route -> route.path("/authentication/**").uri("lb://authentication-server"))
		// ##### swagger
        .route("user-service-docs", r -> r
            .path("/v3/api-docs/user/**", "/v3/api-docs/user/")
            .filters(f -> f.rewritePath(
                "/v3/api-docs/user(?<segment>/?.*)",
                "/v3/api-docs${segment}"
            ))
            .uri("lb://user-service")
        )
        .route("authentication-server-docs", r -> r
            .path("/v3/api-docs/authentication/**", "/v3/api-docs/authentication/")
            .filters(f -> f.rewritePath(
                "/v3/api-docs/authentication(?<segment>/?.*)",
                "/v3/api-docs${segment}"
            ))
            .uri("lb://authentication-server")
        )
        .build();
    }

}

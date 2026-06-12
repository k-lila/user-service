package com.users.gateway;

import org.junit.jupiter.api.Test;

import com.users.gateway.integration.AbstractGatewayIntegrationTest;

/**
 * Smoke test: o contexto reativo completo (segurança OAuth2/BFF, rotas, rate limiting,
 * sessão Redis) sobe com a infraestrutura de teste (Testcontainers Redis + WireMock).
 */
class GatewayApplicationTests extends AbstractGatewayIntegrationTest {

	@Test
	void contextLoads() {
	}

}

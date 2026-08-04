package com.users.gateway.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regressão Elo 2 / Elo 6 (ADR-019): a cadeia ForwardedHeaderTransformer × trusted-proxies
 * do gateway é invisível à suíte base porque (a) os testes não enviam X-Forwarded-For e
 * (b) a config de teste não define trusted-proxies (logo XForwardedHeadersFilter nem registra).
 *
 * Esta classe reproduz o defeito e verifica a correção:
 *
 * - {@link #comXFFDeIPPublico_xForwardedHostNaoDeveChEgarAoDownstream()} documenta o defeito:
 *   com XFF de IP público, o ForwardedHeaderTransformer reescreve remoteAddress → trusted-proxies
 *   falha → XForwardedHeadersFilter não emite X-Forwarded-Host. Se este teste começar a falhar
 *   (WireMock passando a receber X-Forwarded-Host), indica que o nginx voltou a enviar XFF.
 *
 * - {@link #semXFF_xForwardedHeadersDevemChEgarAoDownstream()} verifica o estado pós-fix:
 *   sem XFF (como nginx emite após Elo 6), remoteAddress permanece o peer real (127.0.0.1),
 *   trusted-proxies casa e XForwardedHeadersFilter emite X-Forwarded-Host/Proto downstream.
 *
 * Pré-requisito do contexto: trusted-proxies deve incluir loopback (127.0.0.1), pois o
 * peer dos testes é localhost. A prod usa RFC1918; a divergência é intencional e documentada.
 */
class XForwardedHeadersIntegrationTest extends AbstractGatewayIntegrationTest {

    @DynamicPropertySource
    static void overrideForwardedHeadersConfig(DynamicPropertyRegistry registry) {
        // strategy=framework: registra ForwardedHeaderTransformer como WebFilter, reproduzindo
        // o comportamento de produção (gateway.yml: forward-headers-strategy: framework).
        registry.add("server.forward-headers-strategy", () -> "framework");
        // trusted-proxies com loopback: o peer dos testes é 127.0.0.1; em prod é RFC1918.
        // Incluir loopback aqui só para que o cenário "sem XFF" valide a emissão de headers.
        registry.add("spring.cloud.gateway.server.webflux.trusted-proxies",
                () -> "127\\.0\\.0\\.1|10\\..*|172\\.(1[6-9]|2[0-9]|3[01])\\..*|192\\.168\\..*");
    }

    @Test
    void semXFF_xForwardedHeadersDevemChEgarAoDownstream() {
        // Simula o estado pós-fix do nginx (Opção C, ADR-019): nginx suprime XFF antes de
        // repassar ao gateway. Sem XFF, ForwardedHeaderTransformer não reescreve remoteAddress
        // (permanece 127.0.0.1), trusted-proxies casa e XForwardedHeadersFilter emite
        // X-Forwarded-Host e X-Forwarded-Proto para o auth-server.
        downstream.stubFor(get(urlPathEqualTo("/oauth2/authorize"))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "https://app.k-lila.com.br/login")));

        webTestClient.get().uri("/oauth2/authorize?response_type=code&client_id=test")
                .header("X-Forwarded-Host", "app.k-lila.com.br")
                .header("X-Forwarded-Proto", "https")
                // Sem X-Forwarded-For: simula nginx com supressão de XFF (Elo 6)
                .exchange()
                .expectStatus().is3xxRedirection();

        downstream.verify(getRequestedFor(urlPathEqualTo("/oauth2/authorize"))
                .withHeader("X-Forwarded-Host", equalTo("app.k-lila.com.br"))
                .withHeader("X-Forwarded-Proto", equalTo("https")));
    }

    @Test
    void comXFFDeIPPublico_xForwardedHostNaoDeveChEgarAoDownstream() {
        // Regressão: documenta o comportamento com XFF de IP público (cenário pré-fix do Elo 6).
        // ForwardedHeaderTransformer reescreve remoteAddress → "203.0.113.1" (IP público).
        // TrustedProxies.isTrusted("203.0.113.1") falha (não RFC1918, não loopback).
        // XForwardedHeadersFilter fica silencioso: NÃO emite X-Forwarded-Host downstream.
        // Se este teste começar a falhar (WireMock receber X-Forwarded-Host), o nginx voltou
        // a enviar XFF ao gateway — o Elo 6 regrediu.
        downstream.stubFor(get(urlPathEqualTo("/oauth2/authorize"))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "http://container:8082/login")));

        webTestClient.get().uri("/oauth2/authorize?response_type=code&client_id=test")
                .header("X-Forwarded-For", "203.0.113.1")  // IP público (TEST-NET-3, RFC 5737)
                .header("X-Forwarded-Host", "app.k-lila.com.br")
                .header("X-Forwarded-Proto", "https")
                .exchange()
                .expectStatus().is3xxRedirection();

        downstream.verify(getRequestedFor(urlPathEqualTo("/oauth2/authorize"))
                .withoutHeader("X-Forwarded-Host"));
    }
}

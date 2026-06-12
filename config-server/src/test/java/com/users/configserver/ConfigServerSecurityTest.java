package com.users.configserver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * C10.6 — valida o HTTP Basic do config-server (C17/G3): endpoints de config exigem
 * autenticação; o health fica aberto para os healthchecks.
 */
@SpringBootTest(properties = {
        "spring.security.user.name=config-client",
        "spring.security.user.password=config-dev-secret"
})
@AutoConfigureMockMvc
class ConfigServerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    private static String basic(String user, String pass) {
        String token = Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    @Test
    void deveRetornar401_quandoSemCredenciais() throws Exception {
        mockMvc.perform(get("/user-service/default"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deveRetornar200_quandoBasicCorreto() throws Exception {
        mockMvc.perform(get("/user-service/default")
                        .header(HttpHeaders.AUTHORIZATION, basic("config-client", "config-dev-secret")))
                .andExpect(status().isOk());
    }

    @Test
    void deveRetornar401_quandoCredencialErrada() throws Exception {
        mockMvc.perform(get("/user-service/default")
                        .header(HttpHeaders.AUTHORIZATION, basic("config-client", "senha-errada")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deveDeixarHealthAberto_semCredenciais() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}

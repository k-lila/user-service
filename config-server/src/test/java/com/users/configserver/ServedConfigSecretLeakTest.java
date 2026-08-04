package com.users.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Regressão do vazamento do {@code OAUTH_CLIENT_SECRET} (ADR-020).
 *
 * <p>O springdoc materializa as propriedades {@code springdoc.swagger-ui.oauth.*} como uma chamada
 * {@code ui.initOAuth({...})} <em>literal</em> dentro de {@code /swagger-ui/swagger-initializer.js},
 * que é servido ao browser. Com {@code oauth.client-secret: ${OAUTH_CLIENT_SECRET}} no
 * {@code gateway.yml}, o segredo do cliente confidencial do BFF era publicado em texto plano para
 * qualquer visitante da rota — que na época era pública.
 *
 * <p>O teste vive aqui, e não no módulo gateway, porque a config servida é <b>deste</b> módulo: um
 * teste no gateway leria o {@code application.yml} local, onde o bloco nunca esteve, e passaria
 * verde com o defeito de pé.
 */
class ServedConfigSecretLeakTest {

    /** Fragmentos que denunciam um segredo — comparados em minúsculas. */
    private static final List<String> MARCAS_DE_SEGREDO = List.of("secret", "password", "token");

    @Test
    void nenhumaPropriedadeDoSpringdocPodeCarregarSegredo() throws IOException {
        List<String> achados = new ArrayList<>();
        List<String> inspecionadas = new ArrayList<>();

        for (String arquivo : List.of("gateway.yml", "user-service.yml", "authorization-server.yml",
                "notification-service.yml", "discovery-server.yml")) {
            ClassPathResource recurso = new ClassPathResource("config/" + arquivo);
            if (!recurso.exists()) {
                continue;
            }
            for (PropertySource<?> fonte : new YamlPropertySourceLoader().load(arquivo, recurso)) {
                if (!(fonte instanceof EnumerablePropertySource<?> enumeravel)) {
                    continue;
                }
                for (String nome : enumeravel.getPropertyNames()) {
                    if (!nome.startsWith("springdoc.")) {
                        continue;
                    }
                    inspecionadas.add(nome);
                    String valor = String.valueOf(enumeravel.getProperty(nome));
                    String alvo = (nome + "=" + valor).toLowerCase(Locale.ROOT);
                    if (MARCAS_DE_SEGREDO.stream().anyMatch(alvo::contains)) {
                        achados.add(arquivo + " → " + nome + "=" + valor);
                    }
                }
            }
        }

        // Guarda anti-vacuidade: se o loader deixasse de enxergar as propriedades (mudança de
        // caminho, de formato, de API), a asserção principal passaria verde para sempre.
        assertThat(inspecionadas)
                .as("o teste precisa ter de fato inspecionado propriedades springdoc.*")
                .isNotEmpty();

        assertThat(achados)
                .as("propriedade springdoc.* carregando segredo — o springdoc serve essas "
                        + "propriedades ao browser no swagger-initializer.js (ADR-020)")
                .isEmpty();
    }
}

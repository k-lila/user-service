package authorizationserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableFeignClients
// Habilita o @Scheduled do OAuthStatePurgeService (ADR-022). Era o único módulo de domínio
// sem agendamento; sem esta anotação o poller é um bean inerte que nunca dispara.
@EnableScheduling
@SpringBootApplication
public class AuthorizationServerApplication {
		public static void main(String[] args) {
		SpringApplication.run(AuthorizationServerApplication.class, args);
	}

}

# users

Projeto em microsserviços para gerenciamento de usuários com autenticação via JWT

## Serviços

/config-server
/discovery-server
/gateway
/authentication-server
/user-service

## Execute

1. Inicie a database
   - docker compose up -d

2. Para cada serviço, execute
   - mvn spring-boot:run

## Endpoints disponíveis:

- Gateway: http://localhost:8081
- Eureka: http://localhost:9091
- Config Server: http://localhost:8888
- Zipkin: http://localhost:9411
- Swagger UI: http://localhost:8081/swagger-ui/index.html
- Swagger Auth: http://localhost:8082/swagger-ui/index.html

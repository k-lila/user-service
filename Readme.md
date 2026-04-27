# users

Projeto em microsserviços para gerenciamento de usuários com autenticação via OAuth2

## Serviços

/config-server
/discovery-server
/authorization-server
/user-service
/gateway

## Execute

Para execução local:

1.  Inicie a database
    - docker compose up -d user-mongo

2.  Para cada serviço, execute
    - mvn spring-boot:run

3.  Para a interface React, execute
    - npm run dev

Para execução via docker:

1.  Para iniciar a aplicação
    - docker compose up -d --build

2.  Para derrubar os containers
    - docker compose down -v

## Endpoints disponíveis:

- Gateway: http://localhost:8081
- Eureka: http://localhost:9091
- Zipkin: http://localhost:9411
- Swagger UI: http://localhost:8081/swagger-ui/index.html
- Logout: http://localhost:8082/logout
- interface: http://localhost:5173/

package com.users.userservice.config;

import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.AbstractMongoClientConfiguration;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

@Configuration
public class MongoConfig extends AbstractMongoClientConfiguration {

    @Value("${spring.data.mongodb.uri}")
    private String mongoUri;
    @Value("${spring.data.mongodb.database}")
    private String database;
    /**
     * Teto de conexões por INSTÂNCIA do user-service (ADR-024). O default do driver é 100, e o
     * consumo total é N×este valor: com o serviço replicado contra o piso mínimo (um nó Mongo
     * com {@code mem_limit: 1024m}), 5 réplicas no default abririam até 500 conexões — a ~1MB de
     * pilha por conexão, meio gigabyte só de sockets num nó de um. Não é o teto de
     * {@code max_connections} que limita aqui (o Mongo aceita milhares): é a memória do nó.
     */
    @Value("${spring.data.mongodb.max-pool-size:50}")
    private int maxPoolSize;

    @Override
    protected String getDatabaseName() {
        return database;
    }

    @Override
    public MongoClient mongoClient() {
        // applyToConnectionPoolSettings DEPOIS de applyConnectionString: a ordem importa, senão
        // o valor da URI (quando houver) prevaleceria sobre o knob.
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(mongoUri))
                .applyToConnectionPoolSettings(builder -> builder.maxSize(maxPoolSize))
                .build();
        return MongoClients.create(settings);
    }

    // Liga a criação de índices a partir das anotações (@Indexed(unique=true) em User.email).
    @Override
    protected boolean autoIndexCreation() {
        return true;
    }

    // Garante que User seja escaneado no startup -> índice criado já no boot.
    @Override
    protected Collection<String> getMappingBasePackages() {
        return List.of("com.users.userservice.domain");
    }
}

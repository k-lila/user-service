package com.users.userservice.config;

import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.AbstractMongoClientConfiguration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

@Configuration
public class MongoConfig extends AbstractMongoClientConfiguration {

    @Value("${spring.data.mongodb.uri}")
    private String mongoUri;
    @Value("${spring.data.mongodb.database}")
    private String database;

    @Override
    protected String getDatabaseName() {
        return database;
    }

    @Override
    public MongoClient mongoClient() {
        return MongoClients.create(mongoUri);
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

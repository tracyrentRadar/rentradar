package com.rentradar.backend.config;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.schema.JsonSchemaProperty;
import org.springframework.data.mongodb.core.schema.MongoJsonSchema;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Applies JSON Schema validators to every collection at startup.
 *
 * The application already enforces these invariants in the domain constructors.
 * This is a second, independent line: a defect in the service layer, a migration
 * script or a direct shell write cannot silently corrupt a collection.
 *
 * Validation is advisory about shape, not a substitute for domain validation.
 * Cross-document rules, such as a property's source being valid for its market,
 * cannot be expressed here and remain the service layer's responsibility.
 */
@Component
public class MongoSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(MongoSchemaInitializer.class);

    private final MongoTemplate mongoTemplate;

    public MongoSchemaInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applySchemas() {
        apply("markets", marketsSchema());
        apply("locations", locationsSchema());
        apply("users", usersSchema());
        apply("properties", propertiesSchema());
        apply("predictions", predictionsSchema());
        apply("fraud_alerts", fraudAlertsSchema());
        apply("trend_forecasts", trendForecastsSchema());
        apply("audit_log", auditLogSchema());
        apply("refresh_tokens",refreshTokensSchema());
        apply("model_registry", modelRegistrySchema());
    }

    /**
     * Creates the collection with its validator if absent, otherwise amends the
     * existing one. Failure is logged and does not stop startup: a validator that
     * cannot be applied is a degraded state, not a reason to refuse service.
     */
    private void apply(String collection, MongoJsonSchema schema) {
        try {
            MongoDatabase db = mongoTemplate.getDb();
            Document validator = schema.toDocument();

            if (!mongoTemplate.collectionExists(collection)) {
                db.createCollection(collection,
                        new com.mongodb.client.model.CreateCollectionOptions()
                                .validationOptions(
                                        new com.mongodb.client.model.ValidationOptions()
                                                .validator(validator)
                                                .validationLevel(
                                                        com.mongodb.client.model.ValidationLevel.STRICT)
                                                .validationAction(
                                                        com.mongodb.client.model.ValidationAction.ERROR)));
                log.info("Created collection {} with schema validator", collection);
            } else {
                db.runCommand(new Document("collMod", collection)
                        .append("validator", validator)
                        .append("validationLevel", "strict")
                        .append("validationAction", "error"));
                log.info("Applied schema validator to existing collection {}", collection);
            }
        } catch (RuntimeException e) {
            log.error("Could not apply schema validator to {}: {}", collection, e.getMessage());
        }
    }

    private MongoJsonSchema marketsSchema() {
        return MongoJsonSchema.builder()
                .required("code", "name", "currency", "defaultAreaUnit", "locale", "active")
                .properties(
                        JsonSchemaProperty.string("code").minLength(2).maxLength(8),
                        JsonSchemaProperty.string("name").minLength(1),
                        JsonSchemaProperty.string("currency").minLength(3).maxLength(3),
                        JsonSchemaProperty.string("defaultAreaUnit").possibleValues(
                                List.of("SQM", "SQFT")),
                        JsonSchemaProperty.bool("active")
                ).build();
    }

    private MongoJsonSchema locationsSchema() {
        return MongoJsonSchema.builder()
                .required("marketId", "city", "district", "latitude", "longitude", "active")
                .properties(
                        JsonSchemaProperty.string("marketId").minLength(1),
                        JsonSchemaProperty.string("city").minLength(1),
                        JsonSchemaProperty.string("district").minLength(1),
                        JsonSchemaProperty.number("latitude").gte(-90).lte(90),
                        JsonSchemaProperty.number("longitude").gte(-180).lte(180),
                        JsonSchemaProperty.bool("active")
                ).build();
    }

    private MongoJsonSchema usersSchema() {
        return MongoJsonSchema.builder()
                .required("email", "passwordHash", "role", "marketId")
                .properties(
                        JsonSchemaProperty.string("email").minLength(3),
                        JsonSchemaProperty.string("passwordHash").minLength(20),
                        JsonSchemaProperty.string("role").possibleValues(
                                List.of("TENANT", "LANDLORD", "ADMIN")),
                        JsonSchemaProperty.int32("failedLoginCount").gte(0)
                ).build();
    }

    private MongoJsonSchema propertiesSchema() {
        return MongoJsonSchema.builder()
                .required("marketId", "locationId", "title", "bedrooms", "bathrooms",
                        "size", "listedPrice", "source", "postedAt")
                .properties(
                        JsonSchemaProperty.string("marketId").minLength(1),
                        JsonSchemaProperty.string("locationId").minLength(1),
                        JsonSchemaProperty.string("title").minLength(1).maxLength(140),
                        JsonSchemaProperty.int32("bedrooms").gte(1).lte(10),
                        JsonSchemaProperty.int32("bathrooms").gte(1).lte(10),
                        JsonSchemaProperty.int32("toilets").gte(0).lte(20),
                        JsonSchemaProperty.int32("parkingSpaces").gte(0).lte(20),
                        JsonSchemaProperty.string("propertyType").minLength(1),
                        JsonSchemaProperty.array("amenities"),
                        JsonSchemaProperty.bool("furnished"),
                        JsonSchemaProperty.string("source").minLength(1),
                        JsonSchemaProperty.object("listedPrice")
                                .properties(
                                        JsonSchemaProperty.string("currency")
                                                .minLength(3).maxLength(3)),
                        JsonSchemaProperty.object("size")
                                .properties(
                                        JsonSchemaProperty.string("unit").possibleValues(
                                                List.of("SQM", "SQFT")))
                ).build();
    }

    private MongoJsonSchema predictionsSchema() {
        return MongoJsonSchema.builder()
                .required("propertyId", "marketId", "predictedPrice", "ciLower", "ciUpper",
                        "verdict", "modelVersion", "correlationId", "createdAt")
                .properties(
                        JsonSchemaProperty.string("verdict").possibleValues(
                                List.of("BELOW_MARKET", "AT_MARKET", "ABOVE_MARKET")),
                        JsonSchemaProperty.string("modelVersion").minLength(1),
                        JsonSchemaProperty.string("correlationId").minLength(1),
                        JsonSchemaProperty.int32("latencyMs").gte(0)
                ).build();
    }

    private MongoJsonSchema fraudAlertsSchema() {
        return MongoJsonSchema.builder()
                .required("propertyId", "marketId", "riskScore", "riskLevel",
                        "modelVersion", "triggeredAt")
                .properties(
                        JsonSchemaProperty.number("riskScore").gte(0).lte(1),
                        JsonSchemaProperty.string("riskLevel").possibleValues(
                                List.of("LOW", "MEDIUM", "HIGH")),
                        JsonSchemaProperty.number("reconstructionError").gte(0),
                        JsonSchemaProperty.string("modelVersion").minLength(1)
                ).build();
    }

    private MongoJsonSchema trendForecastsSchema() {
        return MongoJsonSchema.builder()
                .required("marketId", "locationId", "horizonDays", "series",
                        "direction", "modelVersion", "generatedAt")
                .properties(
                        JsonSchemaProperty.int32("horizonDays").gte(1),
                        JsonSchemaProperty.string("direction").possibleValues(
                                List.of("RISING", "STABLE", "FALLING")),
                        JsonSchemaProperty.number("mae").gte(0),
                        JsonSchemaProperty.string("modelVersion").minLength(1),
                        JsonSchemaProperty.array("series").minItems(1)
                ).build();
    }

    private MongoJsonSchema auditLogSchema() {
        return MongoJsonSchema.builder()
                .required("action", "correlationId", "outcome", "occurredAt")
                .properties(
                        JsonSchemaProperty.string("action").minLength(1),
                        JsonSchemaProperty.string("correlationId").minLength(1),
                        JsonSchemaProperty.string("outcome").minLength(1)
                ).build();
    }

    private MongoJsonSchema refreshTokensSchema() {
        return MongoJsonSchema.builder()
                .required("tokenHash", "userId", "issuedAt", "expiresAt")
                .properties(  JsonSchemaProperty.string("tokenHash").minLength(32),
                        JsonSchemaProperty.string("userId").minLength(1),
                        JsonSchemaProperty.bool("revoked"))
                .build();
    }

    private MongoJsonSchema modelRegistrySchema() {
        return MongoJsonSchema.builder()
                .required("marketId", "modelType", "version", "stage", "registeredAt")
                .properties(
                        JsonSchemaProperty.string("marketId").minLength(2),
                        JsonSchemaProperty.string("modelType").possibleValues(
                                List.of("PRICE", "FRAUD", "TREND")),
                        JsonSchemaProperty.string("version").minLength(1),
                        JsonSchemaProperty.string("stage").possibleValues(
                                List.of("CHAMPION", "CHALLENGER", "ARCHIVED"))
                ).build();
    }
}
package com.lily.dbprovisioner.database;

import com.lily.dbprovisioner.ProvisionerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;

@Configuration
class DynamoDbConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbConfig.class);

    /**
     * endpoint 가 있으면 DynamoDB Local 로 붙는다 (자격증명은 아무 값이나 됨).
     * 없으면 AWS 기본 체인 (EC2/ECS 인스턴스 역할 등)
     */
    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(ProvisionerProperties props) {
        ProvisionerProperties.Dynamodb cfg = props.dynamodb();
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(cfg.region()));
        if (cfg.endpoint() != null && !cfg.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(cfg.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        }
        DynamoDbClient client = builder.build();
        if (cfg.createTable()) {
            createTableIfMissing(client, cfg.table());
        }
        return client;
    }

    /** /actuator/health 의 "dynamodb" 항목 */
    @Bean
    HealthIndicator dynamodb(DynamoDbClient client, ProvisionerProperties props) {
        String table = props.dynamodb().table();
        return () -> {
            try {
                String status = client.describeTable(r -> r.tableName(table)).table().tableStatusAsString();
                return Health.up().withDetail("table", table).withDetail("status", status).build();
            } catch (Exception e) {
                return Health.down().withDetail("table", table).withDetail("error", e.getMessage()).build();
            }
        };
    }

    /** 로컬 전용. 운영 테이블 정의는 README 참고 (pk: String, PAY_PER_REQUEST) */
    private static void createTableIfMissing(DynamoDbClient client, String table) {
        try {
            client.createTable(r -> r.tableName(table)
                    .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("pk").attributeType(ScalarAttributeType.S).build())
                    .billingMode(BillingMode.PAY_PER_REQUEST));
            log.info("dynamodb table created: {}", table);
        } catch (ResourceInUseException ignored) {
            // 이미 있음
        }
    }
}

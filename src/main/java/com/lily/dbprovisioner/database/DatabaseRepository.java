package com.lily.dbprovisioner.database;

import com.lily.dbprovisioner.ProvisionerProperties;
import com.lily.dbprovisioner.engine.Engine;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 메타데이터 테이블 (DynamoDB, 파티션 키 pk 하나).
 *
 * 한 테이블에 아이템 두 종류를 둔다.
 *   pk = DB#{databaseId}      실제 DB 정보
 *   pk = PROJECT#{projectId}  가드 아이템. databaseId 만 가진다
 *
 * 두 아이템을 트랜잭션으로 같이 쓰고, 가드에 attribute_not_exists 조건을 걸어서
 * "프로젝트당 DB 1개" 를 보장한다 (RDB 의 UNIQUE 제약 대신).
 * projectId 조회도 GSI 대신 가드 아이템을 거쳐서 강한 일관성으로 읽는다.
 */
@Repository
public class DatabaseRepository {

    private static final String DB_PREFIX = "DB#";
    private static final String PROJECT_PREFIX = "PROJECT#";

    private final DynamoDbClient dynamo;
    private final String table;

    public DatabaseRepository(DynamoDbClient dynamo, ProvisionerProperties props) {
        this.dynamo = dynamo;
        this.table = props.dynamodb().table();
    }

    /** 이미 프로젝트에 DB 가 있으면 DatabaseAlreadyExistsException */
    public void insert(ManagedDatabase db) {
        Map<String, AttributeValue> guard = Map.of(
                "pk", s(PROJECT_PREFIX + db.projectId()),
                "type", s("PROJECT_GUARD"),
                "databaseId", s(db.id()));
        try {
            dynamo.transactWriteItems(r -> r.transactItems(
                    TransactWriteItem.builder().put(p -> p.tableName(table)
                            .item(toItem(db))
                            .conditionExpression("attribute_not_exists(pk)")).build(),
                    TransactWriteItem.builder().put(p -> p.tableName(table)
                            .item(guard)
                            .conditionExpression("attribute_not_exists(pk)")).build()));
        } catch (TransactionCanceledException e) {
            boolean conflict = e.cancellationReasons().stream()
                    .map(CancellationReason::code)
                    .anyMatch("ConditionalCheckFailed"::equals);
            if (conflict) {
                throw new DatabaseAlreadyExistsException(db.projectId());
            }
            throw e;
        }
    }

    public Optional<ManagedDatabase> findById(String id) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table)
                .key(key(DB_PREFIX + id))
                .consistentRead(true)).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(fromItem(item));
    }

    public Optional<ManagedDatabase> findByProjectId(String projectId) {
        Map<String, AttributeValue> guard = dynamo.getItem(r -> r.tableName(table)
                .key(key(PROJECT_PREFIX + projectId))
                .consistentRead(true)).item();
        if (guard == null || guard.isEmpty()) {
            return Optional.empty();
        }
        return findById(guard.get("databaseId").s());
    }

    /** 해커톤 규모라 Scan 으로 충분. 커지면 GSI 로 바꾼다 */
    public List<ManagedDatabase> findAll() {
        return dynamo.scanPaginator(r -> r.tableName(table)
                        .filterExpression("begins_with(pk, :prefix)")
                        .expressionAttributeValues(Map.of(":prefix", s(DB_PREFIX))))
                .items().stream()
                .map(DatabaseRepository::fromItem)
                .sorted(Comparator.comparing(ManagedDatabase::createdAt))
                .toList();
    }

    public void updateSecretRef(String id, String secretRef) {
        dynamo.updateItem(r -> r.tableName(table)
                .key(key(DB_PREFIX + id))
                .updateExpression("SET secretRef = :ref, updatedAt = :now")
                .conditionExpression("attribute_exists(pk)")
                .expressionAttributeValues(Map.of(
                        ":ref", s(secretRef),
                        ":now", s(Instant.now().toString()))));
    }

    public void updateStatus(String id, DatabaseStatus status, String errorMessage) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":status", s(status.name()));
        values.put(":now", s(Instant.now().toString()));
        String update;
        if (errorMessage == null) {
            update = "SET #status = :status, updatedAt = :now REMOVE errorMessage";
        } else {
            update = "SET #status = :status, updatedAt = :now, errorMessage = :error";
            values.put(":error", s(truncate(errorMessage)));
        }
        dynamo.updateItem(r -> r.tableName(table)
                .key(key(DB_PREFIX + id))
                .updateExpression(update)
                .conditionExpression("attribute_exists(pk)")
                // status 는 DynamoDB 예약어
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(values));
    }

    /** DB 아이템과 가드 아이템을 같이 지운다 */
    public void delete(ManagedDatabase db) {
        dynamo.transactWriteItems(r -> r.transactItems(
                TransactWriteItem.builder().delete(d -> d.tableName(table)
                        .key(key(DB_PREFIX + db.id()))).build(),
                TransactWriteItem.builder().delete(d -> d.tableName(table)
                        .key(key(PROJECT_PREFIX + db.projectId()))).build()));
    }

    private static Map<String, AttributeValue> toItem(ManagedDatabase db) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(DB_PREFIX + db.id()));
        item.put("type", s("DATABASE"));
        item.put("id", s(db.id()));
        item.put("projectId", s(db.projectId()));
        item.put("engine", s(db.engine().code()));
        item.put("dbName", s(db.dbName()));
        item.put("dbUser", s(db.dbUser()));
        item.put("host", s(db.host()));
        item.put("port", AttributeValue.fromN(Integer.toString(db.port())));
        item.put("status", s(db.status().name()));
        item.put("createdAt", s(db.createdAt().toString()));
        item.put("updatedAt", s(db.updatedAt().toString()));
        if (db.secretRef() != null) {
            item.put("secretRef", s(db.secretRef()));
        }
        if (db.errorMessage() != null) {
            item.put("errorMessage", s(truncate(db.errorMessage())));
        }
        return item;
    }

    private static ManagedDatabase fromItem(Map<String, AttributeValue> item) {
        return new ManagedDatabase(
                item.get("id").s(),
                item.get("projectId").s(),
                Engine.fromCode(item.get("engine").s()),
                item.get("dbName").s(),
                item.get("dbUser").s(),
                item.get("host").s(),
                Integer.parseInt(item.get("port").n()),
                optional(item, "secretRef"),
                DatabaseStatus.valueOf(item.get("status").s()),
                optional(item, "errorMessage"),
                Instant.parse(item.get("createdAt").s()),
                Instant.parse(item.get("updatedAt").s()));
    }

    private static Map<String, AttributeValue> key(String pk) {
        return Map.of("pk", s(pk));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static String optional(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private static String truncate(String message) {
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}

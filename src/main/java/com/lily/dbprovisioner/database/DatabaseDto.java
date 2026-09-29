package com.lily.dbprovisioner.database;

import com.lily.dbprovisioner.engine.Engine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public class DatabaseDto {

    public record CreateRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String projectId,
            @NotNull Engine engine) {}

    /** 비밀번호는 포함하지 않는다. 접속 정보는 /env 로만 받는다 */
    public record Response(
            String id,
            String projectId,
            Engine engine,
            DatabaseStatus status,
            String dbName,
            String host,
            int port,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {

        public static Response from(ManagedDatabase db) {
            return new Response(
                    db.id(), db.projectId(), db.engine(), db.status(), db.dbName(),
                    db.host(), db.port(), db.errorMessage(), db.createdAt(), db.updatedAt());
        }
    }

    public record EnvResponse(String databaseId, Map<String, String> env) {}
}

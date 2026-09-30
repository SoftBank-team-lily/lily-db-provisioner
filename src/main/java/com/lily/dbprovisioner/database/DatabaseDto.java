package com.lily.dbprovisioner.database;

import com.lily.dbprovisioner.engine.Engine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public class DatabaseDto {

    /**
     * projectId 는 CI/CD 가 k3s namespace/Secret 이름에도 그대로 쓰므로 k3s 이름 규칙(DNS label)에 맞춘다.
     * 소문자·숫자·하이픈, 처음과 끝은 영숫자, 최대 40자 (접두어를 붙여도 63자 안에 들어가도록)
     */
    public record CreateRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$") String projectId,
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

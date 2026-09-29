package com.lily.dbprovisioner.database;

import com.lily.dbprovisioner.engine.Engine;

import java.time.Instant;

/** 프로젝트 하나에 붙은 DB. 비밀번호 자체는 없고 secretRef(보관소 경로)만 가진다 */
public record ManagedDatabase(
        String id,
        String projectId,
        Engine engine,
        String dbName,
        String dbUser,
        String host,
        int port,
        String secretRef,
        DatabaseStatus status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {}

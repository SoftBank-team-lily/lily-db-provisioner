package com.lily.dbprovisioner.database;

import com.lily.dbprovisioner.ProvisionerProperties;
import com.lily.dbprovisioner.engine.Credentials;
import com.lily.dbprovisioner.engine.Engine;
import com.lily.dbprovisioner.engine.EngineProvisioner;
import com.lily.dbprovisioner.engine.EngineRegistry;
import com.lily.dbprovisioner.engine.ProvisioningException;
import com.lily.dbprovisioner.secret.SecretStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 프로젝트 DB 생성/조회/삭제.
 * CREATE DATABASE 는 1초도 안 걸려서 요청 안에서 동기로 끝낸다.
 */
@Service
public class DatabaseService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);

    private final DatabaseRepository repository;
    private final EngineRegistry engines;
    private final SecretStore secrets;
    private final int connectionLimit;

    public DatabaseService(DatabaseRepository repository, EngineRegistry engines,
                           SecretStore secrets, ProvisionerProperties props) {
        this.repository = repository;
        this.engines = engines;
        this.secrets = secrets;
        this.connectionLimit = props.connectionLimit();
    }

    public ManagedDatabase create(String projectId, Engine engine) {
        EngineProvisioner provisioner = engines.get(engine);

        // 프로젝트당 DB 1개. 이전에 실패한 기록만 있으면 정리하고 다시 만든다
        repository.findByProjectId(projectId).ifPresent(existing -> {
            if (existing.status() != DatabaseStatus.FAILED) {
                throw new DatabaseAlreadyExistsException(projectId);
            }
            cleanUp(existing);
        });

        String id = UUID.randomUUID().toString();
        String name = Credentials.nameFor(id);
        Instant now = Instant.now();
        // 동시에 같은 프로젝트로 요청이 와도 하나만 성공 (조건부 쓰기)
        repository.insert(new ManagedDatabase(id, projectId, engine, name, name,
                provisioner.publicHost(), provisioner.publicPort(),
                null, DatabaseStatus.CREATING, null, now, now));

        try {
            String password = Credentials.newPassword();
            repository.updateSecretRef(id, secrets.put(id, password));
            provisioner.create(name, password, connectionLimit);
            repository.updateStatus(id, DatabaseStatus.AVAILABLE, null);
            log.info("database created: id={} project={} engine={} name={}", id, projectId, engine.code(), name);
        } catch (RuntimeException e) {
            log.error("database create failed: id={} project={} engine={} reason={}",
                    id, projectId, engine.code(), e.getMessage());
            dropQuietly(provisioner, name);
            repository.updateStatus(id, DatabaseStatus.FAILED, e.getMessage());
            throw e instanceof ProvisioningException pe ? pe : new ProvisioningException(e.getMessage(), null);
        }
        return get(id);
    }

    public ManagedDatabase get(String id) {
        return repository.findById(id).orElseThrow(() -> new DatabaseNotFoundException(id));
    }

    public List<ManagedDatabase> list(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return repository.findAll();
        }
        return repository.findByProjectId(projectId).map(List::of).orElse(List.of());
    }

    /** 사용자 앱에 주입할 환경변수 (비밀번호 포함) */
    public Map<String, String> env(String id) {
        ManagedDatabase db = get(id);
        if (db.status() != DatabaseStatus.AVAILABLE) {
            throw new DatabaseNotReadyException(id, db.status());
        }
        String password = secrets.get(db.secretRef());
        return engines.get(db.engine()).env(db.dbName(), password);
    }

    /** drop 은 IF EXISTS 라 실패 후 다시 호출해도 된다 */
    public void delete(String id) {
        ManagedDatabase db = get(id);
        repository.updateStatus(id, DatabaseStatus.DELETING, null);
        try {
            cleanUp(db);
        } catch (RuntimeException e) {
            log.error("database delete failed: id={} reason={}", id, e.getMessage());
            repository.updateStatus(id, DatabaseStatus.FAILED, "delete failed: " + e.getMessage());
            throw e;
        }
        log.info("database deleted: id={} project={} name={}", id, db.projectId(), db.dbName());
    }

    private void cleanUp(ManagedDatabase db) {
        engines.get(db.engine()).drop(db.dbName());
        if (db.secretRef() != null) {
            secrets.delete(db.secretRef());
        }
        repository.delete(db);
    }

    private void dropQuietly(EngineProvisioner provisioner, String name) {
        try {
            provisioner.drop(name);
        } catch (RuntimeException e) {
            log.warn("rollback drop failed: name={} reason={}", name, e.getMessage());
        }
    }
}

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
import java.util.LinkedHashMap;
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
    /** 호스트 이름 또는 IPv4 */
    private static final java.util.regex.Pattern HOST =
            java.util.regex.Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*$");

    private final DatabaseRepository repository;
    private final EngineRegistry engines;
    private final SecretStore secrets;
    private final int connectionLimit;
    private final String appPoolSize;

    public DatabaseService(DatabaseRepository repository, EngineRegistry engines,
                           SecretStore secrets, ProvisionerProperties props) {
        this.repository = repository;
        this.engines = engines;
        this.secrets = secrets;
        this.connectionLimit = props.connectionLimit();
        this.appPoolSize = Integer.toString(props.appPoolSize());
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
        return env(id, null, null);
    }

    /** host/port 가 있으면 그 주소로 (온프레미스 터널 등). 없으면 엔진의 공개 주소 */
    public Map<String, String> env(String id, String host, Integer port) {
        if ((host == null) != (port == null)) {
            throw new IllegalArgumentException("host 와 port 는 함께 지정한다");
        }
        if (host != null && (!HOST.matcher(host).matches() || port < 1 || port > 65535)) {
            throw new IllegalArgumentException("host 또는 port 가 올바르지 않다");
        }
        ManagedDatabase db = get(id);
        if (db.status() != DatabaseStatus.AVAILABLE) {
            throw new DatabaseNotReadyException(id, db.status());
        }
        String password = secrets.get(db.secretRef());
        EngineProvisioner engine = engines.get(db.engine());
        Map<String, String> env = new LinkedHashMap<>(host == null
                ? engine.env(db.dbName(), password)
                : engine.env(db.dbName(), password, host, port));
        // 계정당 연결 제한(connectionLimit)을 넘지 않도록 앱 커넥션 풀 크기를 같이 내려준다
        env.put("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", appPoolSize);
        env.put("DB_POOL_SIZE", appPoolSize);
        return env;
    }

    /**
     * 무중단 스키마 변경(pgroll)을 켠다. lily-cicd 가 pgroll 마이그레이션을 처음 적용하기 전에 부른다.
     * 이미 켜져 있으면 권한만 다시 준다.
     */
    public void enablePgroll(String id) {
        ManagedDatabase db = get(id);
        if (db.status() != DatabaseStatus.AVAILABLE) {
            throw new DatabaseNotReadyException(id, db.status());
        }
        engines.get(db.engine()).enablePgroll(db.dbName());
        log.info("pgroll enabled: id={} project={} name={}", id, db.projectId(), db.dbName());
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

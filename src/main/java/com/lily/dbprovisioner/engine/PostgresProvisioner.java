package com.lily.dbprovisioner.engine;

import com.lily.dbprovisioner.ProvisionerProperties.EngineSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.lily.dbprovisioner.engine.Credentials.checkName;
import static com.lily.dbprovisioner.engine.Credentials.checkPassword;

/**
 * PostgreSQL 13 이상 (DROP DATABASE ... WITH (FORCE)).
 * RDS 의 마스터 계정은 superuser 가 아니라서, 만든 role 을 자기 자신에게 GRANT 해야
 * 그 role 을 OWNER 로 DB 를 만들고 나중에 지울 수 있다 (PG16 부터 필수).
 */
class PostgresProvisioner extends JdbcEngineProvisioner {

    private static final Logger log = LoggerFactory.getLogger(PostgresProvisioner.class);

    private final AtomicBoolean prepared = new AtomicBoolean();

    PostgresProvisioner(EngineSettings settings) {
        super(settings, "admin-postgres");
    }

    @Override
    public Engine engine() {
        return Engine.POSTGRES;
    }

    /**
     * 기본 DB(postgres, template1)는 PUBLIC 에 CONNECT 가 열려 있어서, 테넌트 계정이 접속해
     * 다른 프로젝트 DB 이름 목록을 볼 수 있다. 관리자가 "소유한" 기본 DB 에서만 회수한다
     * (소유자는 권한이 유지되므로 관리자 자신은 막히지 않는다. RDS 에서는 마스터 계정이 소유자).
     */
    @Override
    public void prepare() {
        if (prepared.get()) {
            return;
        }
        List<String> owned = queryForStrings("""
                SELECT datname FROM pg_database
                WHERE datname IN ('postgres', 'template1')
                  AND datdba = (SELECT oid FROM pg_roles WHERE rolname = current_user)""");
        for (String db : owned) {
            exec("REVOKE CONNECT, TEMPORARY ON DATABASE \"" + db + "\" FROM PUBLIC");
        }
        prepared.set(true);
        log.info("postgres hardened: revoked PUBLIC connect on {}", owned);
    }

    @Override
    public void create(String name, String password, int connectionLimit) {
        prepare();
        checkName(name);
        checkPassword(password);
        exec("CREATE ROLE \"" + name + "\" LOGIN PASSWORD '" + password
                + "' CONNECTION LIMIT " + connectionLimit);
        exec("GRANT \"" + name + "\" TO CURRENT_USER");
        exec("CREATE DATABASE \"" + name + "\" OWNER \"" + name + "\"");
        // 기본값은 PUBLIC 에 CONNECT 가 열려 있어서, 막지 않으면 다른 프로젝트 계정도 접속할 수 있다
        exec("REVOKE ALL ON DATABASE \"" + name + "\" FROM PUBLIC");
    }

    @Override
    public void drop(String name) {
        checkName(name);
        exec("DROP DATABASE IF EXISTS \"" + name + "\" WITH (FORCE)");
        exec("DROP ROLE IF EXISTS \"" + name + "\"");
    }

    @Override
    public Map<String, String> env(String name, String password) {
        String hostPort = publicHost() + ":" + publicPort();
        Map<String, String> env = jdbcEnv("jdbc:postgresql://" + hostPort + "/" + name, name, password);
        env.put("DATABASE_URL", "postgresql://" + name + ":" + password + "@" + hostPort + "/" + name);
        return env;
    }
}

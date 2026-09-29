package com.lily.dbprovisioner.engine;

import com.lily.dbprovisioner.ProvisionerProperties.EngineSettings;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.lily.dbprovisioner.engine.Credentials.checkName;
import static com.lily.dbprovisioner.engine.Credentials.checkPassword;

/**
 * PostgreSQL 13 이상 (DROP DATABASE ... WITH (FORCE)).
 * RDS 의 마스터 계정은 superuser 가 아니라서, 만든 role 을 자기 자신에게 GRANT 해야
 * 그 role 을 OWNER 로 DB 를 만들고 나중에 지울 수 있다 (PG16 부터 필수).
 */
class PostgresProvisioner extends JdbcEngineProvisioner {

    PostgresProvisioner(EngineSettings settings) {
        super(settings, "admin-postgres");
    }

    @Override
    public Engine engine() {
        return Engine.POSTGRES;
    }

    @Override
    public void create(String name, String password, int connectionLimit) {
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
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DB_URL", "jdbc:postgresql://" + hostPort + "/" + name);
        env.put("DB_USERNAME", name);
        env.put("DB_PASSWORD", password);
        env.put("DATABASE_URL", "postgresql://" + name + ":" + password + "@" + hostPort + "/" + name);
        return env;
    }
}

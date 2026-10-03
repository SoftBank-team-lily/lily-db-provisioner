package com.lily.dbprovisioner.engine;

import com.lily.dbprovisioner.ProvisionerProperties;
import com.lily.dbprovisioner.ProvisionerProperties.EngineSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.lily.dbprovisioner.engine.Credentials.checkName;
import static com.lily.dbprovisioner.engine.Credentials.checkPassword;

/**
 * PostgreSQL 13 이상 (DROP DATABASE ... WITH (FORCE)).
 * RDS 의 마스터 계정은 superuser 가 아니라서, 만든 role 을 자기 자신에게 GRANT 해야
 * 그 role 을 OWNER 로 DB 를 만들고 나중에 지울 수 있다 (PG16 부터 필수).
 */
class PostgresProvisioner extends JdbcEngineProvisioner {

    private static final Logger log = LoggerFactory.getLogger(PostgresProvisioner.class);

    /** pgroll init 뒤 프로젝트 계정이 상태 테이블을 읽고 쓰고, 버전 스키마를 만들 수 있게 */
    private static final List<String> PGROLL_GRANTS = List.of(
            "GRANT USAGE, CREATE ON SCHEMA pgroll TO \"%s\"",
            "GRANT ALL ON ALL TABLES IN SCHEMA pgroll TO \"%s\"",
            "GRANT ALL ON ALL SEQUENCES IN SCHEMA pgroll TO \"%s\"",
            "GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA pgroll TO \"%s\"");
    private static final Pattern HOST_PORT = Pattern.compile("^jdbc:postgresql://([^/?]+)");

    private final AtomicBoolean prepared = new AtomicBoolean();
    private final PgrollCommand pgroll;

    PostgresProvisioner(EngineSettings settings) {
        this(settings, new ProvisionerProperties.Pgroll("pgroll", "require", 60));
    }

    PostgresProvisioner(EngineSettings settings, ProvisionerProperties.Pgroll pgroll) {
        super(settings, "admin-postgres");
        this.pgroll = new PgrollCommand(pgroll);
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

    /**
     * 이미 init 된 DB 는 init 을 건너뛰고 권한만 다시 준다.
     * 이벤트 트리거 함수는 SECURITY DEFINER 라 프로젝트 계정이 실행한 DDL 도 pgroll 이력에 남는다.
     */
    @Override
    public void enablePgroll(String name) {
        checkName(name);
        Matcher m = HOST_PORT.matcher(settings().adminUrl());
        if (!m.find()) {
            throw new ProvisioningException("postgres: admin-url 에서 호스트를 읽지 못함", null);
        }
        String hostPort = m.group(1);
        String jdbcUrl = "jdbc:postgresql://" + hostPort + "/" + name;
        try (Connection conn = connect(jdbcUrl); Statement st = conn.createStatement()) {
            boolean installed;
            try (ResultSet rs = st.executeQuery("SELECT to_regclass('pgroll.migrations') IS NOT NULL")) {
                installed = rs.next() && rs.getBoolean(1);
            }
            if (!installed) {
                String user = URLEncoder.encode(settings().adminUsername(), StandardCharsets.UTF_8);
                pgroll.run("postgres://" + user + "@" + hostPort + "/" + name, settings().adminPassword(), "init");
                log.info("pgroll initialized: db={}", name);
            }
            for (String grant : PGROLL_GRANTS) {
                st.execute(grant.formatted(name));
            }
        } catch (SQLException e) {
            throw new ProvisioningException("postgres: pgroll 권한 부여 실패: " + e.getMessage(), e);
        }
    }

    /** 공용 풀은 postgres DB 에 붙어 있다. 프로젝트 DB 안의 스키마 권한은 그 DB 에 직접 붙어서 준다 */
    private Connection connect(String jdbcUrl) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", settings().adminUsername());
        props.setProperty("password", settings().adminPassword());
        props.setProperty("connectTimeout", "5");
        try {
            // 실행 jar 의 클래스로더에서 DriverManager 가 드라이버를 못 찾는 경우를 피한다 (runtimeOnly 의존성)
            Driver driver = (Driver) Class.forName("org.postgresql.Driver").getDeclaredConstructor().newInstance();
            Connection conn = driver.connect(jdbcUrl, props);
            if (conn == null) {
                throw new SQLException("postgres 드라이버가 URL 을 받지 않음");
            }
            return conn;
        } catch (ReflectiveOperationException e) {
            throw new SQLException("postgres 드라이버를 불러오지 못함", e);
        }
    }

    @Override
    public Map<String, String> env(String name, String password, String host, int port) {
        String hostPort = host + ":" + port;
        Map<String, String> env = jdbcEnv("jdbc:postgresql://" + hostPort + "/" + name, name, password);
        env.put("DATABASE_URL", "postgresql://" + name + ":" + password + "@" + hostPort + "/" + name);
        return env;
    }
}

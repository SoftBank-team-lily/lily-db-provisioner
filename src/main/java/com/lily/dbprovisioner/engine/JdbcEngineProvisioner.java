package com.lily.dbprovisioner.engine;

import com.lily.dbprovisioner.ProvisionerProperties.EngineSettings;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 관리자 계정으로 공용 인스턴스에 붙는 JDBC 기반 공통 부분.
 * 커넥션 풀은 처음 쓸 때 연결한다 (RDS 가 잠깐 죽어 있어도 프로비저너 기동은 되도록).
 */
abstract class JdbcEngineProvisioner implements EngineProvisioner, DisposableBean {

    private final JdbcTemplate jdbc;
    private final HikariDataSource dataSource;
    private final EngineSettings settings;

    protected JdbcEngineProvisioner(EngineSettings settings, String poolName) {
        this.settings = settings;
        this.dataSource = new HikariDataSource();
        dataSource.setPoolName(poolName);
        dataSource.setJdbcUrl(settings.adminUrl());
        dataSource.setUsername(settings.adminUsername());
        dataSource.setPassword(settings.adminPassword());
        dataSource.setMaximumPoolSize(2);
        dataSource.setMinimumIdle(0);
        // CREATE DATABASE 는 트랜잭션 안에서 실행할 수 없다 (Postgres)
        dataSource.setAutoCommit(true);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * DDL 실행. 스프링 예외 메시지에는 실행한 SQL(= 비밀번호 포함)이 그대로 들어가므로,
     * DB 가 돌려준 원인 메시지만 남겨서 다시 던진다.
     */
    protected void exec(String sql) {
        try {
            jdbc.execute(sql);
        } catch (DataAccessException e) {
            Throwable cause = e.getMostSpecificCause();
            throw new ProvisioningException(engine().code() + ": " + cause.getMessage(),
                    cause instanceof SQLException ? cause : null);
        }
    }

    protected List<String> queryForStrings(String sql) {
        try {
            return jdbc.queryForList(sql, String.class);
        } catch (DataAccessException e) {
            throw new ProvisioningException(engine().code() + ": " + e.getMostSpecificCause().getMessage(), null);
        }
    }

    @Override
    public String publicHost() {
        return settings.publicHost();
    }

    @Override
    public int publicPort() {
        return settings.publicPort();
    }

    @Override
    public boolean ping() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void destroy() {
        dataSource.close();
    }
}

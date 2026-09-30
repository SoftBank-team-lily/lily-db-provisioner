package com.lily.dbprovisioner.engine;

import com.lily.dbprovisioner.ProvisionerProperties.EngineSettings;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 앱에 주입하는 환경변수 이름/값. 커넥션 풀은 처음 쓸 때 연결하므로 DB 없이 돌아간다 */
class EngineEnvTest {

    @Test
    void postgres_환경변수() {
        PostgresProvisioner pg = new PostgresProvisioner(
                new EngineSettings(true, "jdbc:postgresql://admin:5432/postgres", "a", "b", "pg-host", 5432));
        try {
            Map<String, String> env = pg.env("p_0123456789abcdef", "pw");

            String url = "jdbc:postgresql://pg-host:5432/p_0123456789abcdef";
            assertThat(env).containsEntry("DB_URL", url)
                    .containsEntry("DB_USERNAME", "p_0123456789abcdef")
                    .containsEntry("DB_PASSWORD", "pw")
                    .containsEntry("SPRING_DATASOURCE_URL", url)
                    .containsEntry("SPRING_DATASOURCE_USERNAME", "p_0123456789abcdef")
                    .containsEntry("SPRING_DATASOURCE_PASSWORD", "pw")
                    .containsEntry("DATABASE_URL", "postgresql://p_0123456789abcdef:pw@pg-host:5432/p_0123456789abcdef");
        } finally {
            pg.destroy();
        }
    }

    @Test
    void mysql_환경변수() {
        MysqlProvisioner mysql = new MysqlProvisioner(
                new EngineSettings(true, "jdbc:mysql://admin:3306/", "a", "b", "my-host", 3306));
        try {
            Map<String, String> env = mysql.env("p_0123456789abcdef", "pw");

            String url = "jdbc:mysql://my-host:3306/p_0123456789abcdef";
            assertThat(env).containsEntry("DB_URL", url)
                    .containsEntry("SPRING_DATASOURCE_URL", url)
                    .containsEntry("SPRING_DATASOURCE_USERNAME", "p_0123456789abcdef")
                    .containsEntry("SPRING_DATASOURCE_PASSWORD", "pw")
                    .containsEntry("DATABASE_URL", "mysql://p_0123456789abcdef:pw@my-host:3306/p_0123456789abcdef");
        } finally {
            mysql.destroy();
        }
    }
}

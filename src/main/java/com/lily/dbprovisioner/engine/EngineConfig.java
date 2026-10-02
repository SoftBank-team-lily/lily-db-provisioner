package com.lily.dbprovisioner.engine;

import com.lily.dbprovisioner.ProvisionerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** enabled=true 인 엔진만 프로비저너를 만든다 */
@Configuration
class EngineConfig {

    @Bean
    @ConditionalOnProperty(name = "provisioner.engines.postgres.enabled", havingValue = "true")
    PostgresProvisioner postgresProvisioner(ProvisionerProperties props) {
        return new PostgresProvisioner(props.engine("postgres"), props.pgroll());
    }

    @Bean
    @ConditionalOnProperty(name = "provisioner.engines.mysql.enabled", havingValue = "true")
    MysqlProvisioner mysqlProvisioner(ProvisionerProperties props) {
        return new MysqlProvisioner(props.engine("mysql"));
    }
}

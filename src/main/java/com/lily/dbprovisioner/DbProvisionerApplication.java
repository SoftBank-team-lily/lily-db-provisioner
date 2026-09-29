package com.lily.dbprovisioner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 메타데이터는 DynamoDB 에 저장한다.
 * JDBC 는 공용 RDS 에 DDL 을 실행할 때만 쓰므로 DataSource 자동 설정은 끈다.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class DbProvisionerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbProvisionerApplication.class, args);
    }
}

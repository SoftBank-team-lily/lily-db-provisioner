package com.lily.dbprovisioner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

@ConfigurationProperties("provisioner")
public record ProvisionerProperties(
        String apiToken,
        @DefaultValue("20") int connectionLimit,
        @DefaultValue("3") int appPoolSize,
        @DefaultValue Dynamodb dynamodb,
        @DefaultValue Secrets secrets,
        Map<String, EngineSettings> engines) {

    /**
     * 메타데이터 테이블.
     * endpoint 가 있으면 DynamoDB Local 로 붙는다 (로컬 개발/테스트).
     * create-table 은 로컬 전용. 운영 테이블은 IaC 로 만든다.
     */
    public record Dynamodb(
            @DefaultValue("lily-managed-databases") String table,
            String endpoint,
            @DefaultValue("ap-northeast-2") String region,
            @DefaultValue("false") boolean createTable) {}

    public record Secrets(
            @DefaultValue("memory") String store,
            @DefaultValue("/lily/db") String ssmPrefix) {}

    /**
     * 엔진별 공용 인스턴스(RDS) 접속 정보.
     * admin-* 는 프로비저너가 DB/계정을 만들 때 쓰는 관리자 접속,
     * public-* 는 사용자 앱에 주입할 접속 주소다. (로컬 docker 네트워크 등에서 둘이 다를 수 있음)
     */
    public record EngineSettings(
            boolean enabled,
            String adminUrl,
            String adminUsername,
            String adminPassword,
            String publicHost,
            int publicPort) {}

    public EngineSettings engine(String code) {
        return engines == null ? null : engines.get(code);
    }
}

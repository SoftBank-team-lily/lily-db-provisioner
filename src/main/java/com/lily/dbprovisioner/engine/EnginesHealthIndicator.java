package com.lily.dbprovisioner.engine;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** /actuator/health 의 "engines" 항목. 공용 인스턴스별 관리자 접속 상태 */
@Component("engines")
class EnginesHealthIndicator implements HealthIndicator {

    private final EngineRegistry registry;

    EnginesHealthIndicator(EngineRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        registry.all().forEach((engine, provisioner) -> {
            boolean ok = provisioner.ping();
            builder.withDetail(engine.code(), ok ? "UP" : "DOWN");
            if (!ok) {
                builder.down();
            }
        });
        return builder.build();
    }
}

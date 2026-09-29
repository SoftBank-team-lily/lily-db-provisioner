package com.lily.dbprovisioner.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 기동 직후 엔진별 prepare() 를 한 번 시도한다. 실패해도 기동은 막지 않는다 (첫 create 때 재시도) */
@Component
class EnginePreparer {

    private static final Logger log = LoggerFactory.getLogger(EnginePreparer.class);

    private final EngineRegistry registry;

    EnginePreparer(EngineRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    void prepareAll() {
        registry.all().forEach((engine, provisioner) -> {
            try {
                provisioner.prepare();
            } catch (RuntimeException e) {
                log.warn("engine prepare failed, will retry on first create: engine={} reason={}",
                        engine.code(), e.getMessage());
            }
        });
    }
}

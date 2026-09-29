package com.lily.dbprovisioner.engine;

public class EngineNotEnabledException extends RuntimeException {

    public EngineNotEnabledException(Engine engine) {
        super("engine not enabled: " + engine.code());
    }
}

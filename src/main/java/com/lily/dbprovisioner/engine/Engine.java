package com.lily.dbprovisioner.engine;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum Engine {
    POSTGRES("postgres"),
    MYSQL("mysql");

    private final String code;

    Engine(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    @JsonCreator
    public static Engine fromCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported engine: " + code));
    }
}

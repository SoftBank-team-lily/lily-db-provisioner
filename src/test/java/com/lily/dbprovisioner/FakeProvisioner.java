package com.lily.dbprovisioner;

import com.lily.dbprovisioner.engine.Engine;
import com.lily.dbprovisioner.engine.EngineProvisioner;
import com.lily.dbprovisioner.engine.ProvisioningException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 실제 DB 대신 만든/지운 이름만 기억하는 테스트용 프로비저너 */
class FakeProvisioner implements EngineProvisioner {

    final Set<String> created = ConcurrentHashMap.newKeySet();
    volatile boolean failNextCreate;

    @Override
    public Engine engine() {
        return Engine.POSTGRES;
    }

    @Override
    public String publicHost() {
        return "fake-host";
    }

    @Override
    public int publicPort() {
        return 5432;
    }

    @Override
    public void create(String name, String password, int connectionLimit) {
        created.add(name);
        if (failNextCreate) {
            failNextCreate = false;
            throw new ProvisioningException("postgres: simulated failure", null);
        }
    }

    @Override
    public void drop(String name) {
        created.remove(name);
    }

    @Override
    public boolean ping() {
        return true;
    }

    @Override
    public Map<String, String> env(String name, String password, String host, int port) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DB_URL", "jdbc:postgresql://" + host + ":" + port + "/" + name);
        env.put("DB_USERNAME", name);
        env.put("DB_PASSWORD", password);
        return env;
    }
}

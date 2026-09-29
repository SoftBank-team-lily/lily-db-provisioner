package com.lily.dbprovisioner.engine;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class EngineRegistry {

    private final Map<Engine, EngineProvisioner> provisioners = new EnumMap<>(Engine.class);

    public EngineRegistry(List<EngineProvisioner> provisioners) {
        provisioners.forEach(p -> this.provisioners.put(p.engine(), p));
    }

    public EngineProvisioner get(Engine engine) {
        EngineProvisioner provisioner = provisioners.get(engine);
        if (provisioner == null) {
            throw new EngineNotEnabledException(engine);
        }
        return provisioner;
    }

    public Set<Engine> enabled() {
        return provisioners.keySet();
    }

    public Map<Engine, EngineProvisioner> all() {
        return provisioners;
    }
}

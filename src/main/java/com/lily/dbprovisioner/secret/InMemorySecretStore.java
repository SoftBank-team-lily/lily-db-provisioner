package com.lily.dbprovisioner.secret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로컬 개발용. 메모리에만 두므로 재시작하면 사라진다.
 * 운영에서는 secrets.store=ssm 을 쓴다.
 */
class InMemorySecretStore implements SecretStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySecretStore.class);

    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    InMemorySecretStore() {
        log.warn("using in-memory secret store: secrets are lost on restart (development only)");
    }

    @Override
    public String put(String databaseId, String secret) {
        String ref = "memory:" + databaseId;
        secrets.put(ref, secret);
        return ref;
    }

    @Override
    public String get(String ref) {
        String secret = secrets.get(ref);
        if (secret == null) {
            throw new IllegalStateException("secret not found: " + ref);
        }
        return secret;
    }

    @Override
    public void delete(String ref) {
        secrets.remove(ref);
    }
}

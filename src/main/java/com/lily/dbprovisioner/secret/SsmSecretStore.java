package com.lily.dbprovisioner.secret;

import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.ParameterType;

/**
 * AWS SSM Parameter Store (SecureString, KMS 암호화).
 * 경로: {prefix}/{databaseId}/password
 * 필요한 IAM 권한: ssm:PutParameter, ssm:GetParameter, ssm:DeleteParameter (+ 기본 KMS 키 사용)
 */
class SsmSecretStore implements SecretStore {

    private final SsmClient ssm;
    private final String prefix;

    SsmSecretStore(SsmClient ssm, String prefix) {
        this.ssm = ssm;
        this.prefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    @Override
    public String put(String databaseId, String secret) {
        String ref = prefix + "/" + databaseId + "/password";
        ssm.putParameter(r -> r.name(ref)
                .value(secret)
                .type(ParameterType.SECURE_STRING)
                .overwrite(true));
        return ref;
    }

    @Override
    public String get(String ref) {
        return ssm.getParameter(r -> r.name(ref).withDecryption(true)).parameter().value();
    }

    @Override
    public void delete(String ref) {
        try {
            ssm.deleteParameter(r -> r.name(ref));
        } catch (ParameterNotFoundException ignored) {
            // 이미 지워짐
        }
    }
}

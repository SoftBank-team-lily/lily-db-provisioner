package com.lily.dbprovisioner.secret;

import com.lily.dbprovisioner.ProvisionerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import software.amazon.awssdk.services.ssm.SsmClient;

@Configuration
class SecretStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "provisioner.secrets.store", havingValue = "memory", matchIfMissing = true)
    SecretStore inMemorySecretStore(Environment env) {
        // 재시작하면 비밀번호가 사라져서 AVAILABLE 인 DB 의 /env 가 영구히 실패한다
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("provisioner.secrets.store=memory is not allowed in prod (SECRET_STORE=ssm)");
        }
        return new InMemorySecretStore();
    }

    /** 리전/자격증명은 AWS 기본 체인을 따른다 (AWS_REGION, EC2/ECS 인스턴스 역할 등) */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "provisioner.secrets.store", havingValue = "ssm")
    SsmClient ssmClient() {
        return SsmClient.create();
    }

    @Bean
    @ConditionalOnProperty(name = "provisioner.secrets.store", havingValue = "ssm")
    SecretStore ssmSecretStore(SsmClient ssm, ProvisionerProperties props) {
        return new SsmSecretStore(ssm, props.secrets().ssmPrefix());
    }
}

package com.lily.dbprovisioner.engine;

/** 공용 인스턴스에서 DB/계정 생성·삭제가 실패함. 메시지에 SQL 이나 비밀번호를 넣지 않는다 */
public class ProvisioningException extends RuntimeException {

    public ProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}

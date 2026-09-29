package com.lily.dbprovisioner.secret;

/**
 * 프로젝트 DB 비밀번호 보관소.
 * 메타데이터 테이블에는 비밀번호 대신 여기서 돌려준 ref(경로)만 저장한다.
 */
public interface SecretStore {

    /** 저장하고 ref 를 돌려준다. 같은 databaseId 로 다시 호출하면 덮어쓴다 */
    String put(String databaseId, String secret);

    String get(String ref);

    /** 없으면 조용히 넘어간다 */
    void delete(String ref);
}

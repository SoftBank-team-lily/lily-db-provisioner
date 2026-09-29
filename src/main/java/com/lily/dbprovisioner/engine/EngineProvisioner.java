package com.lily.dbprovisioner.engine;

import java.util.Map;

/**
 * 공용 인스턴스 하나(엔진 하나)에 프로젝트 전용 DB + 계정을 만들고 지우는 역할.
 * drop 은 여러 번 호출해도 안전해야 한다 (실패 후 재시도, 롤백에서 재사용).
 * create 는 매번 새 이름으로 한 번만 호출된다.
 */
public interface EngineProvisioner {

    Engine engine();

    /** 사용자 앱이 접속할 호스트 */
    String publicHost();

    int publicPort();

    /**
     * 공용 인스턴스 자체에 한 번 적용할 보안 설정. 여러 번 호출해도 안전해야 한다.
     * 기동 시 한 번 시도하고, 실패하면(인스턴스가 아직 안 떠 있는 등) 첫 create 때 다시 시도한다.
     */
    default void prepare() {}

    /** DB 이름과 계정 이름은 같은 값(name)을 쓴다 */
    void create(String name, String password, int connectionLimit);

    void drop(String name);

    /** 관리자 접속이 살아 있는지 (헬스체크용) */
    boolean ping();

    /**
     * 사용자 앱에 주입할 환경변수.
     * DB_URL / DB_USERNAME / DB_PASSWORD 는 lily-blog-sample 의 연동 규칙과 같고,
     * DATABASE_URL 은 Spring 이 아닌 앱(Node, Python 등)을 위한 값이다.
     */
    Map<String, String> env(String name, String password);
}

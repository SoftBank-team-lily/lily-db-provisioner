package com.lily.dbprovisioner.engine;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * DB 이름/계정/비밀번호 생성.
 * 모두 SQL 에 직접 들어가는 값이라 사용자 입력은 절대 섞지 않고, 허용 문자를 좁게 제한한다.
 */
public final class Credentials {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final Pattern NAME = Pattern.compile("^p_[a-f0-9]{16}$");
    private static final Pattern PASSWORD = Pattern.compile("^[A-Za-z0-9]{24}$");

    private Credentials() {}

    /** p_ + 16자리 hex. MySQL 계정 이름 제한(32자) 안에 들어간다 */
    public static String nameFor(String databaseId) {
        return "p_" + UUID.fromString(databaseId).toString().replace("-", "").substring(0, 16);
    }

    /** 따옴표 이스케이프가 필요 없도록 영숫자만 사용 */
    public static String newPassword() {
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }

    static String checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid database name: " + name);
        }
        return name;
    }

    static String checkPassword(String password) {
        if (password == null || !PASSWORD.matcher(password).matches()) {
            throw new IllegalArgumentException("invalid generated password");
        }
        return password;
    }
}

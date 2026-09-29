package com.lily.dbprovisioner.security;

import com.lily.dbprovisioner.ProvisionerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * 내부 서비스용 토큰 인증. /api/** 는 DB 비밀번호를 돌려주므로 반드시 막아야 한다.
 * 다른 플랫폼 모듈은 "Authorization: Bearer {PROVISIONER_API_TOKEN}" 헤더로 호출한다.
 * 토큰이 비어 있으면 인증을 끈다 (로컬 개발용).
 */
@Component
class ApiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenFilter.class);

    private final byte[] expected;

    ApiTokenFilter(ProvisionerProperties props) {
        String token = props.apiToken();
        this.expected = token == null || token.isBlank()
                ? null
                : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        if (expected == null) {
            log.warn("provisioner.api-token is empty: /api/** is NOT protected (development only)");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return expected == null || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        byte[] actual = header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"timestamp\":\"" + Instant.now()
                    + "\",\"code\":\"UNAUTHORIZED\",\"message\":\"invalid api token\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}

package com.lily.dbprovisioner.engine;

import com.lily.dbprovisioner.ProvisionerProperties.Pgroll;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * pgroll CLI 실행. 비밀번호는 URL 이 아니라 PGPASSWORD 로 넘겨서 프로세스 인자에 남지 않게 한다.
 * 출력에는 진행 표시용 ANSI 코드가 섞여 있어서 지우고 마지막 줄만 오류 메시지로 쓴다.
 */
class PgrollCommand {

    private final Pgroll settings;

    PgrollCommand(Pgroll settings) {
        this.settings = settings;
    }

    /**
     * @param url 비밀번호 없는 {@code postgres://user@host:port/db}
     * @return 정리한 출력
     * @throws ProvisioningException 0 이 아닌 종료 코드나 시간 초과
     */
    String run(String url, String password, String... args) {
        List<String> command = new ArrayList<>();
        command.add(settings.binary());
        command.addAll(List.of(args));
        command.add("--postgres-url");
        command.add(url + "?sslmode=" + settings.sslmode());
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("PGPASSWORD", password);
        env.put("NO_COLOR", "1");
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ProvisioningException("pgroll 실행 실패: " + e.getMessage(), e);
        }
        try (InputStream out = process.getInputStream()) {
            byte[] bytes = out.readAllBytes();
            if (!process.waitFor(settings.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ProvisioningException("pgroll " + args[0] + " 시간 초과", null);
            }
            String output = clean(new String(bytes, StandardCharsets.UTF_8));
            if (process.exitValue() != 0) {
                throw new ProvisioningException("pgroll " + args[0] + " 실패: " + lastLine(output), null);
            }
            return output;
        } catch (IOException e) {
            throw new ProvisioningException("pgroll 출력 읽기 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ProvisioningException("pgroll 대기 중 중단됨", e);
        }
    }

    /** ANSI 색·커서 코드와 스피너 줄바꿈(\r)을 지운다 */
    static String clean(String output) {
        return output.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").replace('\r', '\n').trim();
    }

    static String lastLine(String output) {
        String[] lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].trim();
            }
        }
        return "(출력 없음)";
    }
}

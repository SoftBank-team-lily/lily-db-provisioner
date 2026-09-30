package com.lily.dbprovisioner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lily.dbprovisioner.database.DatabaseRepository;
import com.lily.dbprovisioner.database.DatabaseStatus;
import com.lily.dbprovisioner.database.ManagedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DynamoDB Local 이 필요하다: docker compose up -d dynamodb (또는 DYNAMODB_ENDPOINT) */
@SpringBootTest(properties = {
        "provisioner.api-token=test-token",
        "provisioner.dynamodb.table=lily-managed-databases-test"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DatabaseApiTest {

    private static final String AUTH = "Bearer test-token";
    private static final String TABLE = "lily-managed-databases-test";

    @TestConfiguration
    static class Config {
        @Bean
        FakeProvisioner fakeProvisioner() {
            return new FakeProvisioner();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeProvisioner fake;

    @Autowired
    DynamoDbClient dynamo;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    DatabaseRepository repository;

    @BeforeEach
    void clean() {
        items().forEach(item -> dynamo.deleteItem(r -> r.tableName(TABLE).key(Map.of("pk", item.get("pk")))));
        fake.created.clear();
        fake.failNextCreate = false;
    }

    @Test
    void 토큰_없으면_401() throws Exception {
        mvc.perform(get("/api/databases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 인코딩된_경로로도_토큰_검사를_우회할_수_없다() throws Exception {
        mvc.perform(get(URI.create("/%61pi/databases")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 다른_DB를_가리키는_가드는_지우지_않는다() throws Exception {
        String currentId = create("blog", "postgres").get("id").asText();
        ManagedDatabase stale = repository.findById(currentId).orElseThrow();
        ManagedDatabase old = new ManagedDatabase(UUID.randomUUID().toString(), "blog", stale.engine(),
                stale.dbName(), stale.dbUser(), stale.host(), stale.port(), null,
                DatabaseStatus.FAILED, "old", stale.createdAt(), stale.updatedAt());

        repository.delete(old);

        assertThat(repository.findByProjectId("blog")).map(ManagedDatabase::id).contains(currentId);
    }

    @Test
    void 헬스체크는_토큰_없이_접근() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }

    @Test
    void 사용_가능한_엔진_목록() throws Exception {
        mvc.perform(auth(get("/api/engines")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0]").value("postgres"));
    }

    @Test
    void 생성_후_환경변수_조회_후_삭제() throws Exception {
        JsonNode created = create("blog", "postgres");
        String id = created.get("id").asText();
        String dbName = created.get("dbName").asText();

        assertThat(created.get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(dbName).matches("^p_[a-f0-9]{16}$");
        assertThat(created.has("password")).isFalse();
        assertThat(fake.created).containsExactly(dbName);

        mvc.perform(auth(get("/api/databases/" + id + "/env")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.env.DB_URL").value("jdbc:postgresql://fake-host:5432/" + dbName))
                .andExpect(jsonPath("$.env.DB_USERNAME").value(dbName))
                .andExpect(jsonPath("$.env.DB_PASSWORD").isNotEmpty())
                .andExpect(jsonPath("$.env.SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE").value("5"))
                .andExpect(jsonPath("$.env.DB_POOL_SIZE").value("5"));

        mvc.perform(auth(get("/api/databases").param("projectId", "blog")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        mvc.perform(auth(delete("/api/databases/" + id)))
                .andExpect(status().isNoContent());

        assertThat(fake.created).isEmpty();
        // DB 아이템, 가드 아이템 모두 삭제됨
        assertThat(items()).isEmpty();
        mvc.perform(auth(get("/api/databases/" + id)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 같은_프로젝트로_두번_만들면_409() throws Exception {
        create("blog", "postgres");

        mvc.perform(auth(post("/api/databases"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("blog", "postgres")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_EXISTS"));
    }

    @Test
    void 켜지지_않은_엔진은_400() throws Exception {
        mvc.perform(auth(post("/api/databases"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("blog", "mysql")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENGINE_NOT_ENABLED"));
    }

    @Test
    void 지원하지_않는_엔진_값은_400() throws Exception {
        mvc.perform(auth(post("/api/databases"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("blog", "oracle")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 프로젝트_ID_형식_검사() throws Exception {
        mvc.perform(auth(post("/api/databases"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("blog'; DROP TABLE x;--", "postgres")))
                .andExpect(status().isBadRequest());

        // k3s 이름 규칙: 소문자·숫자·하이픈, 처음과 끝은 영숫자, 최대 40자
        for (String invalid : List.of("My-Blog", "my_blog", "my.blog", "-blog", "blog-", "a".repeat(41))) {
            mvc.perform(auth(post("/api/databases"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(invalid, "postgres")))
                    .andExpect(status().isBadRequest());
        }
        create("my-blog-2", "postgres");
    }

    @Test
    void 생성_실패하면_롤백하고_FAILED_기록_후_재시도_가능() throws Exception {
        fake.failNextCreate = true;

        mvc.perform(auth(post("/api/databases"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("blog", "postgres")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("PROVISIONING_FAILED"));

        assertThat(fake.created).isEmpty();
        mvc.perform(auth(get("/api/databases").param("projectId", "blog")))
                .andExpect(jsonPath("$[0].status").value("FAILED"));

        JsonNode retried = create("blog", "postgres");
        assertThat(retried.get("status").asText()).isEqualTo("AVAILABLE");
        // 이전 FAILED 기록은 정리되고 DB 아이템 1개 + 가드 1개만 남음
        assertThat(items()).hasSize(2);
    }

    @Test
    void 같은_프로젝트로_동시에_요청하면_하나만_성공() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> calls = java.util.stream.IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> mvc.perform(auth(post("/api/databases"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body("race", "postgres")))
                            .andReturn().getResponse().getStatus())
                    .toList();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);
            assertThat(fake.created).hasSize(1);
        } finally {
            pool.shutdown();
        }
    }

    private List<Map<String, AttributeValue>> items() {
        return dynamo.scanPaginator(r -> r.tableName(TABLE)).items().stream().toList();
    }

    private JsonNode create(String projectId, String engine) throws Exception {
        String response = mvc.perform(auth(post("/api/databases"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(projectId, engine)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    private static String body(String projectId, String engine) {
        return """
                {"projectId":"%s","engine":"%s"}
                """.formatted(projectId.replace("\"", "\\\""), engine);
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, AUTH);
    }
}

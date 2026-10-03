package com.lily.dbprovisioner.database;

import com.lily.dbprovisioner.engine.Engine;
import com.lily.dbprovisioner.engine.EngineRegistry;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DatabaseController {

    private final DatabaseService service;
    private final EngineRegistry engines;

    public DatabaseController(DatabaseService service, EngineRegistry engines) {
        this.service = service;
        this.engines = engines;
    }

    /** 이 프로비저너에서 만들 수 있는 엔진 목록 (프론트 선택지용) */
    @GetMapping("/engines")
    public List<Engine> engines() {
        return engines.enabled().stream().sorted().toList();
    }

    @PostMapping("/databases")
    public ResponseEntity<DatabaseDto.Response> create(@Valid @RequestBody DatabaseDto.CreateRequest request) {
        ManagedDatabase db = service.create(request.projectId(), request.engine());
        return ResponseEntity
                .created(URI.create("/api/databases/" + db.id()))
                .body(DatabaseDto.Response.from(db));
    }

    @GetMapping("/databases")
    public List<DatabaseDto.Response> list(@RequestParam(required = false) String projectId) {
        return service.list(projectId).stream().map(DatabaseDto.Response::from).toList();
    }

    @GetMapping("/databases/{id}")
    public DatabaseDto.Response get(@PathVariable String id) {
        return DatabaseDto.Response.from(service.get(id));
    }

    /** CI/CD 가 배포 직전에 호출해서 앱 컨테이너에 그대로 주입한다 */
    @GetMapping("/databases/{id}/env")
    public DatabaseDto.EnvResponse env(@PathVariable String id,
                                       @RequestParam(required = false) String host,
                                       @RequestParam(required = false) Integer port) {
        return new DatabaseDto.EnvResponse(id, service.env(id, host, port));
    }

    /** pgroll 상태 스키마와 이벤트 트리거를 관리자 계정으로 만든다. postgres 만. 여러 번 불러도 된다 */
    @PostMapping("/databases/{id}/pgroll")
    public Map<String, String> enablePgroll(@PathVariable String id) {
        service.enablePgroll(id);
        return Map.of("databaseId", id, "pgroll", "enabled");
    }

    @DeleteMapping("/databases/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}

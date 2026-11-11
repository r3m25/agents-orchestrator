package dev.orchestrator.techlead.infrastructure.run;

import dev.orchestrator.techlead.application.run.RunStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.Map;

public final class FileRunStore implements RunStore {
    private final Path root;
    private final ObjectMapper json;
    public FileRunStore(Path root, ObjectMapper json) { this.root = root; this.json = json; }
    private Path path(String runId) {
        if (!runId.matches("[0-9]{1,30}")) throw new IllegalArgumentException("INVALID_RUN_ID");
        return root.resolve(runId + ".json");
    }
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> load(String runId) throws Exception {
        var file = path(runId);
        return Files.exists(file) ? json.readValue(Files.readString(file), Map.class) : null;
    }
    @Override
    public void save(String runId, Map<String, Object> result) throws Exception {
        Files.createDirectories(root);
        var temporary = Files.createTempFile(root, "result-", ".tmp");
        try {
            Files.writeString(temporary, json.writeValueAsString(result));
            Files.move(temporary, path(runId), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}

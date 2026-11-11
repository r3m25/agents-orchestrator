package dev.orchestrator.techlead.infrastructure.process;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class BoundedProcess {
    public String run(List<String> command, Path directory, Duration timeout, int maxBytes,
                      Map<String, String> environment) throws Exception {
        return runWithInput(command, directory, timeout, maxBytes, environment, "");
    }

    public String runWithInput(List<String> command, Path directory, Duration timeout, int maxBytes,
                               Map<String, String> environment, String inputText) throws Exception {
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        builder.environment().putAll(environment);
        var process = builder.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> {
                try (var stream = process.getOutputStream()) {
                    stream.write(inputText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return null;
            });
            var output = executor.submit(() -> {
                var bytes = new ByteArrayOutputStream();
                try (var input = process.getInputStream()) {
                    var buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (bytes.size() + count > maxBytes) {
                            process.destroyForcibly();
                            throw new IllegalStateException("PROCESS_OUTPUT_LIMIT");
                        }
                        bytes.write(buffer, 0, count);
                    }
                }
                return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            });
            boolean completed;
            try { completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { kill(process); Thread.currentThread().interrupt(); throw e; }
            if (!completed) {
                kill(process);
                throw new IllegalStateException("PROCESS_TIMEOUT");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("PROCESS_FAILED");
            writer.get(5, TimeUnit.SECONDS);
            return output.get(5, TimeUnit.SECONDS);
        } finally {
            kill(process);
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    public static Map<String, String> gitEnvironment() {
        return Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
                "GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_TERMINAL_PROMPT", "0", "GIT_NO_REPLACE_OBJECTS", "1");
    }
}

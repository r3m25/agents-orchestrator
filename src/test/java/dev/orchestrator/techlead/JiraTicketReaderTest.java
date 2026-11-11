package dev.orchestrator.techlead;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.orchestrator.techlead.infrastructure.jira.JiraTicketReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JiraTicketReaderTest {
    @TempDir Path directory;
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsAndNormalizesTicketFromJira() throws Exception {
        var projects = projectDirectory();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/3/issue/KIVNES-35", exchange -> {
            assertEquals("Basic dXNlckBleGFtcGxlLmNvbTp0b2tlbg==", exchange.getRequestHeaders().getFirst("Authorization"));
            assertTrue(exchange.getRequestURI().getQuery().contains("fields=summary,description"));
            respond(exchange, 200, """
                    {"key":"KIVNES-35","fields":{"summary":"Prueba","description":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Primer criterio"}]},{"type":"paragraph","content":[{"type":"text","text":"Segundo criterio"}]}]},"status":{"name":"To Do"},"issuetype":{"name":"Story"},"priority":{"name":"Medium"},"labels":["camunda"],"components":[{"name":"backend"}]}}
                    """);
        });
        server.start();

        var reader = reader(projects);

        var ticket = reader.read("kivnes", "KIVNES-35");

        assertEquals("KIVNES-35", ticket.get("key"));
        assertEquals("Prueba", ticket.get("summary"));
        assertEquals("Primer criterio\nSegundo criterio", ticket.get("description"));
        assertEquals("To Do", ticket.get("status"));
        assertEquals("Story", ticket.get("issueType"));
        assertEquals("Medium", ticket.get("priority"));
        assertEquals(List.of("camunda"), ticket.get("labels"));
        assertEquals(List.of("backend"), ticket.get("components"));
        assertEquals(baseUrl() + "browse/KIVNES-35", ticket.get("url"));
    }

    @Test
    void rejectsTicketOutsideConfiguredProjectBeforeCallingJira() throws Exception {
        var reader = new JiraTicketReader(new ObjectMapper(), projectDirectory(), HttpClient.newHttpClient(),
                URI.create("https://jira.example.test/"), "user@example.com", "token");

        assertThrows(IllegalArgumentException.class, () -> reader.read("kivnes", "OTHER-1"));
    }

    private JiraTicketReader reader(Path projects) {
        return new JiraTicketReader(new ObjectMapper(), projects, HttpClient.newHttpClient(),
                URI.create(baseUrl()), "user@example.com", "token");
    }

    private Path projectDirectory() throws IOException {
        var projects = Files.createDirectory(directory.resolve("projects"));
        Files.writeString(projects.resolve("kivnes.json"), new ObjectMapper().writeValueAsString(Map.of(
                "projectId", "kivnes", "ticketProvider", "jira", "ticketKeyPattern", "^KIVNES-[0-9]+$")));
        return projects;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

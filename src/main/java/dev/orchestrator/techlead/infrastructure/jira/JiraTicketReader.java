package dev.orchestrator.techlead.infrastructure.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orchestrator.techlead.application.ticket.ReadTicket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JiraTicketReader implements ReadTicket {
    private static final String FIELDS = "summary,description,status,issuetype,priority,labels,components";

    private final ObjectMapper json;
    private final Path projects;
    private final HttpClient client;
    private final URI baseUrl;
    private final String authorization;

    public JiraTicketReader(ObjectMapper json, Path projects, HttpClient client, URI baseUrl,
                            String email, String apiToken) {
        if (email == null || email.isBlank() || apiToken == null || apiToken.isBlank()) {
            throw new IllegalArgumentException("MISSING_JIRA_CREDENTIALS");
        }
        this.json = json;
        this.projects = projects;
        this.client = client;
        this.baseUrl = requireBaseUrl(baseUrl);
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (email + ":" + apiToken).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Map<String, Object> read(String projectId, String ticketKey) throws Exception {
        validateInput(projectId, ticketKey);
        var profile = json.readTree(Files.readString(projects.resolve(projectId + ".json")));
        validateProject(profile, projectId, ticketKey);

        var request = HttpRequest.newBuilder(issueUri(ticketKey))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Authorization", authorization)
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("JIRA_HTTP_" + response.statusCode());
        }
        return normalizeTicket(json.readTree(response.body()), ticketKey);
    }

    private URI issueUri(String ticketKey) {
        return baseUrl.resolve("rest/api/3/issue/" + ticketKey + "?fields=" + FIELDS);
    }

    private static URI requireBaseUrl(URI value) {
        if (value == null || value.getScheme() == null || value.getHost() == null
                || !(value.getScheme().equals("https") || value.getScheme().equals("http"))) {
            throw new IllegalArgumentException("INVALID_JIRA_BASE_URL");
        }
        return value.toString().endsWith("/") ? value : URI.create(value + "/");
    }

    private static void validateInput(String projectId, String ticketKey) {
        if (projectId == null || !projectId.matches("[a-z0-9-]{1,64}")
                || ticketKey == null || !ticketKey.matches("[A-Z][A-Z0-9]*-[0-9]+")) {
            throw new IllegalArgumentException("INVALID_TICKET_INPUT");
        }
    }

    private static void validateProject(JsonNode profile, String projectId, String ticketKey) {
        if (!projectId.equals(profile.path("projectId").asText())
                || !"jira".equals(profile.path("ticketProvider").asText())
                || !ticketKey.matches(profile.path("ticketKeyPattern").asText())) {
            throw new IllegalArgumentException("TICKET_PROJECT_MISMATCH");
        }
    }

    private Map<String, Object> normalizeTicket(JsonNode issue, String expectedKey) {
        var fields = issue.path("fields");
        var key = issue.path("key").asText();
        if (!expectedKey.equals(key)) throw new IllegalArgumentException("JIRA_TICKET_KEY_MISMATCH");

        var ticket = new LinkedHashMap<String, Object>();
        ticket.put("key", key);
        ticket.put("summary", fields.path("summary").asText());
        ticket.put("description", adfToPlainText(fields.path("description")));
        ticket.put("status", fields.path("status").path("name").asText());
        ticket.put("issueType", fields.path("issuetype").path("name").asText());
        ticket.put("priority", fields.path("priority").path("name").asText());
        ticket.put("labels", strings(fields.path("labels")));
        ticket.put("components", componentNames(fields.path("components")));
        ticket.put("url", baseUrl.resolve("browse/" + key).toString());
        return ticket;
    }

    private static List<String> strings(JsonNode nodes) {
        var values = new ArrayList<String>();
        if (nodes.isArray()) for (var node : nodes) if (node.isTextual()) values.add(node.asText());
        return values;
    }

    private static List<String> componentNames(JsonNode components) {
        var values = new ArrayList<String>();
        if (components.isArray()) for (var component : components) {
            var name = component.path("name").asText();
            if (!name.isBlank()) values.add(name);
        }
        return values;
    }

    private static String adfToPlainText(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return "";
        var parts = new ArrayList<String>();
        if (node.hasNonNull("text")) parts.add(node.path("text").asText());
        if (node.path("content").isArray()) for (var child : node.path("content")) {
            var text = adfToPlainText(child);
            if (!text.isBlank()) parts.add(text);
        }
        return String.join("\n", parts);
    }
}

package dev.orchestrator.techlead.application.ticket;

import java.util.Map;

public interface ReadTicket {
    Map<String, Object> read(String projectId, String ticketKey) throws Exception;
}

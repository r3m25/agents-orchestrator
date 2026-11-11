package com.agents.orchestrator.application.ticket;

import java.util.Map;

public interface ReadTicket {
    Map<String, Object> read(String projectId, String ticketKey) throws Exception;
}

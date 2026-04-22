package de.mhus.spring7.aiassistant.storage;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionMeta(String id, String createdAt, String lastActiveAt,
                          int messageCount, int runCount, String currentPlanName) {}

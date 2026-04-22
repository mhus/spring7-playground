package de.mhus.spring7.aiassistant.plan;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineStep(
        String type,
        String name,
        List<String> dependsOn,
        String systemPrompt,
        String outputSchema,
        Boolean storeInRag,
        Boolean useRag,
        PipelineStep producer,
        PipelineStep itemAgent,
        PipelineStep collector
) {
    public boolean isAgent() {
        return "agent".equalsIgnoreCase(type);
    }

    public boolean isForEach() {
        return "forEach".equalsIgnoreCase(type);
    }

    public boolean shouldStoreInRag() {
        return Boolean.TRUE.equals(storeInRag);
    }

    public boolean shouldUseRag() {
        return Boolean.TRUE.equals(useRag);
    }
}

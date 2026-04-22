package de.mhus.spring7.aiplan;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A step in the pipeline. Flat discriminated union on `type`.
 * <ul>
 *   <li>{@code type="agent"}: single LLM call with {@code systemPrompt}.</li>
 *   <li>{@code type="forEach"}: {@code producer} yields a list, {@code itemAgent} runs per item,
 *       {@code collector} optionally merges the item outputs.</li>
 * </ul>
 * Placeholders (unused for now, populated in later steps): {@code dependsOn} (DAG),
 * {@code outputSchema} (structured flows), {@code storeInRag} (RAG shared memory).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineStep(
        String type,
        String name,
        List<String> dependsOn,
        String systemPrompt,
        String outputSchema,
        Boolean storeInRag,
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
}

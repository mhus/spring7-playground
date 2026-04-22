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
 * Per-agent RAG flags:
 * <ul>
 *   <li>{@code storeInRag=true}: after the call, write the agent's output into the shared vector store.</li>
 *   <li>{@code useRag=true}: before the call, retrieve top-K chunks from the shared vector store and
 *       inject them as context into the user prompt.</li>
 * </ul>
 * Placeholders (unused for now): {@code dependsOn} (DAG), {@code outputSchema} (structured flows).
 */
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

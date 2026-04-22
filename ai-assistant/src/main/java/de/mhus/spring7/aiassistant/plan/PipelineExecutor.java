package de.mhus.spring7.aiassistant.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.storage.TokenTracker;
import de.mhus.spring7.aiassistant.tools.AgentTool;

@Component
public class PipelineExecutor {

    private static final int RAG_TOP_K = 4;

    private static final String ITEM_PRODUCER_HINT = """

            Return ONLY the list items, one per line. No numbering, no bullets, no markdown, no preamble.
            Each line becomes an independent task for the next agent.
            """;

    private final ChatClient executor;
    private final SharedRagStore rag;
    private final TokenTracker tokens;

    public PipelineExecutor(ChatClient.Builder builder, SharedRagStore rag,
                            List<AgentTool> tools, TokenTracker tokens) {
        this.rag = rag;
        this.tokens = tokens;
        this.executor = tools.isEmpty()
                ? builder.build()
                : builder.defaultTools(tools.toArray()).build();
    }

    public Object execute(Plan plan, ExecutionContext ctx) {
        Object input = ctx.originalProblem();
        Object output = null;
        int total = plan.steps().size();
        int i = 1;
        for (PipelineStep step : plan.steps()) {
            IO.println("");
            IO.println("─── step " + i + "/" + total + ": " + step.name() + " (" + step.type() + flags(step) + ") ───");
            output = executeStep(step, input);
            ctx.put(step.name(), output);
            input = output;
            i++;
        }
        return output;
    }

    private Object executeStep(PipelineStep step, Object input) {
        if (step.isForEach()) {
            return runForEach(step, toText(input));
        }
        if (step.isAgent()) {
            return runAgent(step, toText(input));
        }
        if (step.systemPrompt() != null && !step.systemPrompt().isBlank()) {
            IO.println("  [unknown type '" + step.type() + "' — running as agent]");
            return runAgent(step, toText(input));
        }
        throw new IllegalArgumentException("unknown step type '" + step.type() + "' and no systemPrompt");
    }

    private String runAgent(PipelineStep step, String input) {
        String userInput = step.shouldUseRag() ? augmentWithRag(input) : input;
        long t0 = System.currentTimeMillis();
        ChatResponse resp = executor.prompt()
                .system(step.systemPrompt())
                .user(userInput)
                .call()
                .chatResponse();
        tokens.record("pipeline:" + step.name(), resp.getMetadata().getUsage());
        String output = resp.getResult().getOutput().getText();
        long ms = System.currentTimeMillis() - t0;
        IO.println("[" + step.name() + " done in " + ms + " ms]");
        IO.println(output);
        if (step.shouldStoreInRag()) {
            int n = rag.addLines(output, step.name());
            if (n == 0) {
                n = rag.add(output, step.name());
            }
            IO.println("  [stored " + n + " chunks in RAG from " + step.name() + "]");
        }
        return output;
    }

    private List<String> runForEach(PipelineStep step, String input) {
        PipelineStep producer = requireInner(step.producer(), step.name(), "producer");
        PipelineStep itemAgent = requireInner(step.itemAgent(), step.name(), "itemAgent");

        IO.println("  ▸ producer: " + producer.name() + flags(producer));
        String producerInput = producer.shouldUseRag() ? augmentWithRag(input) : input;
        String producerSystem = producer.systemPrompt() + ITEM_PRODUCER_HINT;
        ChatResponse producerResp = executor.prompt()
                .system(producerSystem)
                .user(producerInput)
                .call()
                .chatResponse();
        tokens.record("pipeline:" + producer.name(), producerResp.getMetadata().getUsage());
        String producerOut = producerResp.getResult().getOutput().getText();
        List<String> items = Arrays.stream(producerOut.split("\\R"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        IO.println("  [producer yielded " + items.size() + " items]");
        if (producer.shouldStoreInRag()) {
            int n = rag.addLines(producerOut, producer.name());
            IO.println("  [stored " + n + " chunks in RAG from " + producer.name() + "]");
        }

        List<String> itemOutputs = new ArrayList<>();
        int k = 1;
        for (String item : items) {
            IO.println("  ▸ item " + k + "/" + items.size() + flags(itemAgent) + ": " + preview(item));
            String itemInput = itemAgent.shouldUseRag() ? augmentWithRag(item) : item;
            long t0 = System.currentTimeMillis();
            ChatResponse itemResp = executor.prompt()
                    .system(itemAgent.systemPrompt())
                    .user(itemInput)
                    .call()
                    .chatResponse();
            tokens.record("pipeline:" + itemAgent.name(), itemResp.getMetadata().getUsage());
            String out = itemResp.getResult().getOutput().getText();
            long ms = System.currentTimeMillis() - t0;
            IO.println("  [" + itemAgent.name() + " #" + k + " done in " + ms + " ms]");
            IO.println(out);
            if (itemAgent.shouldStoreInRag()) {
                int n = rag.add(out, itemAgent.name() + "#" + k);
                IO.println("  [stored " + n + " chunks in RAG from " + itemAgent.name() + " #" + k + "]");
            }
            itemOutputs.add(out);
            k++;
        }

        if (step.collector() != null) {
            IO.println("  ▸ collector: " + step.collector().name() + flags(step.collector()));
            String merged = String.join("\n\n---\n\n", itemOutputs);
            return List.of(runAgent(step.collector(), merged));
        }
        return itemOutputs;
    }

    private String augmentWithRag(String input) {
        List<Document> hits = rag.query(input, RAG_TOP_K);
        if (hits.isEmpty()) {
            return input;
        }
        String context = hits.stream()
                .map(d -> "- " + d.getText())
                .collect(Collectors.joining("\n"));
        IO.println("  [RAG matched " + hits.size() + " chunks]");
        return "Context from knowledge store:\n" + context + "\n\nUser input:\n" + input;
    }

    private static String flags(PipelineStep step) {
        StringBuilder sb = new StringBuilder();
        if (step.shouldUseRag()) sb.append(" +useRag");
        if (step.shouldStoreInRag()) sb.append(" +storeInRag");
        return sb.toString();
    }

    private static PipelineStep requireInner(PipelineStep inner, String parent, String role) {
        if (inner == null) {
            throw new IllegalArgumentException("forEach step '" + parent + "' is missing '" + role + "'");
        }
        return inner;
    }

    private static String toText(Object value) {
        if (value instanceof String s) return s;
        if (value instanceof List<?> l) return String.join("\n\n---\n\n", l.stream().map(String::valueOf).toList());
        return String.valueOf(value);
    }

    private static String preview(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
    }
}

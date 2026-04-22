package de.mhus.spring7.aiplan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class PipelineExecutor {

    private static final String ITEM_PRODUCER_HINT = """

            Return ONLY the list items, one per line. No numbering, no bullets, no markdown, no preamble.
            Each line becomes an independent task for the next agent.
            """;

    private final ChatClient executor;

    public PipelineExecutor(ChatClient.Builder builder) {
        this.executor = builder.build();
    }

    public Object execute(Plan plan, ExecutionContext ctx) {
        Object input = ctx.originalProblem();
        Object output = null;
        int total = plan.steps().size();
        int i = 1;
        for (PipelineStep step : plan.steps()) {
            IO.println("");
            IO.println("─── step " + i + "/" + total + ": " + step.name() + " (" + step.type() + ") ───");
            output = executeStep(step, input, ctx);
            ctx.put(step.name(), output);
            input = output;
            i++;
        }
        return output;
    }

    private Object executeStep(PipelineStep step, Object input, ExecutionContext ctx) {
        if (step.isAgent()) {
            return runAgent(step, toText(input));
        }
        if (step.isForEach()) {
            return runForEach(step, toText(input));
        }
        throw new IllegalArgumentException("unknown step type: " + step.type());
    }

    private String runAgent(PipelineStep step, String input) {
        long t0 = System.currentTimeMillis();
        String output = executor.prompt()
                .system(step.systemPrompt())
                .user(input)
                .call()
                .content();
        long ms = System.currentTimeMillis() - t0;
        IO.println("[" + step.name() + " done in " + ms + " ms]");
        IO.println(output);
        return output;
    }

    private List<String> runForEach(PipelineStep step, String input) {
        PipelineStep producer = requireInner(step.producer(), step.name(), "producer");
        PipelineStep itemAgent = requireInner(step.itemAgent(), step.name(), "itemAgent");

        IO.println("  ▸ producer: " + producer.name());
        String producerSystem = producer.systemPrompt() + ITEM_PRODUCER_HINT;
        String producerOut = executor.prompt()
                .system(producerSystem)
                .user(input)
                .call()
                .content();
        List<String> items = Arrays.stream(producerOut.split("\\R"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        IO.println("  [producer yielded " + items.size() + " items]");

        List<String> itemOutputs = new ArrayList<>();
        int k = 1;
        for (String item : items) {
            IO.println("  ▸ item " + k + "/" + items.size() + ": " + preview(item));
            long t0 = System.currentTimeMillis();
            String out = executor.prompt()
                    .system(itemAgent.systemPrompt())
                    .user(item)
                    .call()
                    .content();
            long ms = System.currentTimeMillis() - t0;
            IO.println("  [" + itemAgent.name() + " #" + k + " done in " + ms + " ms]");
            IO.println(out);
            itemOutputs.add(out);
            k++;
        }

        if (step.collector() != null) {
            IO.println("  ▸ collector: " + step.collector().name());
            String merged = String.join("\n\n---\n\n", itemOutputs);
            return List.of(runAgent(step.collector(), merged));
        }
        return itemOutputs;
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

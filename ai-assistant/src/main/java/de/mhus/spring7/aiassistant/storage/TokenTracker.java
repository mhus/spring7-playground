package de.mhus.spring7.aiassistant.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

/**
 * Counts tokens per source (e.g. "say", "planner", "pipeline:<step>", "orchestrate").
 * Values come from {@link org.springframework.ai.chat.model.ChatResponse#getMetadata()}.getUsage().
 * Note: token counts are read from the FINAL ChatResponse per call. With tool-calling enabled,
 * Spring AI issues multiple LLM calls under the hood; those intermediate usages may or may not
 * be aggregated depending on the model. Treat the numbers as a lower bound.
 */
@Component
public class TokenTracker {

    private final Map<String, Stats> stats = new LinkedHashMap<>();

    public synchronized void record(String source, Usage usage) {
        if (usage == null) return;
        Stats s = stats.computeIfAbsent(source, k -> new Stats());
        if (usage.getPromptTokens() != null) s.prompt.addAndGet(usage.getPromptTokens());
        if (usage.getCompletionTokens() != null) s.completion.addAndGet(usage.getCompletionTokens());
        if (usage.getTotalTokens() != null) s.total.addAndGet(usage.getTotalTokens());
        s.calls.incrementAndGet();
    }

    public synchronized void reset() {
        stats.clear();
    }

    public synchronized String summary() {
        if (stats.isEmpty()) return "no tokens recorded yet";
        long total = 0, calls = 0;
        for (Stats s : stats.values()) { total += s.total.get(); calls += s.calls.get(); }
        return total + " tokens across " + calls + " call(s), " + stats.size() + " source(s)";
    }

    @Command(name = "tokens", group = "Session", description = "Show token usage per source since last reset.")
    public String show() {
        if (stats.isEmpty()) return "(no tokens recorded)";
        long totalPrompt = 0, totalCompletion = 0, totalAll = 0, totalCalls = 0;
        StringBuilder sb = new StringBuilder(
                String.format("%-25s %8s %8s %8s %8s%n", "source", "calls", "prompt", "compl", "total"));
        synchronized (this) {
            for (var e : stats.entrySet()) {
                Stats s = e.getValue();
                sb.append(String.format("%-25s %8d %8d %8d %8d%n",
                        truncate(e.getKey(), 25), s.calls.get(),
                        s.prompt.get(), s.completion.get(), s.total.get()));
                totalPrompt += s.prompt.get();
                totalCompletion += s.completion.get();
                totalAll += s.total.get();
                totalCalls += s.calls.get();
            }
        }
        sb.append(String.format("%-25s %8d %8d %8d %8d%n",
                "TOTAL", totalCalls, totalPrompt, totalCompletion, totalAll));
        return sb.toString();
    }

    @Command(name = "tokens-reset", group = "Session", description = "Reset token counters.")
    public String resetCommand() {
        reset();
        return "token counters reset";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static class Stats {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong prompt = new AtomicLong();
        final AtomicLong completion = new AtomicLong();
        final AtomicLong total = new AtomicLong();
    }
}

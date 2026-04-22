package de.mhus.spring7.aiassistant.plan;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

/**
 * Cross-cutting user-defined preferences (language, tone, style conventions).
 * Used by both the planner and the sub-task executor so a single `set sprache: deutsch`
 * applies to every indirect LLM invocation.
 */
@Service
public class BaseSettings {

    private final List<String> items = new CopyOnWriteArrayList<>();

    public void add(String setting) { items.add(setting.strip()); }
    public int clear() { int n = items.size(); items.clear(); return n; }
    public List<String> all() { return List.copyOf(items); }
    public boolean isEmpty() { return items.isEmpty(); }
    public int size() { return items.size(); }

    public String renderBlock() {
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Base settings (always apply):\n");
        for (String s : items) sb.append("- ").append(s).append("\n");
        return sb.toString();
    }
}

package de.mhus.spring7.aiassistant.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import de.mhus.spring7.aiassistant.storage.StorageService;

/**
 * Global, cross-session memory. A list of short facts the agent (or user) wants to keep
 * indefinitely: user preferences, project invariants, long-lived reminders.
 * Persisted in {@code data/project/memory.json}. Always visible in the system prompt.
 *
 * Conceptually complementary to:
 * <ul>
 *   <li>AGENT.md — curated project documentation, hand-edited (or via file tools).</li>
 *   <li>Session pins — short-lived facts for one conversation only.</li>
 * </ul>
 * Use this for things that are too dynamic/small for AGENT.md but too important to forget
 * when the session ends.
 */
@Service
public class GlobalMemoryService {

    private final StorageService storage;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final List<String> items = new CopyOnWriteArrayList<>();

    public GlobalMemoryService(StorageService storage) {
        this.storage = storage;
    }

    @PostConstruct
    void init() {
        reload();
    }

    public synchronized void add(String fact) {
        if (fact == null || fact.isBlank()) return;
        items.add(fact.strip());
        persist();
    }

    public synchronized boolean remove(int oneBased) {
        int idx = oneBased - 1;
        if (idx < 0 || idx >= items.size()) return false;
        items.remove(idx);
        persist();
        return true;
    }

    public synchronized int clear() {
        int n = items.size();
        items.clear();
        persist();
        return n;
    }

    public void reload() {
        items.clear();
        items.addAll(load());
    }

    public List<String> all() { return List.copyOf(items); }
    public int size() { return items.size(); }
    public boolean isEmpty() { return items.isEmpty(); }

    public String renderBlock() {
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Remembered facts (global across all sessions):\n");
        for (String f : items) sb.append("- ").append(f).append("\n");
        return sb.toString();
    }

    private Path file() {
        return storage.projectDir().resolve("memory.json");
    }

    @SuppressWarnings("unchecked")
    private List<String> load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return List.of();
        try {
            return new ArrayList<>((List<String>) json.readValue(Files.readString(f, StandardCharsets.UTF_8), List.class));
        } catch (IOException e) {
            return List.of();
        }
    }

    private void persist() {
        try {
            Files.writeString(file(), json.writeValueAsString(items), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

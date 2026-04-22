package de.mhus.spring7.aiassistant.plan;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Service;

import de.mhus.spring7.aiassistant.storage.StorageService;

/**
 * Session-scoped user preferences (language, tone, style). Injected into planner, sub-task
 * and main-chat system prompts. Persisted in {@code data/sessions/<uuid>/base-settings.json}
 * so {@code set "sprache: deutsch"} survives restarts and resume.
 */
@Service
public class BaseSettings {

    private final StorageService storage;
    private final List<String> items = new CopyOnWriteArrayList<>();

    public BaseSettings(StorageService storage) {
        this.storage = storage;
    }

    @PostConstruct
    void init() {
        reloadFromStorage();
    }

    public void add(String setting) {
        if (setting == null || setting.isBlank()) return;
        items.add(setting.strip());
        persist();
    }

    public int clear() {
        int n = items.size();
        items.clear();
        persist();
        return n;
    }

    public List<String> all() { return List.copyOf(items); }
    public boolean isEmpty() { return items.isEmpty(); }
    public int size() { return items.size(); }

    public String renderBlock() {
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Base settings (always apply):\n");
        for (String s : items) sb.append("- ").append(s).append("\n");
        return sb.toString();
    }

    public void reloadFromStorage() {
        items.clear();
        items.addAll(storage.loadBaseSettings());
    }

    private void persist() {
        storage.saveBaseSettings(items);
    }
}

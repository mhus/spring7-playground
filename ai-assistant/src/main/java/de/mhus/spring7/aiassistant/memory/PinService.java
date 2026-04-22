package de.mhus.spring7.aiassistant.memory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Service;

import de.mhus.spring7.aiassistant.storage.StorageService;

/**
 * Per-session pinned facts the agent must always remember — injected into the system prompt
 * on every call, bypassing the sliding-window limitation of chat memory. Persisted in
 * {@code data/sessions/<uuid>/pins.json}.
 */
@Service
public class PinService {

    private final StorageService storage;
    private final List<String> pins = new CopyOnWriteArrayList<>();

    public PinService(StorageService storage) {
        this.storage = storage;
    }

    @PostConstruct
    void init() {
        reloadFromStorage();
    }

    public void add(String text) {
        if (text == null || text.isBlank()) return;
        pins.add(text.strip());
        persist();
    }

    public boolean remove(int oneBased) {
        int idx = oneBased - 1;
        if (idx < 0 || idx >= pins.size()) return false;
        pins.remove(idx);
        persist();
        return true;
    }

    public int clear() {
        int n = pins.size();
        pins.clear();
        persist();
        return n;
    }

    public List<String> all() { return List.copyOf(pins); }
    public int size() { return pins.size(); }
    public boolean isEmpty() { return pins.isEmpty(); }

    public String renderBlock() {
        if (pins.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Pinned facts (always remember, never contradict):\n");
        for (String p : pins) sb.append("- ").append(p).append("\n");
        return sb.toString();
    }

    public void reloadFromStorage() {
        pins.clear();
        pins.addAll(storage.loadPins());
    }

    private void persist() {
        storage.savePins(pins);
    }
}

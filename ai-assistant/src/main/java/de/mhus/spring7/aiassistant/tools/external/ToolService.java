package de.mhus.spring7.aiassistant.tools.external;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.mhus.spring7.aiassistant.storage.StorageService;

/**
 * Loads external tool configurations from {@code data/project/tools/*.json}, dispatches each
 * to the appropriate {@link ToolFlavor}, starts the resulting {@link ToolInstance}s, and keeps
 * a registry for discovery and invocation.
 *
 * Lifecycle: scan on startup; {@code reload()} stops current instances and re-scans;
 * {@code @PreDestroy} stops everything.
 */
@Service
public class ToolService {

    private final Map<String, ToolFlavor> flavorsByType;
    private final Map<String, ToolInstance> instanceByTool = new ConcurrentHashMap<>();
    private final Map<String, ToolDescriptor> descByTool = new ConcurrentHashMap<>();
    private final List<String> lastLoadErrors = new ArrayList<>();
    private final StorageService storage;
    private final ObjectMapper json = new ObjectMapper();

    public ToolService(List<ToolFlavor> flavors, StorageService storage) {
        Map<String, ToolFlavor> byType = new HashMap<>();
        for (ToolFlavor f : flavors) {
            byType.put(f.type().toLowerCase(Locale.ROOT), f);
        }
        this.flavorsByType = byType;
        this.storage = storage;
    }

    @PostConstruct
    void init() {
        reload();
    }

    @PreDestroy
    void shutdownAll() {
        for (ToolInstance i : new ArrayList<>(instanceByTool.values())) {
            try { i.stop(); } catch (Exception ignored) {}
        }
        instanceByTool.clear();
        descByTool.clear();
    }

    public synchronized String reload() {
        // stop everything currently loaded
        for (ToolInstance i : new ArrayList<>(new java.util.HashSet<>(instanceByTool.values()))) {
            try { i.stop(); } catch (Exception ignored) {}
        }
        instanceByTool.clear();
        descByTool.clear();
        lastLoadErrors.clear();

        Path dir = storage.projectDir().resolve("tools");
        if (!Files.isDirectory(dir)) {
            return "no tools dir (" + dir.toAbsolutePath() + ") — create it and add *.json tool configs";
        }
        int files = 0, loaded = 0, failed = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                files++;
                try {
                    loadOne(p);
                    loaded++;
                } catch (Exception e) {
                    failed++;
                    lastLoadErrors.add(p.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            return "ERROR scanning " + dir + ": " + e.getMessage();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("scanned ").append(files).append(" files: ")
          .append(loaded).append(" loaded, ").append(failed).append(" failed\n");
        sb.append("exposed tools: ").append(descByTool.size()).append("\n");
        for (String err : lastLoadErrors) sb.append("  ERROR ").append(err).append("\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void loadOne(Path p) throws IOException {
        Map<String, Object> cfg = json.readValue(p.toFile(), Map.class);
        String type = asString(cfg.get("type"));
        if (type == null || type.isBlank()) throw new IllegalArgumentException("missing 'type'");
        ToolFlavor flavor = flavorsByType.get(type.toLowerCase(Locale.ROOT));
        if (flavor == null) {
            throw new IllegalArgumentException("no flavor registered for type '" + type
                    + "' (have: " + flavorsByType.keySet() + ")");
        }
        ToolInstance inst = flavor.create(cfg);
        inst.start();
        for (ToolDescriptor d : inst.descriptors()) {
            if (descByTool.containsKey(d.name())) {
                lastLoadErrors.add(p.getFileName() + ": duplicate tool name '" + d.name() + "' (skipped)");
                continue;
            }
            descByTool.put(d.name(), d);
            instanceByTool.put(d.name(), inst);
        }
    }

    public List<ToolDescriptor> search(String query) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>(descByTool.values());
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<ToolDescriptor> hits = new ArrayList<>();
        for (ToolDescriptor d : descByTool.values()) {
            if (d.name().toLowerCase(Locale.ROOT).contains(q)
                    || (d.description() != null && d.description().toLowerCase(Locale.ROOT).contains(q))) {
                hits.add(d);
            }
        }
        return hits;
    }

    public ToolDescriptor describe(String name) {
        return descByTool.get(name);
    }

    public String invoke(String name, String jsonArgs) {
        ToolInstance inst = instanceByTool.get(name);
        if (inst == null) return "ERROR: tool not found: " + name;
        try {
            return inst.invoke(name, jsonArgs == null ? "{}" : jsonArgs);
        } catch (Exception e) {
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public int size() { return descByTool.size(); }

    private static String asString(Object o) { return o == null ? null : o.toString(); }
}

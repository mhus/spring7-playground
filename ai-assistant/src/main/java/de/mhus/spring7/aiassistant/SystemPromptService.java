package de.mhus.spring7.aiassistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import de.mhus.spring7.aiassistant.memory.GlobalMemoryService;
import de.mhus.spring7.aiassistant.memory.PinService;
import de.mhus.spring7.aiassistant.plan.BaseSettings;

/**
 * Composes the system prompt for the main chat on every invocation.
 * <p>
 * Layers (in order):
 * <ol>
 *   <li>{@code data/project/system-prompt.md} if present — <b>replaces</b> the default; else
 *       the bundled {@code classpath:system.md}; else a minimal inline fallback.</li>
 *   <li>{@code AGENT.md} in the working directory — appended as "Project context" so any
 *       project can give the agent team-wide, versioned guidance.</li>
 *   <li>Global remembered facts (from {@link GlobalMemoryService}).</li>
 *   <li>Session pins (from {@link PinService}).</li>
 *   <li>Base settings (from {@link BaseSettings}).</li>
 * </ol>
 * Reloaded per call — no restart needed to pick up edits to any layer.
 */
@Service
public class SystemPromptService {

    private static final String INLINE_FALLBACK =
            "You are a proactive assistant. Use tools. Prefer action over prose.";

    private final BaseSettings baseSettings;
    private final PinService pinService;
    private final GlobalMemoryService globalMemory;

    public SystemPromptService(BaseSettings baseSettings, PinService pinService,
                               GlobalMemoryService globalMemory) {
        this.baseSettings = baseSettings;
        this.pinService = pinService;
        this.globalMemory = globalMemory;
    }

    public String get() {
        StringBuilder sb = new StringBuilder();
        sb.append(loadPersona());

        String agentMd = loadFile(Path.of("AGENT.md"));
        if (agentMd != null && !agentMd.isBlank()) {
            sb.append("\n\n## Project context (AGENT.md)\n").append(agentMd);
        }

        if (!globalMemory.isEmpty()) {
            sb.append("\n\n").append(globalMemory.renderBlock());
        }

        if (!pinService.isEmpty()) {
            sb.append("\n\n").append(pinService.renderBlock());
        }

        if (!baseSettings.isEmpty()) {
            sb.append("\n\n").append(baseSettings.renderBlock());
        }
        return sb.toString();
    }

    private String loadPersona() {
        String override = loadFile(Path.of("data", "project", "system-prompt.md"));
        if (override != null && !override.isBlank()) return override;

        String bundled = loadClasspath("system.md");
        if (bundled != null && !bundled.isBlank()) return bundled;

        return INLINE_FALLBACK;
    }

    private String loadFile(Path p) {
        if (!Files.isRegularFile(p)) return null;
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private String loadClasspath(String name) {
        try {
            ClassPathResource r = new ClassPathResource(name);
            if (!r.exists()) return null;
            return r.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}

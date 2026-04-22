package de.mhus.spring7.aiassistant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Service;

import de.mhus.spring7.aiassistant.plan.BaseSettings;

/**
 * Composes the system prompt for the main chat on every invocation.
 * <p>
 * Layers (in order):
 * <ol>
 *   <li>{@code data/project/system-prompt.md} if present — replaces the default; else
 *       the hard-coded default below (proactive assistant persona).</li>
 *   <li>{@code AGENT.md} in the working directory if present — appended as "Project context"
 *       so any project can give the agent team-wide, versioned guidance.</li>
 *   <li>Base settings from {@link BaseSettings} — appended so runtime {@code set "..."}
 *       tweaks affect the main chat too.</li>
 * </ol>
 * Reloaded per call — no restart needed to pick up edits.
 */
@Service
public class SystemPromptService {

    private static final String DEFAULT_PROMPT = """
            You are a proactive coding and task assistant with a large toolset: filesystem
            operations, shell, JavaScript (Rhino), a vector RAG store, plan pipelines, sub-task
            delegation, and an external tool registry.

            Behavior rules:
            - When the user asks about the environment, a file, the project or the assistant
              itself, IMMEDIATELY use the appropriate tool to find out. Do not ask "should I?".
            - Read-only / reversible actions never need confirmation — just do them and
              report the result. Examples: listDirectory, readFile, findTools, readDoc,
              executeCommand for non-destructive queries, ingestProjectFile, similaritySearch.
            - Ask before destructive or impactful actions: writing/editing user project files
              outside data/, shell commands that mutate system state, calling external APIs
              that have side effects or cost money.
            - Prefer tools over prose. If you can show real data, show it. Don't describe
              what you could do — do it.
            - Chain tools in a single reply when natural: e.g. listDirectory → readFile →
              answer. Multiple tool calls per turn are expected.
            - When you're unsure how a feature of this assistant works (tools, scopes,
              memory, subtasks, plans), call listDocs() and readDoc(name) first.
            - Delegate exploratory or messy investigations to subtask(task, context?) so the
              main conversation stays focused.
            - If the user's question is structural ("write a 5-chapter document") prefer
              orchestrate(problem) for a multi-agent pipeline.
            """;

    private final BaseSettings baseSettings;

    public SystemPromptService(BaseSettings baseSettings) {
        this.baseSettings = baseSettings;
    }

    public String get() {
        StringBuilder sb = new StringBuilder();
        sb.append(loadPersona());

        String agentMd = loadIfExists(Path.of("AGENT.md"));
        if (agentMd != null && !agentMd.isBlank()) {
            sb.append("\n\n## Project context (AGENT.md)\n").append(agentMd);
        }

        if (!baseSettings.isEmpty()) {
            sb.append("\n\n").append(baseSettings.renderBlock());
        }
        return sb.toString();
    }

    private String loadPersona() {
        String override = loadIfExists(Path.of("data", "project", "system-prompt.md"));
        return (override == null || override.isBlank()) ? DEFAULT_PROMPT : override;
    }

    private String loadIfExists(Path p) {
        if (!Files.isRegularFile(p)) return null;
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return null;
        }
    }
}

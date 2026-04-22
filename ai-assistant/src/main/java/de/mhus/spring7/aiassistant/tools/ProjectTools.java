package de.mhus.spring7.aiassistant.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.plan.SharedRagStore;
import de.mhus.spring7.aiassistant.storage.StorageService;

/**
 * Project-wide, session-independent storage. Persisted under {@code data/project/} at the
 * working directory root. Survives session switches — use this for stable knowledge about
 * the project: design notes, domain facts, configuration hints, references.
 *
 * The RAG store itself is session-scoped, so {@code ingestProjectFile} writes into the active
 * session's RAG. On a new session, re-ingest if you need the content queryable again.
 */
@Component
public class ProjectTools implements AgentTool {

    private final StorageService storage;
    private final SharedRagStore rag;

    public ProjectTools(StorageService storage, SharedRagStore rag) {
        this.storage = storage;
        this.rag = rag;
    }

    @Tool(description = """
            Create or overwrite a project-wide file. Relative paths like 'notes/api.md' or
            'decisions/2026-naming.txt'. Content survives across sessions. Parent dirs auto-created.
            """)
    public String writeProjectFile(
            @ToolParam(description = "Relative path inside data/project/.") String relativePath,
            @ToolParam(description = "Full file content.") String content) {
        Path p;
        try { p = resolve(relativePath); }
        catch (IllegalArgumentException e) { return "ERROR: " + e.getMessage(); }
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "OK wrote " + relativePath + " at " + p.toAbsolutePath();
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Read a project-wide file.")
    public String readProjectFile(
            @ToolParam(description = "Relative path inside data/project/.") String relativePath) {
        try {
            Path p = resolve(relativePath);
            if (!Files.isRegularFile(p)) return "ERROR: not a file: " + relativePath;
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Replace one occurrence of oldText with newText inside a project file. Same rules as editFile.")
    public String editProjectFile(
            @ToolParam(description = "Relative path.") String relativePath,
            @ToolParam(description = "Exact text to find.") String oldText,
            @ToolParam(description = "Replacement.") String newText) {
        try {
            Path p = resolve(relativePath);
            String content = Files.readString(p, StandardCharsets.UTF_8);
            int first = content.indexOf(oldText);
            if (first < 0) return "ERROR: oldText not found";
            int second = content.indexOf(oldText, first + oldText.length());
            if (second >= 0) return "ERROR: oldText appears multiple times";
            String updated = content.substring(0, first) + (newText == null ? "" : newText)
                    + content.substring(first + oldText.length());
            Files.writeString(p, updated, StandardCharsets.UTF_8);
            return "OK replaced 1 occurrence in " + relativePath;
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Delete a project-wide file.")
    public String deleteProjectFile(
            @ToolParam(description = "Relative path.") String relativePath) {
        try {
            Path p = resolve(relativePath);
            boolean ok = Files.deleteIfExists(p);
            return ok ? "OK deleted " + relativePath : "(not found: " + relativePath + ")";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "List all project-wide files (recursive). Returns relative paths, one per line.")
    public String listProjectFiles() {
        Path root = storage.projectDir();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .sorted()
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = """
            Absolute filesystem path of the project-wide data directory. Use this to scope
            shell-based searches: e.g. executeCommand("grep -rn PATTERN <projectPath>").
            """)
    public String getProjectPath() {
        return storage.projectDir().toAbsolutePath().toString();
    }

    @Tool(description = """
            Ingest a project file into the current session's RAG vector store. The file is
            chunked by token splitter and embedded. Later `say` or `ask` queries will retrieve
            relevant chunks. Note: the RAG is session-scoped — on session switch, re-ingest
            if needed.
            """)
    public String ingestProjectFile(
            @ToolParam(description = "Relative path inside data/project/.") String relativePath) {
        try {
            Path p = resolve(relativePath);
            if (!Files.isRegularFile(p)) return "ERROR: not a file: " + relativePath;
            String content = Files.readString(p, StandardCharsets.UTF_8);
            int n = rag.add(content, "project:" + relativePath);
            return "OK ingested " + n + " chunks from " + relativePath + " (RAG total: " + rag.size() + ")";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("empty path");
        }
        Path root = storage.projectDir().toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escape not allowed: " + relativePath);
        }
        return resolved;
    }
}

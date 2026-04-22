package de.mhus.spring7.aiassistant.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.mozilla.javascript.engine.RhinoScriptEngineFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.storage.StorageService;

/**
 * Session-scoped filesystem sandbox at {@code data/sessions/<uuid>/workspace/}. The agent can
 * create, edit and run files here without affecting the rest of the filesystem. All operations
 * take relative paths and are confined to the workspace (no breakout via .. or absolute paths).
 * Workspace persists across restarts and follows the session — resume loads it back.
 */
@Component
public class WorkspaceTools implements AgentTool {

    private final StorageService storage;

    public WorkspaceTools(StorageService storage) {
        this.storage = storage;
    }

    @Tool(description = """
            Create or overwrite a file in the session workspace. Use relative paths like
            'tool.js' or 'utils/math.js'. Parent directories are created automatically.
            """)
    public String writeSessionFile(
            @ToolParam(description = "Relative path inside the workspace.") String relativePath,
            @ToolParam(description = "Full file content.") String content) {
        Path p;
        try { p = resolve(relativePath); }
        catch (IllegalArgumentException e) { return "ERROR: " + e.getMessage(); }
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "OK wrote " + relativePath + " (" + (content == null ? 0 : content.length()) + " chars)";
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Read a file from the session workspace.")
    public String readSessionFile(
            @ToolParam(description = "Relative path inside the workspace.") String relativePath) {
        try {
            Path p = resolve(relativePath);
            if (!Files.isRegularFile(p)) return "ERROR: not a file: " + relativePath;
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Replace one occurrence of oldText with newText inside a workspace file. Same rules as editFile.")
    public String editSessionFile(
            @ToolParam(description = "Relative path inside the workspace.") String relativePath,
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

    @Tool(description = "Delete a workspace file.")
    public String deleteSessionFile(
            @ToolParam(description = "Relative path inside the workspace.") String relativePath) {
        try {
            Path p = resolve(relativePath);
            boolean ok = Files.deleteIfExists(p);
            return ok ? "OK deleted " + relativePath : "(not found: " + relativePath + ")";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "List all files currently in the session workspace (recursive). Returns relative paths, one per line.")
    public String listSessionFiles() {
        Path root = storage.workspaceDir();
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
            Read a JavaScript file from the workspace and execute it via Mozilla Rhino.
            Returns the value of the last expression as a string. Use this to iteratively
            develop and test scripts that you previously wrote with writeSessionFile.
            """)
    public String executeSessionJavaScript(
            @ToolParam(description = "Relative path to a .js file inside the workspace.") String relativePath) {
        try {
            Path p = resolve(relativePath);
            if (!Files.isRegularFile(p)) return "ERROR: not a file: " + relativePath;
            String code = Files.readString(p, StandardCharsets.UTF_8);
            ScriptEngine engine = new RhinoScriptEngineFactory().getScriptEngine();
            try {
                Object result = engine.eval(code);
                return String.valueOf(result);
            } catch (ScriptException e) {
                return "ERROR: " + e.getMessage();
            }
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("empty path");
        }
        Path root = storage.workspaceDir().toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escape not allowed: " + relativePath);
        }
        return resolved;
    }
}

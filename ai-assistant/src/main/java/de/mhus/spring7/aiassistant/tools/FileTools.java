package de.mhus.spring7.aiassistant.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Generic text-file editing. Absolute or working-directory-relative paths accepted.
 * The agent receives {@code "OK"}-style strings on success or {@code "ERROR: …"} on failure,
 * so the LLM can detect problems and react.
 */
@Component
public class FileTools implements AgentTool {

    private static final int DEFAULT_MAX_CHARS = 8_000;

    @Tool(description = """
            List file and subdirectory names inside the given directory (non-recursive, sorted).
            Returns one name per line. Prefer this over `executeCommand("ls …")` for structured output.
            """)
    public String listDirectory(
            @ToolParam(description = "Directory path.") String path) {
        try (Stream<Path> s = Files.list(Path.of(path))) {
            return s.map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .sorted()
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = """
            Read a text file. Optionally pass startLine (1-based) and maxLines to page through
            large files. Default slice returns up to ~8000 characters from the beginning.
            """)
    public String readFile(
            @ToolParam(description = "File path.") String path,
            @ToolParam(description = "1-based start line. Omit to start from line 1.", required = false) Integer startLine,
            @ToolParam(description = "Maximum number of lines to return. Omit to use a default character cap.", required = false) Integer maxLines) {
        try {
            if (startLine != null || maxLines != null) {
                int from = startLine == null ? 1 : Math.max(1, startLine);
                int count = maxLines == null ? Integer.MAX_VALUE : Math.max(0, maxLines);
                try (Stream<String> lines = Files.lines(Path.of(path), StandardCharsets.UTF_8)) {
                    return lines.skip(from - 1)
                            .limit(count)
                            .collect(Collectors.joining("\n"));
                }
            }
            String full = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            return full.length() > DEFAULT_MAX_CHARS
                    ? full.substring(0, DEFAULT_MAX_CHARS) + "\n…[truncated, pass startLine/maxLines to page]"
                    : full;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = """
            Create or overwrite a text file with the exact given content. Creates parent directories
            as needed. Use this for brand-new files or complete rewrites. For targeted changes to
            an existing file, prefer editFile — it is safer and cheaper.
            """)
    public String writeFile(
            @ToolParam(description = "File path.") String path,
            @ToolParam(description = "Full file content.") String content) {
        try {
            Path p = Path.of(path);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "OK wrote " + p.toAbsolutePath() + " (" + (content == null ? 0 : content.length()) + " chars)";
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = """
            Replace ONE occurrence of oldText with newText inside the file. Fails if oldText is
            not found, or if it appears more than once — in that case, include more surrounding
            context in oldText until the match is unique. This is the preferred way to modify
            existing files.
            """)
    public String editFile(
            @ToolParam(description = "File path.") String path,
            @ToolParam(description = "Exact text to find. Must match unique location including whitespace.") String oldText,
            @ToolParam(description = "Text that replaces oldText.") String newText) {
        try {
            Path p = Path.of(path);
            String content = Files.readString(p, StandardCharsets.UTF_8);
            int first = content.indexOf(oldText);
            if (first < 0) return "ERROR: oldText not found in file";
            int second = content.indexOf(oldText, first + oldText.length());
            if (second >= 0) return "ERROR: oldText appears multiple times — add surrounding context to make it unique";
            String updated = content.substring(0, first) + (newText == null ? "" : newText)
                    + content.substring(first + oldText.length());
            Files.writeString(p, updated, StandardCharsets.UTF_8);
            return "OK replaced 1 occurrence in " + p.toAbsolutePath();
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = "Append content to the end of a file. Creates the file if missing.")
    public String appendFile(
            @ToolParam(description = "File path.") String path,
            @ToolParam(description = "Content to append.") String content) {
        try {
            Path p = Path.of(path);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return "OK appended " + (content == null ? 0 : content.length()) + " chars to " + p.toAbsolutePath();
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @SuppressWarnings("unused")
    private List<String> placeholder() { return List.of(); }
}

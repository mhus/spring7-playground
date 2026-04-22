package de.mhus.spring7.aiassistant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class AssistantTools {

    @Tool(description = "Returns the current local date and time.")
    public String currentTime() {
        return LocalDateTime.now().toString();
    }

    @Tool(description = "Lists file names directly inside the given directory path (non-recursive).")
    public List<String> listFiles(String path) {
        try (Stream<Path> s = Files.list(Path.of(path))) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (Exception e) {
            return List.of("ERROR: " + e.getMessage());
        }
    }

    @Tool(description = "Reads the textual content of a file at the given path. Returns up to 4000 chars.")
    public String readFile(String path) {
        try {
            String content = Files.readString(Path.of(path));
            return content.length() > 4000 ? content.substring(0, 4000) + "\n…[truncated]" : content;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}

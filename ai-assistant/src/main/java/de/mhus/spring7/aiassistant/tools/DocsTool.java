package de.mhus.spring7.aiassistant.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Exposes bundled markdown how-to docs under {@code classpath:docs/*.md} to the agent so it
 * can learn on demand — no need to fill the context preemptively. Two meta-tools:
 * list topics, read one.
 */
@Component
public class DocsTool implements AgentTool {

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    @Tool(description = """
            List available documentation topics bundled with the assistant. These are concise
            markdown guides describing how the assistant works: how to create external tools,
            what the different file scopes mean, how memory/RAG is organized, how subtask and
            orchestrate compare, etc. Read one with readDoc(name). Consult these whenever the
            user's question is about the assistant itself or you need to operate an advanced
            feature you're unsure about.
            """)
    public String listDocs() {
        try {
            Resource[] resources = resolver.getResources("classpath:docs/*.md");
            if (resources.length == 0) return "(no docs bundled)";
            List<String> names = new ArrayList<>();
            for (Resource r : resources) {
                String fn = r.getFilename();
                if (fn != null && fn.endsWith(".md")) {
                    names.add(fn.substring(0, fn.length() - ".md".length()));
                }
            }
            names.sort(String::compareTo);
            return String.join("\n", names);
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = """
            Read a specific documentation topic by name. Use listDocs() first to see what's
            available. Pass the name without the .md extension.
            """)
    public String readDoc(
            @ToolParam(description = "Doc topic name (without extension), e.g. 'tools' or 'scopes'.") String name) {
        if (name == null || name.isBlank()) return "ERROR: empty name";
        // basic sanitization — no path traversal
        if (name.contains("/") || name.contains("..")) return "ERROR: invalid name";
        Resource r = resolver.getResource("classpath:docs/" + name + ".md");
        if (!r.exists()) {
            return "ERROR: doc '" + name + "' not found. Available: " + Arrays.toString(availableNames());
        }
        try {
            return r.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String[] availableNames() {
        try {
            Resource[] rs = resolver.getResources("classpath:docs/*.md");
            return Arrays.stream(rs)
                    .map(Resource::getFilename)
                    .filter(f -> f != null && f.endsWith(".md"))
                    .map(f -> f.substring(0, f.length() - ".md".length()))
                    .sorted()
                    .toArray(String[]::new);
        } catch (IOException e) {
            return new String[0];
        }
    }
}

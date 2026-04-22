package de.mhus.spring7.aiassistant.tools.external;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.mhus.spring7.aiassistant.tools.AgentTool;

/**
 * Agent-facing meta-tools for the dynamic external tool registry. The agent uses these to
 * discover, inspect and invoke tools that are not baked into the core — REST endpoints,
 * MCP servers, etc. — defined in {@code data/project/tools/*.json}.
 *
 * Usage pattern:
 *   1. findTools("what you're looking for") → names + descriptions
 *   2. describeTool(name) → full parameter schema
 *   3. invokeTool(name, '{"arg":"value"}') → raw response
 */
@Component
public class ToolRegistryTool implements AgentTool {

    private final ToolService svc;
    private final ObjectMapper json = new ObjectMapper();

    public ToolRegistryTool(ToolService svc) {
        this.svc = svc;
    }

    @Tool(description = """
            Search the external tool registry for tools matching a keyword. Matches against
            tool name and description (case-insensitive substring). Returns up to 20 hits as
            "name: description" lines. Pass an empty string to list all.
            """)
    public String findTools(
            @ToolParam(description = "Search keyword (e.g. 'weather', 'git'). Empty = list all.") String query) {
        List<ToolDescriptor> hits = svc.search(query);
        if (hits.isEmpty()) {
            return "(no external tools found — registry has " + svc.size() + " tool(s) total. "
                    + "Try reloadTools() after adding JSON configs to data/project/tools/.)";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (ToolDescriptor d : hits) {
            if (i++ >= 20) { sb.append("(truncated — refine your query)\n"); break; }
            sb.append(d.name()).append(": ").append(d.description() == null ? "" : d.description()).append("\n");
        }
        return sb.toString();
    }

    @Tool(description = """
            Get the full specification of an external tool by name, including its parameter
            schema. Call this after findTools to know what JSON arguments invokeTool expects.
            """)
    public String describeTool(
            @ToolParam(description = "Exact tool name as returned by findTools.") String name) {
        ToolDescriptor d = svc.describe(name);
        if (d == null) return "ERROR: tool not found: " + name;
        try {
            return "name: " + d.name() + "\n"
                    + "description: " + (d.description() == null ? "" : d.description()) + "\n"
                    + "params: " + json.writerWithDefaultPrettyPrinter().writeValueAsString(d.params());
        } catch (JsonProcessingException e) {
            return "ERROR serializing params: " + e.getMessage();
        }
    }

    @Tool(description = """
            Invoke an external tool by name with JSON arguments. The raw response (body/text)
            is returned. On error, the message starts with "ERROR:".
            """)
    public String invokeTool(
            @ToolParam(description = "Exact tool name.") String name,
            @ToolParam(description = "JSON object with arguments matching the tool's params schema. Use {} for no args.") String jsonArgs) {
        return svc.invoke(name, jsonArgs);
    }

    @Tool(description = """
            Re-scan data/project/tools/ and reload all external tool configurations. Use this
            after you created or edited a JSON tool config. Returns a summary of loaded/failed.
            """)
    public String reloadTools() {
        return svc.reload();
    }
}

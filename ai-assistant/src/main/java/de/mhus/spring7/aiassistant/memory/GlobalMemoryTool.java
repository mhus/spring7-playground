package de.mhus.spring7.aiassistant.memory;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.tools.AgentTool;

/**
 * Exposes {@link GlobalMemoryService} to both the agent (as AgentTool) and the user
 * (as Shell commands). The agent should self-register facts here whenever something is
 * worth remembering beyond the current session.
 */
@Component
public class GlobalMemoryTool implements AgentTool {

    private final GlobalMemoryService svc;

    public GlobalMemoryTool(GlobalMemoryService svc) {
        this.svc = svc;
    }

    // ---- Agent tools ----

    @Tool(description = """
            Permanently remember a short fact across all sessions. Use this when you learn
            something that will matter in future conversations: user preferences, project
            constraints, key decisions, long-lived reminders. Kept small and direct — one
            fact per call. Not for large documents (use writeProjectFile for those).
            Stored in data/project/memory.json and injected into every system prompt.
            """)
    public String remember(
            @ToolParam(description = "One concise fact to remember, e.g. 'User prefers terse answers'.") String fact) {
        svc.add(fact);
        return "remembered. total global facts: " + svc.size();
    }

    @Tool(description = "List all currently remembered global facts with 1-based indices.")
    public String listRemembered() {
        if (svc.isEmpty()) return "(no global facts)";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (String f : svc.all()) sb.append("[").append(i++).append("] ").append(f).append("\n");
        return sb.toString();
    }

    @Tool(description = """
            Forget one global fact by 1-based index. Call listRemembered() first to see
            indices. Use when a fact is outdated or wrong.
            """)
    public String forgetRemembered(
            @ToolParam(description = "1-based index from listRemembered().") int index) {
        boolean ok = svc.remove(index);
        return ok ? "forgot fact #" + index : "index " + index + " out of range";
    }

    // ---- Shell commands ----

    @Command(name = "remember", group = "Memory", description = "Add a permanent global fact (visible in every future session).")
    public String rememberCommand(@Argument(index = 0, description = "The fact to remember.") String fact) {
        svc.add(fact);
        return "remembered. total global facts: " + svc.size();
    }

    @Command(name = "remembered", group = "Memory", description = "List all global facts.")
    public String rememberedCommand() {
        return listRemembered();
    }

    @Command(name = "unremember", group = "Memory", description = "Remove a global fact by 1-based index, or 'all' to clear everything.")
    public String unrememberCommand(@Argument(index = 0, description = "Index or 'all'.") String arg) {
        if ("all".equalsIgnoreCase(arg)) {
            int n = svc.clear();
            return "forgot " + n + " global fact(s)";
        }
        try {
            int idx = Integer.parseInt(arg.strip());
            boolean ok = svc.remove(idx);
            return ok ? "forgot fact #" + idx : "fact #" + idx + " not found";
        } catch (NumberFormatException e) {
            return "ERROR: expected index or 'all'";
        }
    }
}

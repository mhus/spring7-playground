package de.mhus.spring7.aiassistant.memory;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.AssistantCommands;
import de.mhus.spring7.aiassistant.RagCommands;
import de.mhus.spring7.aiassistant.plan.AssistantPlanCommands;
import de.mhus.spring7.aiassistant.plan.BaseSettings;
import de.mhus.spring7.aiassistant.plan.SharedRagStore;
import de.mhus.spring7.aiassistant.storage.StorageService;
import de.mhus.spring7.aiassistant.storage.TokenTracker;
import de.mhus.spring7.aiassistant.tools.ExecManager;
import de.mhus.spring7.aiassistant.tools.external.ToolService;

@Component
public class MemoryCommands {

    private final ChatMemory chatMemory;
    private final RagCommands assistantRag;
    private final SharedRagStore planRag;
    private final AssistantPlanCommands planCommands;
    private final BaseSettings baseSettings;
    private final PinService pins;
    private final GlobalMemoryService globalMemory;
    private final StorageService storage;
    private final TokenTracker tokens;
    private final ExecManager exec;
    private final ToolService externalTools;

    public MemoryCommands(ChatMemory chatMemory, RagCommands assistantRag, SharedRagStore planRag,
                          AssistantPlanCommands planCommands, BaseSettings baseSettings,
                          PinService pins, GlobalMemoryService globalMemory,
                          StorageService storage, TokenTracker tokens,
                          ExecManager exec, ToolService externalTools) {
        this.chatMemory = chatMemory;
        this.assistantRag = assistantRag;
        this.planRag = planRag;
        this.planCommands = planCommands;
        this.baseSettings = baseSettings;
        this.pins = pins;
        this.globalMemory = globalMemory;
        this.storage = storage;
        this.tokens = tokens;
        this.exec = exec;
        this.externalTools = externalTools;
    }

    @Command(name = "memory", group = "Memory", description = "Dashboard of all memory layers (chat, RAG, plan, pins, settings, files, exec, tokens).")
    public String memory() {
        var chat = chatMemory.get(AssistantCommands.CONVERSATION_ID);
        long running = exec.list().stream().filter(j -> j.status().name().equals("RUNNING")).count();
        String plan = planCommands.currentPlanName() == null ? "(none)" : planCommands.currentPlanName();
        StringBuilder sb = new StringBuilder();
        sb.append("session     ").append(storage.currentSession()).append("\n");
        sb.append("chat        ").append(chat.size()).append(" messages (window 20)\n");
        sb.append("pins        ").append(pins.size()).append(" pinned fact(s) — session\n");
        sb.append("remembered  ").append(globalMemory.size()).append(" global fact(s)\n");
        sb.append("rag         ").append(assistantRag.storedDocs().size() + planRag.size())
          .append(" chunks (").append(assistantRag.storedDocs().size()).append(" assistant, ")
          .append(planRag.size()).append(" plan)\n");
        sb.append("plan        current: ").append(plan).append("\n");
        sb.append("settings    ").append(baseSettings.size()).append(" base setting(s)\n");
        sb.append("workspace   ").append(storage.countWorkspaceFiles()).append(" file(s)\n");
        sb.append("project     ").append(storage.countProjectFiles()).append(" file(s)\n");
        sb.append("exec        ").append(exec.list().size()).append(" task(s) (").append(running).append(" running)\n");
        sb.append("ext-tools   ").append(externalTools.size()).append(" tool(s) in registry\n");
        sb.append("tokens      ").append(tokens.summary()).append("\n");
        return sb.toString();
    }

    @Command(name = "memory-drop", group = "Memory", description = "Drop chat messages by 1-based index. Accepts 'N' or 'N-M'. Warning: breaking a tool-call/tool-response pair can confuse the LLM.")
    public String memoryDrop(@Argument(index = 0, description = "Index or range like '3' or '3-5'.") String spec) {
        List<Message> messages = new ArrayList<>(chatMemory.get(AssistantCommands.CONVERSATION_ID));
        int n = messages.size();
        if (n == 0) return "(chat is empty)";
        int from, to;
        try {
            int dash = spec.indexOf('-');
            if (dash < 0) {
                int idx = Integer.parseInt(spec.strip());
                from = to = idx;
            } else {
                from = Integer.parseInt(spec.substring(0, dash).strip());
                to = Integer.parseInt(spec.substring(dash + 1).strip());
            }
        } catch (NumberFormatException e) {
            return "ERROR: invalid range '" + spec + "' (expected 'N' or 'N-M')";
        }
        if (from < 1 || to > n || from > to) {
            return "ERROR: range out of bounds (have " + n + " messages, got " + from + ".." + to + ")";
        }
        List<Message> kept = new ArrayList<>(n - (to - from + 1));
        for (int i = 0; i < n; i++) {
            int pos = i + 1;
            if (pos < from || pos > to) kept.add(messages.get(i));
        }
        chatMemory.clear(AssistantCommands.CONVERSATION_ID);
        for (Message m : kept) chatMemory.add(AssistantCommands.CONVERSATION_ID, m);
        return "dropped messages " + from + ".." + to + " — " + kept.size() + " remain";
    }

    @Command(name = "pin", group = "Memory", description = "Pin a fact the agent must always remember. Persisted per session.")
    public String pin(@Argument(index = 0, description = "The fact to pin, e.g. 'User is called Mike'.") String text) {
        pins.add(text);
        return "pinned. now " + pins.size() + " pin(s).";
    }

    @Command(name = "pins", group = "Memory", description = "List pinned facts.")
    public String pinsList() {
        if (pins.isEmpty()) return "(no pins)";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (String p : pins.all()) sb.append("[").append(i++).append("] ").append(p).append("\n");
        return sb.toString();
    }

    @Command(name = "unpin", group = "Memory", description = "Remove a pin by 1-based index (see 'pins'), or 'all' to clear everything.")
    public String unpin(@Argument(index = 0, description = "Index or 'all'.") String arg) {
        if ("all".equalsIgnoreCase(arg)) {
            int n = pins.clear();
            return "cleared " + n + " pin(s)";
        }
        try {
            int idx = Integer.parseInt(arg.strip());
            boolean ok = pins.remove(idx);
            return ok ? "removed pin #" + idx : "pin #" + idx + " not found";
        } catch (NumberFormatException e) {
            return "ERROR: expected index or 'all'";
        }
    }
}

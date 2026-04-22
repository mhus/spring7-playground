package de.mhus.spring7.aiassistant.storage;

import java.util.UUID;

import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.AssistantCommands;
import de.mhus.spring7.aiassistant.RagCommands;
import de.mhus.spring7.aiassistant.plan.AssistantPlanCommands;
import de.mhus.spring7.aiassistant.plan.SharedRagStore;
import de.mhus.spring7.aiassistant.tools.ExecManager;

@Component
public class SessionCommands {

    private final StorageService storage;
    private final AssistantCommands assistantCommands;
    private final AssistantPlanCommands planCommands;
    private final RagCommands ragCommands;
    private final SharedRagStore sharedRagStore;
    private final ExecManager execManager;

    public SessionCommands(StorageService storage,
                           AssistantCommands assistantCommands,
                           AssistantPlanCommands planCommands,
                           RagCommands ragCommands,
                           SharedRagStore sharedRagStore,
                           ExecManager execManager) {
        this.storage = storage;
        this.assistantCommands = assistantCommands;
        this.planCommands = planCommands;
        this.ragCommands = ragCommands;
        this.sharedRagStore = sharedRagStore;
        this.execManager = execManager;
    }

    private void reloadAll() {
        assistantCommands.reloadFromStorage();
        planCommands.reloadFromStorage();
        ragCommands.reloadFromStorage();
        sharedRagStore.reloadFromStorage();
        execManager.reloadFromStorage();
    }

    @Command(name = "session", group = "Session", description = "Show the current session id and stats.")
    public String session() {
        SessionMeta m = storage.meta(storage.currentSession());
        return String.format("""
                session : %s
                created : %s
                active  : %s
                messages: %d
                runs    : %d
                path    : %s
                """,
                m.id(), m.createdAt(), m.lastActiveAt(),
                storage.countMessages(), storage.countRuns(), storage.sessionDir().toAbsolutePath());
    }

    @Command(name = "sessions", group = "Session", description = "List all stored sessions.")
    public String sessions() {
        var ids = storage.listSessions();
        if (ids.isEmpty()) return "(no sessions on disk)";
        StringBuilder sb = new StringBuilder();
        String current = storage.currentSession();
        for (String id : ids) {
            SessionMeta m = storage.meta(id);
            sb.append(id.equals(current) ? "* " : "  ")
              .append(id).append("  msgs=").append(m.messageCount())
              .append("  runs=").append(m.runCount())
              .append("  active=").append(m.lastActiveAt()).append("\n");
        }
        return sb.toString();
    }

    @Command(name = "resume", group = "Session", description = "Switch to another session and load its chat memory and plan.")
    public String resume(@Argument(index = 0, description = "Session UUID (as shown by 'sessions').") String id) {
        if (!storage.listSessions().contains(id)) {
            return "session not found: " + id;
        }
        storage.switchSession(id);
        reloadAll();
        SessionMeta m = storage.meta(id);
        return "resumed " + id + "  msgs=" + m.messageCount() + "  runs=" + m.runCount();
    }

    @Command(name = "new-session", group = "Session", description = "Start a fresh session (leaves the current one on disk).")
    public String newSession() {
        String id = UUID.randomUUID().toString();
        storage.switchSession(id);
        reloadAll();
        return "new session: " + id;
    }
}

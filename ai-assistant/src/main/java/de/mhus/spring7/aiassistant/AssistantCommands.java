package de.mhus.spring7.aiassistant;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.storage.ChatMessageDto;
import de.mhus.spring7.aiassistant.storage.StorageService;
import de.mhus.spring7.aiassistant.storage.TokenTracker;
import de.mhus.spring7.aiassistant.tools.AgentTool;
import de.mhus.spring7.aiassistant.tools.OrchestrateTool;
import de.mhus.spring7.aiassistant.tools.SubtaskTool;

@Component
public class AssistantCommands {

    public static final String CONVERSATION_ID = "shell-session";
    private static final int RAG_TOP_K = 4;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final VectorStore vectorStore;
    private final ToolCallingManager toolCallingManager;
    private final StorageService storage;
    private final TokenTracker tokens;
    private final SystemPromptService systemPromptService;
    private final ToolCallback[] toolCallbacks;
    private final int maxIterations;

    public AssistantCommands(ChatClient chatClient, ChatMemory chatMemory, VectorStore vectorStore,
                             ToolCallingManager toolCallingManager,
                             StorageService storage, TokenTracker tokens,
                             SystemPromptService systemPromptService,
                             AssistantTools tools, List<AgentTool> agentTools,
                             OrchestrateTool orchestrateTool, SubtaskTool subtaskTool,
                             @Value("${ai.assistant.max-iterations:15}") int maxIterations) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.vectorStore = vectorStore;
        this.toolCallingManager = toolCallingManager;
        this.storage = storage;
        this.tokens = tokens;
        this.systemPromptService = systemPromptService;
        this.maxIterations = maxIterations;
        List<Object> allTools = new ArrayList<>();
        allTools.add(tools);
        allTools.addAll(agentTools);
        allTools.add(orchestrateTool);
        allTools.add(subtaskTool);
        this.toolCallbacks = ToolCallbacks.from(allTools.toArray());
    }

    @Command(name = "say", group = "Assistant", description = "Send a message. Uses memory, tools, and imported PDFs. Shows per-iteration tool activity live.")
    public String say(@Argument(index = 0, description = "Your message.") String message) {
        // 1. Build initial conversation: system + history + user (augmented with RAG if applicable)
        List<Message> conversation = new ArrayList<>();
        conversation.add(new SystemMessage(systemPromptService.get()));
        conversation.addAll(chatMemory.get(CONVERSATION_ID));

        String augmentedUser = maybeAugmentWithRag(message);
        conversation.add(new UserMessage(augmentedUser));

        // 2. Manual tool-call loop with live updates
        AssistantMessage finalAi = null;
        for (int i = 0; i < maxIterations; i++) {
            long t0 = System.currentTimeMillis();
            ChatResponse resp = chatClient.prompt().messages(conversation).call().chatResponse();
            long ms = System.currentTimeMillis() - t0;
            tokens.record("say", resp.getMetadata().getUsage());

            AssistantMessage ai = resp.getResult().getOutput();
            conversation.add(ai);

            if (ai.getToolCalls() == null || ai.getToolCalls().isEmpty()) {
                finalAi = ai;
                IO.println("  [iter " + (i + 1) + ": done in " + ms + " ms]");
                break;
            }

            String toolsSummary = ai.getToolCalls().stream()
                    .map(tc -> tc.name() + "(" + truncate(tc.arguments(), 60) + ")")
                    .collect(Collectors.joining(", "));
            IO.println("  [iter " + (i + 1) + ": " + ai.getToolCalls().size()
                    + " tool call(s), " + ms + " ms]  " + toolsSummary);

            Prompt execPrompt = new Prompt(conversation,
                    ToolCallingChatOptions.builder()
                            .toolCallbacks(toolCallbacks)
                            .internalToolExecutionEnabled(false)
                            .build());
            ToolExecutionResult execResult = toolCallingManager.executeToolCalls(execPrompt, resp);
            conversation = new ArrayList<>(execResult.conversationHistory());
        }

        if (finalAi == null) {
            return "max iterations (" + maxIterations + ") reached — the agent kept calling tools. "
                    + "Try /reset or refine your question.";
        }

        // 3. Persist only the user + final assistant pair to chat memory
        chatMemory.add(CONVERSATION_ID, new UserMessage(message));
        chatMemory.add(CONVERSATION_ID, new AssistantMessage(finalAi.getText()));
        persistMemory();

        return finalAi.getText();
    }

    @Command(name = "reset", group = "Assistant", description = "Clear the conversation memory (keeps imported PDFs).")
    public String reset() {
        chatMemory.clear(CONVERSATION_ID);
        persistMemory();
        return "conversation cleared";
    }

    @Command(name = "history", group = "Assistant", description = "Show the conversation history.")
    public String history() {
        var messages = chatMemory.get(CONVERSATION_ID);
        if (messages.isEmpty()) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Message m : messages) {
            sb.append("[").append(i++).append("] ").append(m.getMessageType()).append(": ")
              .append(m.getText()).append("\n");
        }
        return sb.toString();
    }

    @Command(name = "tools", group = "Assistant", description = "List the tools available to the agent.")
    public String tools() {
        return String.join("\n", List.of(
                "currentTime()                              - current local date/time",
                "executeCommand(cmd)                        - run a shell command (async, 15s wait)",
                "getTaskOutput(id)                          - query a previously started shell task",
                "killTask(id)                               - kill a running shell task",
                "executeJavaScript(code)                    - run JavaScript inline (GraalJS / Rhino)",
                "listDirectory(path)                        - list a filesystem directory",
                "readFile(path, startLine?, maxLines?)      - read text file, paged",
                "writeFile(path, content)                   - create or overwrite a file",
                "editFile(path, oldText, newText)           - replace one occurrence in a file",
                "appendFile(path, content)                  - append to a file",
                "writeSessionFile(rel, content)             - create/overwrite in session workspace",
                "readSessionFile(rel)                       - read from session workspace",
                "editSessionFile(rel, oldText, newText)     - edit in session workspace",
                "deleteSessionFile(rel)                     - delete in session workspace",
                "listSessionFiles()                         - list session workspace",
                "getSessionWorkspacePath()                  - absolute path of session workspace",
                "executeSessionJavaScript(rel)              - run a JS file from the workspace",
                "writeProjectFile(rel, content)             - create/overwrite in project scope",
                "readProjectFile(rel)                       - read from project scope",
                "editProjectFile(rel, oldText, newText)     - edit in project scope",
                "deleteProjectFile(rel)                     - delete in project scope",
                "listProjectFiles()                         - list project scope",
                "getProjectPath()                           - absolute path of project scope",
                "ingestProjectFile(rel)                     - load a project file into session RAG",
                "listDocs()                                 - list bundled how-to docs",
                "readDoc(name)                              - read a how-to doc (e.g. 'memory')",
                "remember(fact)                             - save a permanent global fact",
                "listRemembered()                           - list global facts",
                "forgetRemembered(n)                        - remove a global fact by index",
                "findTools(query)                           - search external tool registry",
                "describeTool(name)                         - get params schema of an external tool",
                "invokeTool(name, jsonArgs)                 - call an external tool",
                "reloadTools()                              - re-read data/project/tools/",
                "orchestrate(problem)                       - multi-agent pipeline for a structured task",
                "subtask(task, context?, persona?)          - fresh sub-LLM with same tools, no memory"
        ));
    }

    /** Reload chat memory from the active session on disk. Called by SessionCommands.resume. */
    public void reloadFromStorage() {
        chatMemory.clear(CONVERSATION_ID);
        for (ChatMessageDto dto : storage.loadChatMemory()) {
            chatMemory.add(CONVERSATION_ID, toMessage(dto));
        }
    }

    private String maybeAugmentWithRag(String userMessage) {
        List<Document> hits;
        try {
            hits = vectorStore.similaritySearch(
                    SearchRequest.builder().query(userMessage).topK(RAG_TOP_K).build());
        } catch (Exception e) {
            return userMessage;
        }
        if (hits == null || hits.isEmpty()) return userMessage;
        String context = hits.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
        return "Context from knowledge store:\n" + context + "\n\n---\n\nUser: " + userMessage;
    }

    private void persistMemory() {
        var messages = chatMemory.get(CONVERSATION_ID);
        storage.saveChatMemory(messages.stream().map(AssistantCommands::toDto).toList());
    }

    private static ChatMessageDto toDto(Message m) {
        return new ChatMessageDto(m.getMessageType().name(), m.getText());
    }

    private static Message toMessage(ChatMessageDto d) {
        return switch (d.type()) {
            case "USER" -> new UserMessage(d.text());
            case "ASSISTANT" -> new AssistantMessage(d.text());
            case "SYSTEM" -> new SystemMessage(d.text());
            default -> new UserMessage(d.text());
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}

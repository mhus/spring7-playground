package de.mhus.spring7.aiassistant;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.storage.ChatMessageDto;
import de.mhus.spring7.aiassistant.storage.StorageService;
import de.mhus.spring7.aiassistant.storage.TokenTracker;

@Component
public class AssistantCommands {

    public static final String CONVERSATION_ID = "shell-session";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final StorageService storage;
    private final TokenTracker tokens;

    public AssistantCommands(ChatClient chatClient, ChatMemory chatMemory,
                             StorageService storage, TokenTracker tokens) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.storage = storage;
        this.tokens = tokens;
    }

    @Command(name = "say", group = "Assistant", description = "Send a message. Uses memory, tools, and imported PDFs.")
    public String say(@Argument(index = 0, description = "Your message.") String message) {
        ChatResponse resp = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .call()
                .chatResponse();
        tokens.record("say", resp.getMetadata().getUsage());
        String content = resp.getResult().getOutput().getText();
        persistMemory();
        return content;
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
        messages.forEach(m -> sb.append(m.getMessageType()).append(": ").append(m.getText()).append("\n"));
        return sb.toString();
    }

    @Command(name = "tools", group = "Assistant", description = "List the tools available to the agent.")
    public String tools() {
        return String.join("\n", List.of(
                "currentTime() - current local time",
                "listFiles(path) - list files in directory",
                "readFile(path) - read file content",
                "executeJavaScript(code) - run Mozilla Rhino JavaScript",
                "orchestrate(problem) - run a multi-agent pipeline for a complex task"
        ));
    }

    /** Reload chat memory from the active session on disk. Called by SessionCommands.resume. */
    public void reloadFromStorage() {
        chatMemory.clear(CONVERSATION_ID);
        for (ChatMessageDto dto : storage.loadChatMemory()) {
            chatMemory.add(CONVERSATION_ID, toMessage(dto));
        }
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
}

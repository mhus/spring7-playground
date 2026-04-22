package de.mhus.spring7.aiassistant;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

@Component
public class AssistantCommands {

    static final String CONVERSATION_ID = "shell-session";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public AssistantCommands(ChatClient chatClient, ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
    }

    @Command(name = "say", group = "Assistant", description = "Send a message. Uses memory, tools, and imported PDFs.")
    public String say(@Argument(index = 0, description = "Your message.") String message) {
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .call()
                .content();
    }

    @Command(name = "reset", group = "Assistant", description = "Clear the conversation memory (keeps imported PDFs).")
    public String reset() {
        chatMemory.clear(CONVERSATION_ID);
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
                "readFile(path) - read file content"
        ));
    }
}

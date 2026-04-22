package de.mhus.spring7.aiagent;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

@Component
public class ChatCommands {

    private static final String CONVERSATION_ID = "shell-session";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final AgentTools tools;

    public ChatCommands(ChatClient.Builder builder, ChatMemory chatMemory, AgentTools tools) {
        this.chatMemory = chatMemory;
        this.tools = tools;
        this.chatClient = builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(tools)
                .build();
    }

    @Command(name = "say", group = "Agent", description = "Send a message to the agent.")
    public String say(@Argument(index = 0, description = "Your message.") String message) {
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .call()
                .content();
    }

    @Command(name = "reset", group = "Agent", description = "Clear the conversation memory.")
    public String reset() {
        chatMemory.clear(CONVERSATION_ID);
        return "conversation cleared";
    }

    @Command(name = "tools", group = "Agent", description = "List the tools available to the agent.")
    public String tools() {
        return String.join("\n", List.of(
                "currentTime() - current local time",
                "listFiles(path) - list files in directory",
                "readFile(path) - read file content"
        ));
    }

    @Command(name = "history", group = "Agent", description = "Show the conversation history.")
    public String history() {
        var messages = chatMemory.get(CONVERSATION_ID);
        if (messages.isEmpty()) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        messages.forEach(m -> sb.append(m.getMessageType()).append(": ").append(m.getText()).append("\n"));
        return sb.toString();
    }
}

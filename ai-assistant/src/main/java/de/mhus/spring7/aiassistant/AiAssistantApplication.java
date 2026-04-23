package de.mhus.spring7.aiassistant;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import de.mhus.spring7.aiassistant.tools.AgentTool;
import de.mhus.spring7.aiassistant.tools.OrchestrateTool;
import de.mhus.spring7.aiassistant.tools.SubtaskTool;

@SpringBootApplication
public class AiAssistantApplication {

    static void main(String[] args) {
        SpringApplication.run(AiAssistantApplication.class, args);
    }

    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().maxMessages(20).build();
    }

    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * Main-chat ChatClient without advisors and with {@code internalToolExecutionEnabled=false}.
     * {@link AssistantCommands#say} drives the tool-call loop manually so it can emit
     * per-iteration progress and manage memory + RAG context explicitly.
     */
    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          AssistantTools tools,
                          List<AgentTool> agentTools,
                          OrchestrateTool orchestrateTool,
                          SubtaskTool subtaskTool) {
        List<Object> allTools = new ArrayList<>();
        allTools.add(tools);
        allTools.addAll(agentTools);
        allTools.add(orchestrateTool);
        allTools.add(subtaskTool);
        return builder
                .defaultTools(allTools.toArray())
                .defaultOptions(ToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build())
                .build();
    }
}

package de.mhus.spring7.aiassistant;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import de.mhus.spring7.aiassistant.tools.AgentTool;
import de.mhus.spring7.aiassistant.tools.OrchestrateTool;

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

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          ChatMemory chatMemory,
                          VectorStore vectorStore,
                          AssistantTools tools,
                          List<AgentTool> agentTools,
                          OrchestrateTool orchestrateTool) {
        List<Object> allTools = new ArrayList<>();
        allTools.add(tools);
        allTools.addAll(agentTools);
        allTools.add(orchestrateTool);
        return builder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(SearchRequest.builder().topK(4).build())
                                .build())
                .defaultTools(allTools.toArray())
                .build();
    }
}

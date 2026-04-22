package de.mhus.spring7.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class AiGeminiApplication {

    static void main(String[] args) {
        SpringApplication.run(AiGeminiApplication.class, args);
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    CommandLineRunner demo(ChatClient chat) {
        return args -> {
            String prompt = args.length > 0
                    ? String.join(" ", args)
                    : "Tell me a one-line joke about Java.";
            String answer = chat.prompt(prompt).call().content();
            IO.println("[gemini] " + answer);
        };
    }
}

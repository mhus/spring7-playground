package de.mhus.spring7.aiassistant;

import java.time.LocalDateTime;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class AssistantTools {

    @Tool(description = "Returns the current local date and time.")
    public String currentTime() {
        return LocalDateTime.now().toString();
    }
}

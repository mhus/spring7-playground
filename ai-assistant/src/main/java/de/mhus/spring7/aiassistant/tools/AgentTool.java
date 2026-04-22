package de.mhus.spring7.aiassistant.tools;

/**
 * Marker interface — any Spring bean implementing this is offered to the agent pipeline executor
 * and to the main assistant ChatClient as a tool. Must expose one or more methods annotated with
 * {@link org.springframework.ai.tool.annotation.Tool}.
 */
public interface AgentTool {
}

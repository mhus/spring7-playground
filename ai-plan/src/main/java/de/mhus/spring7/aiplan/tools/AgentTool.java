package de.mhus.spring7.aiplan.tools;

/**
 * Marker interface — any Spring bean implementing this is offered to the agent pipeline executor
 * as a tool. The bean class must expose one or more methods annotated with
 * {@link org.springframework.ai.tool.annotation.Tool}.
 */
public interface AgentTool {
}

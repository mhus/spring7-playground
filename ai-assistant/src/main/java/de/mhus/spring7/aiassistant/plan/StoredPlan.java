package de.mhus.spring7.aiassistant.plan;

/**
 * Wraps a Plan with the original problem statement so both can be persisted/restored together.
 * The Plan record itself stays focused on what the planner LLM returns.
 */
public record StoredPlan(String problem, Plan plan) {}

package de.mhus.spring7.aiplan;

import java.util.List;

public record Plan(List<AgentSpec> agents, List<String> openQuestions) {

    public boolean hasQuestions() {
        return openQuestions != null && !openQuestions.isEmpty();
    }

    public boolean hasAgents() {
        return agents != null && !agents.isEmpty();
    }
}

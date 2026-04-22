package de.mhus.spring7.aiassistant.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExecutionContext {

    private final String originalProblem;
    private final List<String> baseSettings;
    private final Map<String, Object> stepOutputs = new LinkedHashMap<>();

    public ExecutionContext(String originalProblem, List<String> baseSettings) {
        this.originalProblem = originalProblem;
        this.baseSettings = baseSettings;
    }

    public String originalProblem() {
        return originalProblem;
    }

    public List<String> baseSettings() {
        return baseSettings;
    }

    public Map<String, Object> stepOutputs() {
        return stepOutputs;
    }

    public void put(String name, Object output) {
        stepOutputs.put(name, output);
    }
}

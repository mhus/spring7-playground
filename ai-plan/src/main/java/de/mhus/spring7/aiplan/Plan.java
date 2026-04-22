package de.mhus.spring7.aiplan;

import java.util.List;

public record Plan(List<PipelineStep> steps, List<String> openQuestions) {

    public boolean hasQuestions() {
        return openQuestions != null && !openQuestions.isEmpty();
    }

    public boolean hasSteps() {
        return steps != null && !steps.isEmpty();
    }
}

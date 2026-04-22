package de.mhus.spring7.aiassistant.tools;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.plan.ExecutionContext;
import de.mhus.spring7.aiassistant.plan.PipelineExecutor;
import de.mhus.spring7.aiassistant.plan.Plan;
import de.mhus.spring7.aiassistant.plan.PlannerPrompts;
import de.mhus.spring7.aiassistant.storage.TokenTracker;

/**
 * Exposes the plan/execute pipeline as a tool the assistant ChatClient can call during `say`.
 * Uses FORCE_SYSTEM so the planner always produces executable agents (it cannot ask questions
 * inside a tool call).
 *
 * NOT an AgentTool — otherwise sub-agents could call orchestrate recursively.
 */
@Component
public class OrchestrateTool {

    private final ChatClient planner;
    private final PipelineExecutor executor;
    private final TokenTracker tokens;

    public OrchestrateTool(ChatClient.Builder builder, @Lazy PipelineExecutor executor, TokenTracker tokens) {
        this.planner = builder.build();
        this.executor = executor;
        this.tokens = tokens;
    }

    @Tool(description = """
            Break a complex task into a pipeline of sub-agents and execute it. Use for multi-step
            goals (writing with research, multiple chapters, structured analyses, comparative
            evaluations) where a single LLM reply would be insufficient. The pipeline runs
            autonomously (makes assumptions where needed) and the final output is returned.
            Live progress is printed to the console.
            """)
    public String orchestrate(
            @ToolParam(description = "The full problem statement, self-contained.")
            String problem) {
        ResponseEntity<ChatResponse, Plan> re = planner.prompt()
                .system(PlannerPrompts.FORCE_SYSTEM)
                .user(problem)
                .call()
                .responseEntity(Plan.class);
        tokens.record("orchestrate:planner", re.response().getMetadata().getUsage());
        Plan plan = re.entity();
        if (plan == null || !plan.hasSteps()) {
            return "orchestrate: planner did not produce an executable plan";
        }
        ExecutionContext ctx = new ExecutionContext(problem, List.of());
        Object result = executor.execute(plan, ctx);
        return String.valueOf(result);
    }
}

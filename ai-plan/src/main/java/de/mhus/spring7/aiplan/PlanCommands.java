package de.mhus.spring7.aiplan;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

@Component
public class PlanCommands {

    private static final String PLANNER_SYSTEM = """
            You design a linear pipeline of specialist sub-agents that together solve the user's problem.
            Each agent is a single LLM call with a system prompt.
            The output of agent N becomes the user input for agent N+1.
            The first agent receives the original problem statement as its user input.
            The last agent's output is the final deliverable.

            Rules:
            - 2 to 5 agents. Fewer is usually better.
            - Each agent has a distinct, focused role (e.g. Researcher, Outliner, Writer, Lector, Finalizer).
            - Each systemPrompt must be self-contained and specific: describe the role, the expected input,
              and the exact output format/style. No meta-commentary.
            - Do not mention other agents inside a systemPrompt. Each agent only sees its own systemPrompt
              and the output handed to it.
            """;

    private final ChatClient planner;
    private final ChatClient executor;

    private volatile String currentProblem;
    private volatile Plan currentPlan;

    public PlanCommands(ChatClient.Builder builder) {
        this.planner = builder.build();
        this.executor = builder.build();
    }

    @Command(name = "plan", group = "Plan", description = "Design a pipeline of sub-agents for the given problem.")
    public String plan(@Argument(index = 0, description = "Problem statement, e.g. 'Create a text about horses'.") String problem) {
        IO.println("[planner calling LLM…]");
        long t0 = System.currentTimeMillis();
        Plan plan = planner.prompt()
                .system(PLANNER_SYSTEM)
                .user(problem)
                .call()
                .entity(Plan.class);
        IO.println("[planner done in " + (System.currentTimeMillis() - t0) + " ms]");
        if (plan == null || plan.agents() == null || plan.agents().isEmpty()) {
            return "planner returned no agents";
        }
        this.currentProblem = problem;
        this.currentPlan = plan;
        return renderPlan(plan);
    }

    @Command(name = "run", group = "Plan", description = "Execute the most recently created plan.")
    public String run() {
        if (currentPlan == null) {
            return "no plan — run 'plan <problem>' first";
        }
        return execute(currentProblem, currentPlan);
    }

    @Command(name = "solve", group = "Plan", description = "Plan and execute in one go.")
    public String solve(@Argument(index = 0, description = "Problem statement.") String problem) {
        String planOutput = plan(problem);
        String runOutput = run();
        return planOutput + "\n\n" + runOutput;
    }

    private String execute(String problem, Plan plan) {
        String input = problem;
        String output = null;
        int total = plan.agents().size();
        int step = 1;
        for (AgentSpec agent : plan.agents()) {
            IO.println("");
            IO.println("─── step " + step + "/" + total + ": " + agent.name() + " (calling LLM…) ───");
            long t0 = System.currentTimeMillis();
            output = executor.prompt()
                    .system(agent.systemPrompt())
                    .user(input)
                    .call()
                    .content();
            long ms = System.currentTimeMillis() - t0;
            IO.println("[" + agent.name() + " done in " + ms + " ms]");
            IO.println(output);
            input = output;
            step++;
        }
        return "═══ finished " + total + " agents ═══";
    }

    private String renderPlan(Plan plan) {
        StringBuilder sb = new StringBuilder("plan (").append(plan.agents().size()).append(" agents):\n");
        int i = 1;
        for (AgentSpec a : plan.agents()) {
            sb.append("  [").append(i++).append("] ").append(a.name()).append("\n")
              .append("      ").append(preview(a.systemPrompt())).append("\n");
        }
        return sb.toString();
    }

    private static String preview(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 180 ? oneLine.substring(0, 180) + "…" : oneLine;
    }
}

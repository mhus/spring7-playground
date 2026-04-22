package de.mhus.spring7.aiplan;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class PlanCommands {

    private static final String PLANNER_SYSTEM = """
            You design a linear pipeline of specialist sub-agents that together solve the user's problem.
            Each agent is a single LLM call with a system prompt.
            The output of agent N becomes the user input for agent N+1.
            The first agent receives the original problem statement as its user input.
            The last agent's output is the final deliverable.

            You MUST use the openQuestions field (and return an empty agents list) whenever ANY of these are true:
            - Target audience is not specified.
            - Output format, medium, or length is not specified.
            - Tone, style, or language is not specified.
            - Domain/subject is vague ("something nice", "a text", "an article") without a concrete topic.
            - There are ambiguous terms that could reasonably mean different things.
            - You would otherwise have to guess or invent constraints to produce a good plan.

            Only return a populated `agents` list when the problem is concrete enough that two competent
            humans would plan it the same way. If you feel tempted to "assume reasonable defaults",
            ask instead.

            Ask 1 to 4 questions at a time. Be specific: each question targets ONE missing piece of
            information and is answerable in one sentence. Do not ask open-ended or philosophical questions.

            Planning rules (only when you are returning agents):
            - 2 to 5 agents. Fewer is usually better.
            - Each agent has a distinct, focused role.
            - Each systemPrompt is self-contained and specific: role, expected input, exact output format.
              No meta-commentary. Do not mention other agents inside a systemPrompt.

            Examples of when to ask vs plan:
            - "Write a text about horses" → ASK (length? audience? tone? angle?)
            - "Write a 300-word neutral introductory article about horses for a children's magazine" → PLAN
            - "Make me something nice" → ASK (what kind of thing? for whom?)
            """;

    private static final String FORCE_SYSTEM = """
            You design a linear pipeline of specialist sub-agents. Same output rules as the planner:
            2-5 agents, self-contained systemPrompts, no meta-commentary.

            The user has decided NOT to answer further clarifying questions.
            You MUST return a populated `agents` list and an EMPTY `openQuestions` list.
            Make reasonable, explicit assumptions for any missing information and reflect them
            clearly inside the relevant systemPrompts (e.g. "assume audience: general adults").
            Do not ask questions. Do not refuse.
            """;

    private static final String REFINE_SYSTEM = """
            You revise an existing agent pipeline based on a user instruction.
            Apply the user's change to the current plan and return the updated plan.
            Keep agents not affected by the instruction unchanged.
            Same output rules as the initial planner: 2-5 agents, self-contained systemPrompts.
            If the instruction is ambiguous, return openQuestions instead of guessing.
            """;

    private final ChatClient planner;
    private final ChatClient executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String currentProblem;
    private volatile Plan currentPlan;
    private final List<String> clarifications = new CopyOnWriteArrayList<>();
    private final List<String> baseSettings = new CopyOnWriteArrayList<>();

    public PlanCommands(ChatClient.Builder builder) {
        this.planner = builder.build();
        this.executor = builder.build();
    }

    @Command(name = "plan", group = "Plan", description = "Design a pipeline of sub-agents for the given problem.")
    public String plan(@Argument(index = 0, description = "Problem statement, e.g. 'Create a text about horses'.") String problem) {
        this.currentProblem = problem;
        this.clarifications.clear();
        return invokePlanner(PLANNER_SYSTEM, buildInitialUserPrompt());
    }

    @Command(name = "answer", group = "Plan", description = "Answer the planner's open questions and re-plan.")
    public String answer(@Argument(index = 0, description = "Your answer(s) to the open questions.") String answer) {
        if (currentPlan == null || !currentPlan.hasQuestions()) {
            return "no open questions — run 'plan <problem>' first";
        }
        StringBuilder block = new StringBuilder();
        for (String q : currentPlan.openQuestions()) {
            block.append("Q: ").append(q).append("\n");
        }
        block.append("A: ").append(answer);
        clarifications.add(block.toString());
        return invokePlanner(PLANNER_SYSTEM, buildInitialUserPrompt());
    }

    @Command(name = "set", group = "Plan", description = "Add a base setting that applies to all plans (e.g. 'sprache: deutsch').")
    public String set(@Argument(index = 0, description = "The setting, e.g. 'sprache: deutsch' or 'tonalität: sachlich'.") String setting) {
        baseSettings.add(setting.strip());
        return "base settings now: " + renderSettings();
    }

    @Command(name = "settings", group = "Plan", description = "Show current base settings.")
    public String settings() {
        return baseSettings.isEmpty() ? "(no base settings)" : renderSettings();
    }

    @Command(name = "unset", group = "Plan", description = "Clear all base settings.")
    public String unset() {
        int n = baseSettings.size();
        baseSettings.clear();
        return "cleared " + n + " base settings";
    }

    @Command(name = "force", group = "Plan", description = "Skip remaining questions and let the planner make assumptions.")
    public String force() {
        if (currentProblem == null) {
            return "no problem — run 'plan <problem>' first";
        }
        return invokePlanner(FORCE_SYSTEM, buildInitialUserPrompt());
    }

    @Command(name = "refine", group = "Plan", description = "Adjust the current plan via a natural-language instruction.")
    public String refine(@Argument(index = 0, description = "Change instruction, e.g. 'add a fact-checker before the writer'.") String instruction) {
        if (currentPlan == null) {
            return "no plan — run 'plan <problem>' first";
        }
        String planJson;
        try {
            planJson = objectMapper.writeValueAsString(currentPlan);
        } catch (JsonProcessingException e) {
            return "ERROR serializing current plan: " + e.getMessage();
        }
        String user = """
                Original problem:
                %s

                Current plan (JSON):
                %s

                User instruction:
                %s
                """.formatted(currentProblem, planJson, instruction);
        return invokePlanner(REFINE_SYSTEM, user);
    }

    @Command(name = "run", group = "Plan", description = "Execute the most recently created plan.")
    public String run() {
        if (currentPlan == null) {
            return "no plan — run 'plan <problem>' first";
        }
        if (!currentPlan.hasAgents()) {
            return "plan has no agents — answer open questions or refine first";
        }
        return execute(currentProblem, currentPlan);
    }

    @Command(name = "solve", group = "Plan", description = "Plan and execute in one go (skips refine/answer).")
    public String solve(@Argument(index = 0, description = "Problem statement.") String problem) {
        String planOutput = plan(problem);
        if (currentPlan == null || !currentPlan.hasAgents()) {
            return planOutput + "\n\n(planner needs clarification — use 'answer' to continue)";
        }
        String runOutput = run();
        return planOutput + "\n\n" + runOutput;
    }

    private String invokePlanner(String system, String user) {
        IO.println("[planner calling LLM…]");
        long t0 = System.currentTimeMillis();
        Plan plan = planner.prompt()
                .system(system)
                .user(user)
                .call()
                .entity(Plan.class);
        IO.println("[planner done in " + (System.currentTimeMillis() - t0) + " ms]");
        if (plan == null) {
            return "planner returned nothing";
        }
        this.currentPlan = plan;
        if (plan.hasQuestions()) {
            return renderQuestions(plan);
        }
        if (!plan.hasAgents()) {
            return "planner returned neither agents nor questions";
        }
        return renderPlan(plan);
    }

    private String buildInitialUserPrompt() {
        if (clarifications.isEmpty() && baseSettings.isEmpty()) {
            return currentProblem;
        }
        StringBuilder sb = new StringBuilder();
        if (!baseSettings.isEmpty()) {
            sb.append("Base settings (always apply, do not ask about these):\n");
            for (String s : baseSettings) {
                sb.append("- ").append(s).append("\n");
            }
            sb.append("\n");
        }
        sb.append("Original problem:\n").append(currentProblem).append("\n");
        if (!clarifications.isEmpty()) {
            sb.append("\nClarifications from user:\n");
            for (String c : clarifications) {
                sb.append(c).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String renderSettings() {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (String s : baseSettings) {
            sb.append("\n  [").append(i++).append("] ").append(s);
        }
        return sb.toString();
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

    private String renderQuestions(Plan plan) {
        StringBuilder sb = new StringBuilder("planner has open questions:\n");
        int i = 1;
        for (String q : plan.openQuestions()) {
            sb.append("  (").append(i++).append(") ").append(q).append("\n");
        }
        sb.append("\nuse: answer \"<your answers>\"");
        return sb.toString();
    }

    private static String preview(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 180 ? oneLine.substring(0, 180) + "…" : oneLine;
    }
}

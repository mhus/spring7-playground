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
            You design a pipeline of specialist sub-agents that together solve the user's problem.

            Each pipeline step has a `type`:
              - "agent":   a single LLM call. Fields: name, systemPrompt.
              - "forEach": run an item-agent over a list of items. Fields:
                           producer   (agent whose output is a list, one item per line)
                           itemAgent  (agent that is called once per item; its user input is the item)
                           collector  (optional agent that merges all item outputs into the final output)

            Sequencing: output of step N becomes user input of step N+1.
            The first step receives the original problem statement as user input.
            The last step's output is the final deliverable.

            Pick forEach ONLY when the problem naturally decomposes into independent sub-tasks
            (e.g. "write 5 chapters", "generate 10 variations", "translate into 3 languages").
            Otherwise stick to plain agent steps.

            You MUST use the openQuestions field (and return an empty steps list) whenever ANY of these are true:
            - Target audience is not specified.
            - Output format, medium, or length is not specified.
            - Tone, style, or language is not specified.
            - Domain/subject is vague without a concrete topic.
            - There are ambiguous terms that could reasonably mean different things.
            - You would otherwise have to guess or invent constraints to produce a good plan.

            Only return populated `steps` when the problem is concrete enough that two competent
            humans would plan it the same way. If tempted to "assume reasonable defaults", ask instead.

            Ask 1 to 4 questions at a time; each targets ONE missing piece and is answerable in one sentence.

            Planning rules (when returning steps):
            - 2 to 5 top-level steps. Fewer is usually better.
            - Each agent/item prompt is self-contained and specific: role, expected input, exact output format.
              No meta-commentary. Do not mention other agents inside a systemPrompt.
            - Fields you don't use (e.g. outputSchema, storeInRag, dependsOn, producer/itemAgent/collector
              for agent steps) may be omitted.

            Examples:
            - "Write a 300-word neutral intro article about horses for adults" →
              steps: [agent Outliner, agent Writer, agent Lector]
            - "Write a 5-chapter short fantasy novel, each chapter ~500 words" →
              steps: [agent Outliner (plot+characters+chapter list), forEach (producer=ChapterLister,
                      itemAgent=ChapterWriter, collector=FinalAssembler), agent Lector]
            """;

    private static final String FORCE_SYSTEM = """
            You design a pipeline of specialist sub-agents. Same rules and step types as the planner.
            The user has decided NOT to answer further clarifying questions.
            You MUST return a populated `steps` list and an EMPTY `openQuestions` list.
            Make reasonable, explicit assumptions for any missing information and reflect them
            clearly inside the relevant systemPrompts. Do not ask questions. Do not refuse.
            """;

    private static final String REFINE_SYSTEM = """
            You revise an existing agent pipeline based on a user instruction.
            Apply the user's change to the current plan and return the updated plan.
            Keep steps not affected by the instruction unchanged.
            Same output rules and step types as the initial planner.
            If the instruction is ambiguous, return openQuestions instead of guessing.
            """;

    private final ChatClient planner;
    private final PipelineExecutor pipelineExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String currentProblem;
    private volatile Plan currentPlan;
    private final List<String> clarifications = new CopyOnWriteArrayList<>();
    private final List<String> baseSettings = new CopyOnWriteArrayList<>();

    public PlanCommands(ChatClient.Builder builder, PipelineExecutor pipelineExecutor) {
        this.planner = builder.build();
        this.pipelineExecutor = pipelineExecutor;
    }

    @Command(name = "plan", group = "Plan", description = "Design a pipeline of sub-agents for the given problem.")
    public String plan(@Argument(index = 0, description = "Problem statement.") String problem) {
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
    public String set(@Argument(index = 0, description = "The setting.") String setting) {
        baseSettings.add(setting.strip());
        return "base settings now:" + renderSettings();
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
    public String refine(@Argument(index = 0, description = "Change instruction.") String instruction) {
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
        if (!currentPlan.hasSteps()) {
            return "plan has no steps — answer, force or refine first";
        }
        ExecutionContext ctx = new ExecutionContext(currentProblem, List.copyOf(baseSettings));
        pipelineExecutor.execute(currentPlan, ctx);
        return "═══ finished " + currentPlan.steps().size() + " steps ═══";
    }

    @Command(name = "solve", group = "Plan", description = "Plan and execute in one go (skips refine/answer).")
    public String solve(@Argument(index = 0, description = "Problem statement.") String problem) {
        String planOutput = plan(problem);
        if (currentPlan == null || !currentPlan.hasSteps()) {
            return planOutput + "\n\n(planner needs clarification — use 'answer' or 'force' to continue)";
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
        if (!plan.hasSteps()) {
            return "planner returned neither steps nor questions";
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

    private String renderPlan(Plan plan) {
        StringBuilder sb = new StringBuilder("plan (").append(plan.steps().size()).append(" steps):\n");
        int i = 1;
        for (PipelineStep s : plan.steps()) {
            sb.append("  [").append(i++).append("] ").append(s.type()).append(" · ").append(s.name()).append("\n");
            if (s.isAgent()) {
                sb.append("      ").append(preview(s.systemPrompt())).append("\n");
            } else if (s.isForEach()) {
                if (s.producer() != null)
                    sb.append("      producer ▸ ").append(s.producer().name()).append(": ")
                      .append(preview(s.producer().systemPrompt())).append("\n");
                if (s.itemAgent() != null)
                    sb.append("      item     ▸ ").append(s.itemAgent().name()).append(": ")
                      .append(preview(s.itemAgent().systemPrompt())).append("\n");
                if (s.collector() != null)
                    sb.append("      collect  ▸ ").append(s.collector().name()).append(": ")
                      .append(preview(s.collector().systemPrompt())).append("\n");
            }
        }
        return sb.toString();
    }

    private String renderQuestions(Plan plan) {
        StringBuilder sb = new StringBuilder("planner has open questions:\n");
        int i = 1;
        for (String q : plan.openQuestions()) {
            sb.append("  (").append(i++).append(") ").append(q).append("\n");
        }
        sb.append("\nuse: answer \"<your answers>\"  or  force");
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

    private static String preview(String text) {
        if (text == null) return "";
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 160 ? oneLine.substring(0, 160) + "…" : oneLine;
    }
}

package de.mhus.spring7.aiassistant.tools;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.AssistantTools;
import de.mhus.spring7.aiassistant.plan.BaseSettings;
import de.mhus.spring7.aiassistant.storage.TokenTracker;

/**
 * Delegates a task to a fresh LLM conversation — no chat memory, no RAG advisor, but the same
 * toolset as the main assistant (including orchestrate, so a sub-task can spawn a plan).
 *
 * SubtaskTool itself is not an AgentTool: it is registered only on the main chat client, which
 * prevents sub-tasks from recursively calling themselves. They can still call orchestrate, which
 * leads to bounded recursion (orchestrate runs a pipeline, not more sub-tasks).
 *
 * The tool-call loop runs manually: {@code internalToolExecutionEnabled=false} on the options,
 * we iterate until either the LLM emits no more tool calls or {@code ai.subtask.max-iterations}
 * is reached, whichever comes first.
 */
@Component
public class SubtaskTool {

    private static final String DEFAULT_SYSTEM = """
            You are a task executor. You receive one self-contained task and produce a single
            final answer. Use the available tools as needed to investigate, compute or fetch.
            Do not ask the user questions — make reasonable assumptions and state them in your
            answer. Respond with the final answer only, no thinking out loud.
            """;

    private final ChatClient subChat;
    private final ToolCallingManager toolCallingManager;
    private final BaseSettings baseSettings;
    private final TokenTracker tokens;
    private final ToolCallback[] toolCallbacks;
    private final int maxIterations;

    public SubtaskTool(ChatClient.Builder builder,
                       List<AgentTool> agentTools,
                       AssistantTools assistantTools,
                       @Lazy OrchestrateTool orchestrateTool,
                       ToolCallingManager toolCallingManager,
                       BaseSettings baseSettings,
                       TokenTracker tokens,
                       @Value("${ai.subtask.max-iterations:10}") int maxIterations) {
        this.toolCallingManager = toolCallingManager;
        this.baseSettings = baseSettings;
        this.tokens = tokens;
        this.maxIterations = maxIterations;

        List<Object> tools = new ArrayList<>();
        tools.add(assistantTools);
        tools.addAll(agentTools);
        tools.add(orchestrateTool);
        this.toolCallbacks = ToolCallbacks.from(tools.toArray());
        // no default advisors → fresh context, no memory, no RAG injection
        this.subChat = builder
                .defaultToolCallbacks(toolCallbacks)
                .defaultOptions(ToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false)
                        .build())
                .build();
    }

    @Tool(description = """
            Delegate a self-contained task to a fresh sub-LLM with the same tools you have.
            The sub-LLM has NO memory of the current conversation. Pass everything it needs
            explicitly via 'task' and optionally 'context'. Use this for side-errands whose
            intermediate reasoning should not pollute the main conversation — e.g. "search for
            all TODOs in /src and classify them", "run this benchmark and summarize the result",
            "analyze this log file for anomalies". You receive only the sub-LLM's final answer.
            """)
    public String subtask(
            @ToolParam(description = "What the sub-LLM should do — complete and self-contained.")
            String task,
            @ToolParam(description = "Optional background facts. Summarize only what's relevant.", required = false)
            String context,
            @ToolParam(description = "Optional system-prompt override to specialize the sub-LLM's persona.", required = false)
            String persona) {
        String system = composeSystemPrompt(persona, context);
        List<Message> conversation = new ArrayList<>();
        conversation.add(new SystemMessage(system));
        conversation.add(new UserMessage(task));

        for (int i = 0; i < maxIterations; i++) {
            ChatResponse resp = subChat.prompt()
                    .messages(conversation)
                    .call()
                    .chatResponse();
            tokens.record("subtask", resp.getMetadata().getUsage());
            AssistantMessage ai = resp.getResult().getOutput();

            if (ai.getToolCalls() == null || ai.getToolCalls().isEmpty()) {
                return ai.getText();
            }

            Prompt execPrompt = new Prompt(conversation,
                    ToolCallingChatOptions.builder()
                            .toolCallbacks(toolCallbacks)
                            .internalToolExecutionEnabled(false)
                            .build());
            ToolExecutionResult result = toolCallingManager.executeToolCalls(execPrompt, resp);
            conversation = new ArrayList<>(result.conversationHistory());
        }
        return "subtask aborted: max-iterations (" + maxIterations + ") reached. "
                + "The sub-LLM kept calling tools. Consider breaking the task down further.";
    }

    private String composeSystemPrompt(String persona, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append(persona == null || persona.isBlank() ? DEFAULT_SYSTEM : persona);
        if (!baseSettings.isEmpty()) {
            sb.append("\n\n").append(baseSettings.renderBlock());
        }
        if (context != null && !context.isBlank()) {
            sb.append("\n\nKnown context:\n").append(context);
        }
        return sb.toString();
    }
}

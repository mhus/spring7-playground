package de.mhus.spring7.aiassistant.tools;

import java.time.Duration;
import java.time.Instant;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ExecuteTool implements AgentTool {

    static final long DEFAULT_WAIT_MS = 15_000;
    static final int MAX_OUTPUT_CHARS = 8_000;

    private final ExecManager exec;

    public ExecuteTool(ExecManager exec) {
        this.exec = exec;
    }

    @Tool(description = """
            Execute a shell command (bash via /bin/sh -c on Linux/macOS, cmd.exe /c on Windows).
            The command runs in the background. This call waits up to 15 seconds.
            If the command finishes within that window, you get status + stdout + stderr.
            If it is still running, you get its task id — call getTaskOutput(taskId) later to
            check progress. Output is truncated at ~8 KB per stream.
            """)
    public String executeCommand(
            @ToolParam(description = "The shell command to run. Full shell syntax (pipes, redirection, env vars) is allowed.")
            String command) {
        String id = exec.submit(command);
        ExecJob job = exec.waitFor(id, DEFAULT_WAIT_MS);
        return renderJob(job);
    }

    @Tool(description = "Check status and output of a previously started exec task.")
    public String getTaskOutput(
            @ToolParam(description = "Task id returned by executeCommand.")
            String taskId) {
        ExecJob job = exec.get(taskId);
        if (job == null) return "task not found: " + taskId;
        return renderJob(job);
    }

    @Tool(description = "Kill a still-running exec task.")
    public String killTask(
            @ToolParam(description = "Task id.")
            String taskId) {
        boolean ok = exec.kill(taskId);
        return ok ? "killed " + taskId : "could not kill (not found or already done)";
    }

    static String renderJob(ExecJob job) {
        if (job == null) return "(no job)";
        StringBuilder sb = new StringBuilder();
        sb.append("id    : ").append(job.id()).append("\n");
        sb.append("status: ").append(job.status()).append("\n");
        sb.append("cmd   : ").append(job.command()).append("\n");
        Instant end = job.finishedAt() != null ? job.finishedAt() : Instant.now();
        sb.append("dur   : ").append(Duration.between(job.startedAt(), end).toMillis()).append(" ms\n");
        if (job.exitCode() != null) sb.append("exit  : ").append(job.exitCode()).append("\n");
        String out = job.readStdout();
        String err = job.readStderr();
        if (!out.isEmpty()) sb.append("stdout:\n").append(truncate(out)).append("\n");
        if (!err.isEmpty()) sb.append("stderr:\n").append(truncate(err)).append("\n");
        if (out.isEmpty() && err.isEmpty()) sb.append("(no output yet)\n");
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_OUTPUT_CHARS) return s;
        return s.substring(0, MAX_OUTPUT_CHARS) + "\n…[truncated, " + (s.length() - MAX_OUTPUT_CHARS) + " more chars]";
    }
}

package de.mhus.spring7.aiassistant.tools;

import java.time.Duration;
import java.time.Instant;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.storage.StorageService;

@Component
public class ExecuteTool implements AgentTool {

    static final long DEFAULT_WAIT_MS = 15_000;
    static final int MAX_OUTPUT_CHARS = 8_000;

    private final ExecManager exec;
    private final StorageService storage;

    public ExecuteTool(ExecManager exec, StorageService storage) {
        this.exec = exec;
        this.storage = storage;
    }

    @Tool(description = """
            Execute a shell command (bash via /bin/sh -c on Linux/macOS, cmd.exe /c on Windows).
            The command runs in the background. This call waits up to 15 seconds.
            If the command finishes within that window, you get status + stdout + stderr.
            If it is still running, you get its task id — call getTaskOutput(taskId) later.
            The tool also returns the full on-disk paths to stdout.log / stderr.log. These are
            plain text files — you can run further shell commands against them (grep, tail,
            sed, wc, awk) via another executeCommand call to search or page through long output
            without re-fetching everything through this tool.

            Inline output is truncated at ~8 KB per stream. If you see "…[truncated, N more chars]"
            in the response, your follow-up MUST bound the output:
              - start with `wc -l <path>` to know the size;
              - use `grep -m N` or `grep PATTERN | head -N` instead of bare grep;
              - use `head -N`, `tail -N`, or `sed -n 'A,Bp' <path>` to page;
              - never re-run the same unbounded command hoping for a smaller result.
            If a single bounded query still truncates, tighten the bound further before trying again.
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

    String renderJob(ExecJob job) {
        if (job == null) return "(no job)";
        StringBuilder sb = new StringBuilder();
        sb.append("id    : ").append(job.id()).append("\n");
        sb.append("status: ").append(job.status()).append("\n");
        sb.append("cmd   : ").append(job.command()).append("\n");
        Instant end = job.finishedAt() != null ? job.finishedAt() : Instant.now();
        sb.append("dur   : ").append(Duration.between(job.startedAt(), end).toMillis()).append(" ms\n");
        if (job.exitCode() != null) sb.append("exit  : ").append(job.exitCode()).append("\n");
        var dir = storage.execJobDir(job.id()).toAbsolutePath();
        sb.append("stdout: ").append(dir.resolve("stdout.log")).append("\n");
        sb.append("stderr: ").append(dir.resolve("stderr.log")).append("\n");
        String out = job.readStdout();
        String err = job.readStderr();
        if (!out.isEmpty()) sb.append("stdout (inline):\n").append(truncate(out)).append("\n");
        if (!err.isEmpty()) sb.append("stderr (inline):\n").append(truncate(err)).append("\n");
        if (out.isEmpty() && err.isEmpty()) sb.append("(no output yet — tail the log files if the job is long-running)\n");
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_OUTPUT_CHARS) return s;
        return s.substring(0, MAX_OUTPUT_CHARS) + "\n…[truncated, " + (s.length() - MAX_OUTPUT_CHARS) + " more chars]";
    }
}

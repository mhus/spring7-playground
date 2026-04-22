package de.mhus.spring7.aiassistant.tools;

import java.time.Duration;
import java.time.Instant;

import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

@Component
public class ExecCommands {

    private final ExecManager exec;
    private final ExecuteTool executeTool;

    public ExecCommands(ExecManager exec, ExecuteTool executeTool) {
        this.exec = exec;
        this.executeTool = executeTool;
    }

    @Command(name = "exec", group = "Exec", description = "Run a shell command. Waits up to 15s, then returns the task id so you can check back.")
    public String execCommand(@Argument(index = 0, description = "Shell command.") String command) {
        String id = exec.submit(command);
        ExecJob job = exec.waitFor(id, ExecuteTool.DEFAULT_WAIT_MS);
        return executeTool.renderJob(job);
    }

    @Command(name = "exec-list", group = "Exec", description = "List all exec tasks (running and finished).")
    public String execList() {
        var jobs = exec.list();
        if (jobs.isEmpty()) return "(no tasks)";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-10s %-10s %-8s %s%n", "id", "status", "ms", "cmd"));
        for (ExecJob j : jobs) {
            Instant end = j.finishedAt() != null ? j.finishedAt() : Instant.now();
            sb.append(String.format("%-10s %-10s %-8d %s%n",
                    j.id(), j.status(),
                    Duration.between(j.startedAt(), end).toMillis(),
                    preview(j.command())));
        }
        return sb.toString();
    }

    @Command(name = "exec-output", group = "Exec", description = "Show full status and output of a task.")
    public String execOutput(@Argument(index = 0, description = "Task id (from exec-list).") String taskId) {
        ExecJob j = exec.get(taskId);
        if (j == null) return "task not found: " + taskId;
        return executeTool.renderJob(j);
    }

    @Command(name = "exec-kill", group = "Exec", description = "Kill a running task.")
    public String execKill(@Argument(index = 0, description = "Task id.") String taskId) {
        boolean ok = exec.kill(taskId);
        return ok ? "killed " + taskId : "not running / not found: " + taskId;
    }

    @Command(name = "exec-wait", group = "Exec", description = "Wait up to N ms for a task to finish and return its state.")
    public String execWait(
            @Argument(index = 0, description = "Task id.") String taskId,
            @Argument(index = 1, description = "Max milliseconds to wait.") String maxMillis) {
        long n;
        try { n = Long.parseLong(maxMillis); }
        catch (NumberFormatException e) { return "invalid milliseconds: " + maxMillis; }
        ExecJob j = exec.waitFor(taskId, n);
        if (j == null) return "task not found: " + taskId;
        return executeTool.renderJob(j);
    }

    private static String preview(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}

package de.mhus.spring7.aiassistant.tools;

import java.time.Instant;

public class ExecJob {

    public enum Status { RUNNING, COMPLETED, FAILED, KILLED }

    private final String id;
    private final String command;
    private final Instant startedAt;
    private volatile Instant finishedAt;
    private volatile Status status = Status.RUNNING;
    private final StringBuilder stdout = new StringBuilder();
    private final StringBuilder stderr = new StringBuilder();
    private volatile Integer exitCode;
    private volatile Process process;

    public ExecJob(String id, String command) {
        this.id = id;
        this.command = command;
        this.startedAt = Instant.now();
    }

    public String id() { return id; }
    public String command() { return command; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public Status status() { return status; }
    public Integer exitCode() { return exitCode; }
    public Process process() { return process; }

    public void setStatus(Status s) { this.status = s; }
    public void setFinishedAt(Instant t) { this.finishedAt = t; }
    public void setExitCode(Integer c) { this.exitCode = c; }
    public void setProcess(Process p) { this.process = p; }

    public void appendStdout(String line) {
        synchronized (stdout) { stdout.append(line).append('\n'); }
    }

    public void appendStderr(String line) {
        synchronized (stderr) { stderr.append(line).append('\n'); }
    }

    public String readStdout() {
        synchronized (stdout) { return stdout.toString(); }
    }

    public String readStderr() {
        synchronized (stderr) { return stderr.toString(); }
    }
}

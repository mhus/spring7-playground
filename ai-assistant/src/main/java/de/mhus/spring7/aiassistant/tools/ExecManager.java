package de.mhus.spring7.aiassistant.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

/**
 * Runs shell commands in background threads. Each submission gets a short id; the command is
 * launched immediately and the job object tracks status + captured output. Callers can wait
 * with a timeout, query the state later, or kill the process.
 *
 * Uses {@code /bin/sh -c} on Unix-like systems and {@code cmd.exe /c} on Windows.
 */
@Service
public class ExecManager {

    private final Map<String, ExecJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "exec-worker");
        t.setDaemon(true);
        return t;
    });

    public String submit(String command) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        ExecJob job = new ExecJob(id, command);
        jobs.put(id, job);
        workers.submit(() -> runJob(job));
        return id;
    }

    public ExecJob get(String id) {
        return jobs.get(id);
    }

    public Collection<ExecJob> list() {
        return jobs.values();
    }

    public ExecJob waitFor(String id, long maxMillis) {
        ExecJob j = jobs.get(id);
        if (j == null) return null;
        long deadline = System.currentTimeMillis() + maxMillis;
        while (j.status() == ExecJob.Status.RUNNING && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return j;
    }

    public boolean kill(String id) {
        ExecJob j = jobs.get(id);
        if (j == null || j.status() != ExecJob.Status.RUNNING) return false;
        Process p = j.process();
        if (p == null) return false;
        p.destroyForcibly();
        j.setStatus(ExecJob.Status.KILLED);
        j.setFinishedAt(Instant.now());
        return true;
    }

    @PreDestroy
    void shutdown() {
        for (ExecJob j : jobs.values()) {
            if (j.status() == ExecJob.Status.RUNNING && j.process() != null) {
                j.process().destroyForcibly();
            }
        }
        workers.shutdownNow();
    }

    private void runJob(ExecJob job) {
        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("cmd.exe", "/c", job.command())
                    : new ProcessBuilder("/bin/sh", "-c", job.command());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            job.setProcess(p);

            Thread out = pump(p.getInputStream(), line -> job.appendStdout(line));
            Thread err = pump(p.getErrorStream(), line -> job.appendStderr(line));
            out.start(); err.start();

            int code = p.waitFor();
            out.join(); err.join();

            job.setExitCode(code);
            job.setStatus(code == 0 ? ExecJob.Status.COMPLETED : ExecJob.Status.FAILED);
        } catch (Exception e) {
            job.appendStderr("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            job.setStatus(ExecJob.Status.FAILED);
        } finally {
            job.setFinishedAt(Instant.now());
        }
    }

    private static Thread pump(InputStream in, java.util.function.Consumer<String> sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.accept(line);
                }
            } catch (IOException ignored) {}
        });
        t.setDaemon(true);
        return t;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

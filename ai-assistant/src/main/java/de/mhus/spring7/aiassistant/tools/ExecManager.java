package de.mhus.spring7.aiassistant.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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

import de.mhus.spring7.aiassistant.storage.ExecJobDto;
import de.mhus.spring7.aiassistant.storage.StorageService;

/**
 * Runs shell commands in background threads with live output persistence.
 * <p>
 * Per job: {@code exec/<id>/job.json} (metadata, updated on state changes) and
 * {@code stdout.log} / {@code stderr.log} (streamed line-by-line with flush).
 * Surviving JVM crashes means partial output is preserved — RUNNING jobs found on disk are
 * marked FAILED when reloaded since their process is gone.
 */
@Service
public class ExecManager {

    private final Map<String, ExecJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "exec-worker");
        t.setDaemon(true);
        return t;
    });
    private final StorageService storage;

    public ExecManager(StorageService storage) {
        this.storage = storage;
    }

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
        saveMeta(j);
        return true;
    }

    public void reloadFromStorage() {
        jobs.clear();
        for (ExecJobDto dto : storage.loadExecJobMetas()) {
            ExecJob job = new ExecJob(dto.id(), dto.command());
            if (dto.finishedAt() != null) {
                job.setFinishedAt(Instant.parse(dto.finishedAt()));
            }
            ExecJob.Status status;
            try { status = ExecJob.Status.valueOf(dto.status()); }
            catch (IllegalArgumentException e) { status = ExecJob.Status.FAILED; }
            // running on disk = orphaned (process is gone)
            if (status == ExecJob.Status.RUNNING) status = ExecJob.Status.FAILED;
            job.setStatus(status);
            job.setExitCode(dto.exitCode());
            String out = storage.readExecStdout(job.id());
            String err = storage.readExecStderr(job.id());
            for (String line : out.split("\n", -1)) if (!line.isEmpty()) job.appendStdout(line);
            for (String line : err.split("\n", -1)) if (!line.isEmpty()) job.appendStderr(line);
            jobs.put(job.id(), job);
        }
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
        saveMeta(job); // initial: RUNNING
        try (BufferedWriter stdoutW = storage.openExecStdout(job.id());
             BufferedWriter stderrW = storage.openExecStderr(job.id())) {

            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("cmd.exe", "/c", job.command())
                    : new ProcessBuilder("/bin/sh", "-c", job.command());
            pb.redirectErrorStream(false);
            Process p = pb.start();
            job.setProcess(p);

            Thread out = pump(p.getInputStream(), line -> {
                job.appendStdout(line);
                writeLine(stdoutW, line);
            });
            Thread err = pump(p.getErrorStream(), line -> {
                job.appendStderr(line);
                writeLine(stderrW, line);
            });
            out.start(); err.start();

            int code = p.waitFor();
            out.join(); err.join();

            job.setExitCode(code);
            job.setStatus(code == 0 ? ExecJob.Status.COMPLETED : ExecJob.Status.FAILED);
        } catch (Exception e) {
            String msg = "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            job.appendStderr(msg);
            job.setStatus(ExecJob.Status.FAILED);
        } finally {
            job.setFinishedAt(Instant.now());
            saveMeta(job);
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

    private static void writeLine(BufferedWriter w, String line) {
        synchronized (w) {
            try {
                w.write(line);
                w.newLine();
                w.flush();
            } catch (IOException ignored) {}
        }
    }

    private void saveMeta(ExecJob j) {
        storage.saveExecJobMeta(new ExecJobDto(
                j.id(),
                j.command(),
                j.startedAt().toString(),
                j.finishedAt() != null ? j.finishedAt().toString() : null,
                j.status().name(),
                j.exitCode()
        ));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

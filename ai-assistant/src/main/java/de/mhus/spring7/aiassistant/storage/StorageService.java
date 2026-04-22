package de.mhus.spring7.aiassistant.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import de.mhus.spring7.aiassistant.plan.StoredPlan;

/**
 * Filesystem-backed session storage under ./data/sessions/&lt;uuid&gt;/.
 * A new session UUID is minted on startup. Resume via {@link #switchSession(String)}.
 */
@Service
public class StorageService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path baseDir = Path.of("data", "sessions");
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private volatile String currentSessionId;

    @PostConstruct
    void init() {
        switchSession(UUID.randomUUID().toString());
    }

    public String currentSession() {
        return currentSessionId;
    }

    public Path sessionDir() {
        return baseDir.resolve(currentSessionId);
    }

    public void switchSession(String id) {
        this.currentSessionId = id;
        try {
            Files.createDirectories(sessionDir().resolve("runs"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        touchMeta();
    }

    public List<String> listSessions() {
        if (!Files.isDirectory(baseDir)) return List.of();
        try (Stream<Path> s = Files.list(baseDir)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public SessionMeta meta(String id) {
        Path f = baseDir.resolve(id).resolve("session.json");
        if (!Files.isRegularFile(f)) {
            return new SessionMeta(id, "?", "?", 0, 0, null);
        }
        try {
            return json.readValue(Files.readString(f), SessionMeta.class);
        } catch (IOException e) {
            return new SessionMeta(id, "?", "?", 0, 0, null);
        }
    }

    // ---- chat memory ----

    public void saveChatMemory(List<ChatMessageDto> messages) {
        writeJson(sessionDir().resolve("chat.json"), messages);
        updateMeta(m -> new SessionMeta(m.id(), m.createdAt(), nowIso(), messages.size(), m.runCount(), m.currentPlanName()));
    }

    public List<ChatMessageDto> loadChatMemory() {
        Path f = sessionDir().resolve("chat.json");
        if (!Files.isRegularFile(f)) return List.of();
        try {
            ChatMessageDto[] arr = json.readValue(Files.readString(f), ChatMessageDto[].class);
            return new ArrayList<>(List.of(arr));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- plans (named) ----

    public void savePlan(String name, StoredPlan plan) {
        writeJson(plansDir().resolve(name + ".json"), plan);
    }

    public StoredPlan loadPlan(String name) {
        Path f = plansDir().resolve(name + ".json");
        if (!Files.isRegularFile(f)) return null;
        try {
            return json.readValue(Files.readString(f), StoredPlan.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean deletePlan(String name) {
        try {
            return Files.deleteIfExists(plansDir().resolve(name + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean renamePlan(String oldName, String newName) {
        Path from = plansDir().resolve(oldName + ".json");
        Path to = plansDir().resolve(newName + ".json");
        if (!Files.isRegularFile(from) || Files.exists(to)) return false;
        try {
            Files.move(from, to);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> listPlans() {
        Path d = plansDir();
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String currentPlanName() {
        return meta(currentSessionId).currentPlanName();
    }

    public void setCurrentPlanName(String name) {
        updateMeta(m -> new SessionMeta(m.id(), m.createdAt(), nowIso(), m.messageCount(), m.runCount(), name));
    }

    private Path plansDir() {
        Path d = sessionDir().resolve("plans");
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return d;
    }

    // ---- run log ----

    /**
     * Runs {@code action} with System.out tee'd into a session run-log file.
     * IO.println writes to System.out, so they are captured transparently.
     */
    public void runWithLogging(Runnable action) {
        String ts = LocalDateTime.now().format(TS);
        Path f = sessionDir().resolve("runs").resolve(ts + ".log");
        PrintStream originalOut = System.out;
        try {
            Files.createDirectories(f.getParent());
            try (OutputStream file = Files.newOutputStream(f);
                 PrintStream tee = new PrintStream(new TeeOutputStream(originalOut, file), true, StandardCharsets.UTF_8)) {
                System.setOut(tee);
                updateMeta(m -> new SessionMeta(m.id(), m.createdAt(), nowIso(), m.messageCount(), m.runCount() + 1, m.currentPlanName()));
                action.run();
            } finally {
                System.setOut(originalOut);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;
        TeeOutputStream(OutputStream a, OutputStream b) { this.a = a; this.b = b; }
        @Override public void write(int c) throws IOException { a.write(c); b.write(c); }
        @Override public void write(byte[] buf, int off, int len) throws IOException { a.write(buf, off, len); b.write(buf, off, len); }
        @Override public void flush() throws IOException { a.flush(); b.flush(); }
        @Override public void close() { /* caller closes originals */ }
    }

    // ---- exec ----

    public Path execJobDir(String id) {
        Path d = sessionDir().resolve("exec").resolve(id);
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return d;
    }

    public void saveExecJobMeta(ExecJobDto dto) {
        writeJson(execJobDir(dto.id()).resolve("job.json"), dto);
    }

    public java.io.BufferedWriter openExecStdout(String id) throws IOException {
        return Files.newBufferedWriter(execJobDir(id).resolve("stdout.log"),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    public java.io.BufferedWriter openExecStderr(String id) throws IOException {
        return Files.newBufferedWriter(execJobDir(id).resolve("stderr.log"),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    public String readExecStdout(String id) {
        return readOptional(execJobDir(id).resolve("stdout.log"));
    }

    public String readExecStderr(String id) {
        return readOptional(execJobDir(id).resolve("stderr.log"));
    }

    public List<ExecJobDto> loadExecJobMetas() {
        Path d = sessionDir().resolve("exec");
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(Files::isDirectory)
                    .map(jobDir -> jobDir.resolve("job.json"))
                    .filter(Files::isRegularFile)
                    .map(this::readExecJob)
                    .filter(j -> j != null)
                    .sorted(Comparator.comparing(ExecJobDto::startedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ExecJobDto readExecJob(Path p) {
        try {
            return json.readValue(Files.readString(p), ExecJobDto.class);
        } catch (IOException e) {
            return null;
        }
    }

    private String readOptional(Path p) {
        if (!Files.isRegularFile(p)) return "";
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // ---- rag ----

    public void persistRag(VectorStore vs, List<Document> assistantDocs, List<Document> planDocs) {
        Path ragDir = sessionDir().resolve("rag");
        try {
            Files.createDirectories(ragDir);
            if (vs instanceof SimpleVectorStore svs) {
                svs.save(ragDir.resolve("vectors.json").toFile());
            }
            writeJson(ragDir.resolve("assistant-docs.json"),
                    assistantDocs.stream().map(StorageService::toDto).toList());
            writeJson(ragDir.resolve("plan-docs.json"),
                    planDocs.stream().map(StorageService::toDto).toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean loadRagVectors(VectorStore vs) {
        Path p = sessionDir().resolve("rag").resolve("vectors.json");
        if (!Files.isRegularFile(p) || !(vs instanceof SimpleVectorStore svs)) return false;
        svs.load(p.toFile());
        return true;
    }

    public List<Document> loadAssistantDocs() {
        return loadDocs(sessionDir().resolve("rag").resolve("assistant-docs.json"));
    }

    public List<Document> loadPlanDocs() {
        return loadDocs(sessionDir().resolve("rag").resolve("plan-docs.json"));
    }

    private List<Document> loadDocs(Path f) {
        if (!Files.isRegularFile(f)) return List.of();
        try {
            DocumentDto[] arr = json.readValue(Files.readString(f), DocumentDto[].class);
            return java.util.Arrays.stream(arr)
                    .map(d -> Document.builder().id(d.id()).text(d.text())
                            .metadata(d.metadata() == null ? java.util.Map.of() : d.metadata())
                            .build())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DocumentDto toDto(Document d) {
        return new DocumentDto(d.getId(), d.getText(), d.getMetadata());
    }

    // ---- meta helpers ----

    private void touchMeta() {
        Path f = sessionDir().resolve("session.json");
        if (Files.isRegularFile(f)) {
            updateMeta(m -> new SessionMeta(m.id(), m.createdAt(), nowIso(), m.messageCount(), m.runCount(), m.currentPlanName()));
        } else {
            String now = nowIso();
            writeJson(f, new SessionMeta(currentSessionId, now, now, 0, 0, null));
        }
    }

    private void updateMeta(java.util.function.Function<SessionMeta, SessionMeta> fn) {
        SessionMeta current = meta(currentSessionId);
        writeJson(sessionDir().resolve("session.json"), fn.apply(current));
    }

    private void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json.writeValueAsString(value), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String nowIso() {
        return Instant.now().atZone(ZoneId.systemDefault()).toLocalDateTime().toString();
    }

    public long countRuns() {
        Path d = sessionDir().resolve("runs");
        if (!Files.isDirectory(d)) return 0;
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    public long countMessages() {
        Path f = sessionDir().resolve("chat.json");
        if (!Files.isRegularFile(f)) return 0;
        return loadChatMemory().size();
    }

    // ---- pins ----

    public void savePins(List<String> pins) {
        writeJson(sessionDir().resolve("pins.json"), pins);
    }

    public List<String> loadPins() {
        Path f = sessionDir().resolve("pins.json");
        if (!Files.isRegularFile(f)) return List.of();
        try {
            String[] arr = json.readValue(Files.readString(f), String[].class);
            return new ArrayList<>(List.of(arr));
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---- base settings ----

    public void saveBaseSettings(List<String> settings) {
        writeJson(sessionDir().resolve("base-settings.json"), settings);
    }

    public List<String> loadBaseSettings() {
        Path f = sessionDir().resolve("base-settings.json");
        if (!Files.isRegularFile(f)) return List.of();
        try {
            String[] arr = json.readValue(Files.readString(f), String[].class);
            return new ArrayList<>(List.of(arr));
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---- counts for dashboard ----

    public long countWorkspaceFiles() {
        return countFiles(workspaceDir());
    }

    public long countProjectFiles() {
        return countFiles(projectDir());
    }

    private long countFiles(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    public Path workspaceDir() {
        Path d = sessionDir().resolve("workspace");
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return d;
    }

    /**
     * Project-wide, session-independent storage area. Kept under {@code data/project/} at the
     * working directory root. Intended for long-lived notes, references, design docs, config,
     * etc. that the agent should recognize across sessions.
     */
    public Path projectDir() {
        Path d = Path.of("data", "project");
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return d;
    }

    public List<String> listRuns() {
        Path d = sessionDir().resolve("runs");
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}

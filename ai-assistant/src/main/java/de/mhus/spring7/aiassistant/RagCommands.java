package de.mhus.spring7.aiassistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.FileSystemResource;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.plan.SharedRagStore;
import de.mhus.spring7.aiassistant.storage.StorageService;
import de.mhus.spring7.aiassistant.storage.TokenTracker;

@Component
public class RagCommands {

    private static final String GENERATOR_SYSTEM = """
            You generate factual knowledge statements for a retrieval database.
            Follow the user's request precisely (topic, quantity, style).
            Return ONLY the statements, one per line.
            No numbering, no bullets, no markdown, no preamble, no trailing commentary.
            Each line must be self-contained and understandable without context.
            """;

    private final VectorStore vectorStore;
    private final ChatClient generator;
    private final StorageService storage;
    private final SharedRagStore planRag;
    private final TokenTracker tokens;
    private final Map<String, Document> storedDocs = new ConcurrentHashMap<>();

    public RagCommands(VectorStore vectorStore, ChatClient.Builder builder,
                       StorageService storage, @Lazy SharedRagStore planRag, TokenTracker tokens) {
        this.vectorStore = vectorStore;
        this.generator = builder.build();
        this.storage = storage;
        this.planRag = planRag;
        this.tokens = tokens;
    }

    public Collection<Document> storedDocs() {
        return storedDocs.values();
    }

    public void reloadFromStorage() {
        if (!storedDocs.isEmpty()) {
            vectorStore.delete(new ArrayList<>(storedDocs.keySet()));
            storedDocs.clear();
        }
        storage.loadRagVectors(vectorStore);
        for (Document d : storage.loadAssistantDocs()) {
            storedDocs.put(d.getId(), d);
        }
    }

    @Command(name = "import", group = "RAG", description = "Import a PDF file into the RAG vector store.")
    public String importPdf(@Argument(index = 0, description = "Path to the PDF file.") String path) {
        List<Document> docs;
        try {
            docs = new ParagraphPdfDocumentReader(new FileSystemResource(path)).get();
        } catch (Exception paragraphFailure) {
            docs = new PagePdfDocumentReader(new FileSystemResource(path)).get();
        }
        List<Document> chunks = TokenTextSplitter.builder().build().apply(docs);
        vectorStore.add(chunks);
        chunks.forEach(c -> storedDocs.put(c.getId(), c));
        persist();
        return "imported " + chunks.size() + " chunks from " + path;
    }

    @Command(name = "generate", group = "RAG", description = "Generate statements with the LLM and store them as RAG chunks.")
    public String generate(@Argument(index = 0, description = "Instruction, e.g. 'erstelle 40 aussagen zum thema Formel 1'.") String instruction) {
        ChatResponse resp = generator.prompt()
                .system(GENERATOR_SYSTEM)
                .user(instruction)
                .call()
                .chatResponse();
        tokens.record("generate", resp.getMetadata().getUsage());
        String content = resp.getResult().getOutput().getText();
        List<Document> docs = Arrays.stream(content.split("\\R"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> Document.builder().text(s).build())
                .toList();
        if (docs.isEmpty()) {
            return "no statements produced";
        }
        vectorStore.add(docs);
        docs.forEach(d -> storedDocs.put(d.getId(), d));
        persist();
        return "generated and stored " + docs.size() + " statements";
    }

    @Command(name = "ask", group = "RAG", description = "Query the vector store directly. Shows top-K chunks with similarity score (no LLM call).")
    public String ask(@Argument(index = 0, description = "Your query.") String query) {
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(4).build());
        if (hits.isEmpty()) {
            return "(no matches)";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Document d : hits) {
            sb.append(String.format("[%d] score=%.4f  id=%s%n", i++, d.getScore(), d.getId()));
            sb.append("    ").append(preview(d.getText())).append("\n");
        }
        return sb.toString();
    }

    @Command(name = "show", group = "RAG", description = "Dump all stored RAG chunks (previews).")
    public String show() {
        if (storedDocs.isEmpty()) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Document d : storedDocs.values()) {
            sb.append("[").append(i++).append("] ").append(preview(d.getText())).append("\n");
        }
        return sb.toString();
    }

    @Command(name = "docs", group = "RAG", description = "Show how many RAG chunks are stored.")
    public String docs() {
        return "chunks stored: " + storedDocs.size();
    }

    @Command(name = "forget", group = "RAG", description = "Clear the assistant-tracked RAG entries (keeps plan-side).")
    public String forget() {
        if (!storedDocs.isEmpty()) {
            vectorStore.delete(new ArrayList<>(storedDocs.keySet()));
            storedDocs.clear();
            persist();
        }
        return "assistant RAG cleared";
    }

    private void persist() {
        storage.persistRag(vectorStore, new ArrayList<>(storedDocs.values()), planRag.all());
    }

    private static String preview(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 160 ? oneLine.substring(0, 160) + "…" : oneLine;
    }
}

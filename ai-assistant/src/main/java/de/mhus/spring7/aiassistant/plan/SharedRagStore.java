package de.mhus.spring7.aiassistant.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import de.mhus.spring7.aiassistant.RagCommands;
import de.mhus.spring7.aiassistant.storage.StorageService;

/**
 * Tracks documents added via the plan/pipeline. Shares the same VectorStore as the assistant
 * (imported PDFs, generated statements). Clearing here only removes plan-added entries.
 */
@Component
public class SharedRagStore {

    private final VectorStore vectorStore;
    private final StorageService storage;
    private final ObjectProvider<RagCommands> assistantRag;
    private final List<Document> stored = new CopyOnWriteArrayList<>();

    public SharedRagStore(VectorStore vectorStore, StorageService storage, ObjectProvider<RagCommands> assistantRag) {
        this.vectorStore = vectorStore;
        this.storage = storage;
        this.assistantRag = assistantRag;
    }

    public int add(String text, String source) {
        if (text == null || text.isBlank()) return 0;
        List<Document> raw = List.of(Document.builder()
                .text(text)
                .metadata("source", source == null ? "unknown" : source)
                .build());
        List<Document> chunks = TokenTextSplitter.builder().build().apply(raw);
        vectorStore.add(chunks);
        stored.addAll(chunks);
        persist();
        return chunks.size();
    }

    public int addLines(String text, String source) {
        if (text == null || text.isBlank()) return 0;
        List<Document> docs = Arrays.stream(text.split("\\R"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> Document.builder().text(s).metadata("source", source == null ? "unknown" : source).build())
                .toList();
        if (docs.isEmpty()) return 0;
        vectorStore.add(docs);
        stored.addAll(docs);
        persist();
        return docs.size();
    }

    public List<Document> query(String query, int topK) {
        return vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).build());
    }

    public int size() {
        return stored.size();
    }

    public List<Document> all() {
        return List.copyOf(stored);
    }

    public int clear() {
        int n = stored.size();
        if (n == 0) return 0;
        List<String> ids = new ArrayList<>(n);
        for (Document d : stored) ids.add(d.getId());
        vectorStore.delete(ids);
        stored.clear();
        persist();
        return n;
    }

    public void reloadFromStorage() {
        if (!stored.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Document d : stored) ids.add(d.getId());
            vectorStore.delete(ids);
            stored.clear();
        }
        storage.loadRagVectors(vectorStore);
        stored.addAll(storage.loadPlanDocs());
    }

    private void persist() {
        List<Document> assistantDocs = new ArrayList<>();
        RagCommands rc = assistantRag.getIfAvailable();
        if (rc != null) {
            assistantDocs.addAll(rc.storedDocs());
        }
        storage.persistRag(vectorStore, assistantDocs, new ArrayList<>(stored));
    }
}

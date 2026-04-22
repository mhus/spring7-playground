package de.mhus.spring7.aiplan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around VectorStore that tracks IDs so we can clear them.
 * Shared across planning, ingestion and execution.
 */
@Component
public class SharedRagStore {

    private final VectorStore vectorStore;
    private final List<Document> stored = new CopyOnWriteArrayList<>();

    public SharedRagStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
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
        return n;
    }
}

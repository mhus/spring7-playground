package de.mhus.spring7.airag;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

@Component
public class RagCommands {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final List<String> storedIds = new CopyOnWriteArrayList<>();

    public RagCommands(ChatClient.Builder builder, VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.chatClient = builder
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder().topK(4).build())
                        .build())
                .build();
    }

    @Command(name = "import", group = "RAG", description = "Import a PDF file into the vector store.")
    public String importPdf(@Argument(index = 0, description = "Path to the PDF file.") String path) {
        List<Document> docs;
        try {
            docs = new ParagraphPdfDocumentReader(new FileSystemResource(path)).get();
        } catch (Exception paragraphFailure) {
            docs = new PagePdfDocumentReader(new FileSystemResource(path)).get();
        }
        List<Document> chunks = TokenTextSplitter.builder().build().apply(docs);
        vectorStore.add(chunks);
        chunks.forEach(c -> storedIds.add(c.getId()));
        return "imported " + chunks.size() + " chunks from " + path;
    }

    @Command(name = "ask", group = "RAG", description = "Ask a question grounded in imported documents.")
    public String ask(@Argument(index = 0, description = "Your question.") String question) {
        return chatClient.prompt().user(question).call().content();
    }

    @Command(name = "docs", group = "RAG", description = "Show how many chunks are stored.")
    public String docs() {
        return "chunks stored: " + storedIds.size();
    }

    @Command(name = "forget", group = "RAG", description = "Clear the vector store.")
    public String forget() {
        if (!storedIds.isEmpty()) {
            vectorStore.delete(new ArrayList<>(storedIds));
            storedIds.clear();
        }
        return "vector store cleared";
    }
}

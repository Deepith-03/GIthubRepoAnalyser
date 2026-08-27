package github_chatbot.controller;

import github_chatbot.model.ChatRequest;
import github_chatbot.model.ChatResponse;
import github_chatbot.model.IngestRequest;
import github_chatbot.service.IngestionService;
import github_chatbot.service.RagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api")
public class RagController {

    private final IngestionService ingestionService;
    private final RagService ragService;

    public RagController(IngestionService ingestionService, RagService ragService) {
        this.ingestionService = ingestionService;
        this.ragService = ragService;
    }

    /**
     * Ingests a GitHub repository by url, chunking and embedding code files into PostgreSQL vector store.
     * POST /api/ingest
     * Body: { "url": "https://github.com/owner/repo" }
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingestRepo(@RequestBody IngestRequest request) {
        if (request.url() == null || request.url().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Repository URL is required"));
        }

        int chunkCount = ingestionService.ingestRepository(request.url());
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Repository successfully ingested and embedded into vector store.",
                "ingestedChunks", chunkCount,
                "repositoryUrl", request.url()
        ));
    }

    /**
     * Answers questions about the ingested repository using RAG pipeline (Similarity search + Gemini LLM).
     * POST /api/chat
     * Body: { "question": "What is the project structure?", "repoUrl": "https://github.com/octocat/Spoon-Knife", "topK": 4 }
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        int topK = (request.topK() != null && request.topK() > 0) ? request.topK() : 4;
        ChatResponse response = ragService.askQuestion(request.question(), request.repoUrl(), topK);
        return ResponseEntity.ok(response);
    }
}

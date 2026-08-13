package github_chatbot.service;

import github_chatbot.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorStore vectorStore;
    private final GitHubService gitHubService;
    private final RestClient geminiRestClient;
    private final String geminiApiKey;

    public RagService(VectorStore vectorStore,
                      GitHubService gitHubService,
                      RestClient.Builder restClientBuilder,
                      @Value("${spring.ai.openai.api-key:${spring.ai.google.genai.api-key:${GEMINI_API_KEY:}}}") String geminiApiKey) {
        this.vectorStore = vectorStore;
        this.gitHubService = gitHubService;
        this.geminiApiKey = geminiApiKey;
        this.geminiRestClient = restClientBuilder
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
    }

    /**
     * Executes RAG pipeline: vector similarity search -> prompt assembly -> Gemini REST API call.
     */
    public ChatResponse askQuestion(String question, String repoUrl, int topK) {
        log.info("Processing RAG query: '{}' (repoUrl: {}, topK: {})", question, repoUrl, topK);

        SearchRequest searchRequest = SearchRequest.query(question).withTopK(topK);

        // Optional metadata filtering by repository identifier
        if (repoUrl != null && !repoUrl.isBlank()) {
            try {
                String[] ownerAndRepo = gitHubService.extractOwnerAndRepo(repoUrl);
                String repoIdentifier = ownerAndRepo[0] + "/" + ownerAndRepo[1];
                searchRequest = searchRequest.withFilterExpression("repo == '" + repoIdentifier + "'");
                log.info("Applying vector search filter for repository: {}", repoIdentifier);
            } catch (Exception e) {
                log.warn("Invalid repoUrl provided for filtering: {}", repoUrl);
            }
        }

        // 1. Vector similarity search in PGVector store
        List<Document> similarDocs = vectorStore.similaritySearch(searchRequest);
        log.info("Retrieved {} relevant code chunks from vector store.", similarDocs.size());

        // 2. Build context string and extract source file paths
        StringBuilder contextBuilder = new StringBuilder();
        List<String> sources = similarDocs.stream()
                .map(doc -> {
                    String path = (String) doc.getMetadata().getOrDefault("path", "unknown");
                    contextBuilder.append("--- File: ").append(path).append(" ---\n");
                    contextBuilder.append(doc.getContent()).append("\n\n");
                    return path;
                })
                .distinct()
                .collect(Collectors.toList());

        // 3. Construct System & User Prompt
        String promptText = """
                You are an expert full-stack AI engineer and code analyst.
                Answer the user's question about the repository codebase using ONLY the relevant code context provided below.
                If the context does not contain enough information to answer the question accurately, explain what is missing.
                Provide clear code snippets and precise file references in your explanation where appropriate.

                --- CONTEXT FROM REPOSITORY ---
                %s
                -------------------------------

                USER QUESTION: %s

                ANSWER:
                """.formatted(contextBuilder.toString(), question);

        // 4. Call Google Gemini API directly via RestClient with instant error feedback
        String aiAnswer = generateGeminiCompletion(promptText);

        return new ChatResponse(aiAnswer, sources);
    }

    /**
     * Calls Gemini 1.5 Flash REST API directly via RestClient.
     */
    private String generateGeminiCompletion(String promptText) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not set! Please set the GEMINI_API_KEY environment variable or property.");
        }

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", promptText)
                        ))
                )
        );

        String[] modelNames = new String[]{
                "gemini-2.5-flash",
                "gemini-flash-latest",
                "gemini-3.6-flash",
                "gemini-pro-latest"
        };

        Exception lastException = null;
        for (String model : modelNames) {
            try {
                Map<?, ?> response = geminiRestClient.post()
                        .uri("/v1beta/models/" + model + ":generateContent?key={key}", geminiApiKey.trim())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(Map.class);

                if (response != null && response.containsKey("candidates")) {
                    List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
                    if (!candidates.isEmpty()) {
                        Map<String, Object> candidate = candidates.get(0);
                        Map<String, Object> content = (Map<String, Object>) candidate.get("content");
                        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                        if (parts != null && !parts.isEmpty()) {
                            log.info("Successfully generated RAG completion using model: {}", model);
                            return (String) parts.get(0).get("text");
                        }
                    }
                }
            } catch (Exception e) {
                lastException = e;
                log.warn("Gemini model {} failed: {}. Retrying next candidate model...", model, e.getMessage());
            }
        }

        log.error("All Gemini API models failed. Last exception: {}", lastException != null ? lastException.getMessage() : "Unknown");
        throw new RuntimeException("Gemini API Call Failed across all candidate models: " + (lastException != null ? lastException.getMessage() : "Unknown"), lastException);
    }
}

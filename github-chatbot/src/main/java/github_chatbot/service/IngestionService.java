package github_chatbot.service;

import github_chatbot.model.GitHubFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final GitHubService gitHubService;
    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;
    private final JdbcTemplate jdbcTemplate;

    public IngestionService(GitHubService gitHubService, VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.gitHubService = gitHubService;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        // TokenTextSplitter chunking into ~600 tokens per segment with 100 token overlap
        this.textSplitter = new TokenTextSplitter(600, 100, 5, 10000, true);
    }

    /**
     * Clears previous repository vectors and ingests ONLY the new GitHub repository,
     * maintaining a single-repository active vector database for maximum efficiency.
     *
     * @param githubUrl The full URL or owner/repo of the GitHub repository.
     * @return Total number of document chunks ingested into vector store.
     */
    public int ingestRepository(String githubUrl) {
        log.info("Starting single-repository ingestion pipeline for: {}", githubUrl);

        // 1a. Evict any cached files for this URL so we always fetch fresh data on re-ingest.
        //     This ensures ArchitectureController will also see the latest version of the repo.
        gitHubService.clearCache(githubUrl);

        // 1b. Purge previous vector store entries to maintain single active repo mode
        clearPreviousVectors();

        // 2. Fetch raw code files from GitHub Service
        List<GitHubFile> rawFiles = gitHubService.fetchRepositoryFiles(githubUrl);
        if (rawFiles.isEmpty()) {
            log.warn("No files retrieved from repository: {}", githubUrl);
            return 0;
        }

        // 3. Convert raw files into Spring AI Document objects with metadata
        List<Document> rawDocuments = new ArrayList<>();
        String[] ownerAndRepo = gitHubService.extractOwnerAndRepo(githubUrl);
        String repoIdentifier = ownerAndRepo[0] + "/" + ownerAndRepo[1];

        for (GitHubFile file : rawFiles) {
            Map<String, Object> metadata = Map.of(
                    "path", file.path(),
                    "repo", repoIdentifier,
                    "sourceUrl", githubUrl
            );
            rawDocuments.add(new Document(file.content(), metadata));
        }

        // 4. Chunk documents using TokenTextSplitter
        List<Document> chunkedDocuments = textSplitter.apply(rawDocuments);
        log.info("Split {} raw files into {} vector chunks for repo: {}", 
                rawFiles.size(), chunkedDocuments.size(), repoIdentifier);

        // 5. Embed and persist chunks into PostgreSQL pgvector
        vectorStore.accept(chunkedDocuments);
        log.info("Successfully ingested {} chunks into PGVector store for active repo: {}", 
                chunkedDocuments.size(), repoIdentifier);

        return chunkedDocuments.size();
    }

    /**
     * Truncates vector_store table to clear all previously stored codebase vectors.
     */
    private void clearPreviousVectors() {
        try {
            jdbcTemplate.execute("TRUNCATE TABLE vector_store");
            log.info("Cleared previous vector store records. Database is now dedicated to the new active repository.");
        } catch (Exception e) {
            log.warn("Notice during vector_store cleanup: {}", e.getMessage());
        }
    }
}

package github_chatbot.controller;

import github_chatbot.model.DependencyGraph;
import github_chatbot.model.GitHubFile;
import github_chatbot.service.DependencyParserService;
import github_chatbot.service.GitHubService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ArchitectureController
 *
 * <p>Exposes the Visual Architecture Dependency Graph API endpoint.
 * Clients supply a GitHub repository URL and receive a structured JSON graph
 * containing all file nodes and their import-based dependency edges.</p>
 *
 * <h2>Endpoint</h2>
 * <pre>GET /api/architecture?url={githubRepoUrl}</pre>
 *
 * <h2>Caching</h2>
 * <p>{@link GitHubService} maintains an in-memory cache keyed by repository URL.
 * If the repository was already ingested via {@code POST /api/ingest}, the files
 * are served directly from cache — no second GitHub API round-trip is made.
 * The cache is evicted at the start of each new ingestion call.</p>
 *
 * <h2>Example Response</h2>
 * <pre>
 * {
 *   "nodes": [
 *     { "id": "src/App.java", "label": "App.java", "type": "java" },
 *     { "id": "src/Service.java", "label": "Service.java", "type": "java" }
 *   ],
 *   "edges": [
 *     { "source": "src/App.java", "target": "src/Service.java" }
 *   ]
 * }
 * </pre>
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api")
public class ArchitectureController {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureController.class);

    private final GitHubService gitHubService;
    private final DependencyParserService dependencyParserService;

    public ArchitectureController(GitHubService gitHubService,
                                  DependencyParserService dependencyParserService) {
        this.gitHubService = gitHubService;
        this.dependencyParserService = dependencyParserService;
    }

    /**
     * Returns a dependency graph (nodes + edges) for the specified GitHub repository.
     *
     * <p>If the repository was previously ingested, files are served from the
     * {@link GitHubService} in-memory cache (zero additional GitHub API calls).
     * If not yet cached, files are fetched directly from the GitHub API and then
     * stored in the cache for subsequent requests.</p>
     *
     * @param url The full GitHub repository URL (e.g., {@code https://github.com/owner/repo})
     *            or the short {@code owner/repo} format.
     * @return {@code 200 OK} with a {@link DependencyGraph} JSON body, or
     *         {@code 400 Bad Request} if the URL is missing/blank, or
     *         {@code 500 Internal Server Error} if the fetch or parse fails unexpectedly.
     */
    @GetMapping("/architecture")
    public ResponseEntity<?> getArchitectureGraph(@RequestParam String url) {

        // ── Validate the incoming URL parameter ──────────────────────────────
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Repository URL is required. " +
                            "Usage: GET /api/architecture?url=https://github.com/owner/repo"));
        }

        log.info("Architecture graph requested for repository: {}", url);

        try {
            // ── Step 1: Fetch files (from cache if available, GitHub API otherwise) ──
            List<GitHubFile> files = gitHubService.fetchRepositoryFiles(url);

            if (files.isEmpty()) {
                log.warn("No source files found for repository: {}", url);
                return ResponseEntity.ok(Map.of(
                        "nodes", List.of(),
                        "edges", List.of(),
                        "message", "No analysable source files were found in the repository."
                ));
            }

            // ── Step 2: Parse dependencies and build the graph ────────────────
            DependencyGraph graph = dependencyParserService.buildGraph(files);

            log.info("Architecture graph for '{}' returned successfully: {} nodes, {} edges.",
                    url, graph.nodes().size(), graph.edges().size());

            return ResponseEntity.ok(graph);

        } catch (IllegalArgumentException e) {
            // Validation error from extractOwnerAndRepo in GitHubService
            log.warn("Invalid GitHub URL '{}': {}", url, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid repository URL: " + e.getMessage()));

        } catch (Exception e) {
            // Unexpected error during fetch or parse
            log.error("Failed to build architecture graph for '{}': {}", url, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to generate architecture graph: " + e.getMessage()));
        }
    }
}

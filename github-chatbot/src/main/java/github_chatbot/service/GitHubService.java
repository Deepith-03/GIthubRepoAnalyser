package github_chatbot.service;

import github_chatbot.model.GitHubFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);

    private final RestClient restClient;

    // Set of accepted file extensions for source code, configuration, and documentation
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "java", "js", "jsx", "ts", "tsx", "py", "html", "css", "scss",
            "json", "xml", "md", "yml", "yaml", "sql", "c", "cpp", "h", "hpp",
            "cs", "go", "rs", "kt", "kts", "scala", "sh", "properties", "gradle",
            "dockerfile", "env"
    );

    // Lockfiles, metadata, and binary extensions to explicitly filter out
    private static final Set<String> EXCLUDED_FILES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "cargo.lock",
            "composer.lock", "pom.xml.tag", ".gitignore", ".gitattributes"
    );

    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "bmp", "tiff",
            "pdf", "zip", "tar", "gz", "7z", "rar", "exe", "dll", "so", "dylib",
            "jar", "war", "ear", "class", "pyc", "pyo", "db", "sqlite", "mp3",
            "mp4", "wav", "avi", "mov", "ttf", "woff", "woff2", "eot"
    );

    private static final List<String> EXCLUDED_DIRECTORIES = List.of(
            ".git/", ".github/", ".idea/", ".vscode/", "node_modules/",
            "target/", "build/", "bin/", "out/", "dist/", ".mvn/"
    );

    public GitHubService(RestClient.Builder restClientBuilder,
                         @Value("${github.token:}") String githubToken) {
        RestClient.Builder builder = restClientBuilder
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "SpringBoot-GitHub-Chatbot");

        if (githubToken != null && !githubToken.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + githubToken.trim());
            log.info("GitHub REST client configured with Personal Access Token.");
        } else {
            log.warn("No GitHub Token provided. Requests will be unauthenticated (subject to 60 req/hr limit).");
        }

        this.restClient = builder.build();
    }

    /**
     * Extracts [owner, repo] from a GitHub URL or "owner/repo" string.
     * Examples:
     * - https://github.com/owner/repository.git -> ["owner", "repository"]
     * - https://github.com/owner/repository/ -> ["owner", "repository"]
     * - owner/repository -> ["owner", "repository"]
     */
    public String[] extractOwnerAndRepo(String githubUrl) {
        if (githubUrl == null || githubUrl.isBlank()) {
            throw new IllegalArgumentException("GitHub URL cannot be empty");
        }

        String cleanedUrl = githubUrl.trim();
        if (cleanedUrl.endsWith(".git")) {
            cleanedUrl = cleanedUrl.substring(0, cleanedUrl.length() - 4);
        }
        if (cleanedUrl.endsWith("/")) {
            cleanedUrl = cleanedUrl.substring(0, cleanedUrl.length() - 1);
        }

        Pattern pattern = Pattern.compile("(?:https?://github\\.com/)?([^/]+)/([^/]+)");
        Matcher matcher = pattern.matcher(cleanedUrl);

        if (matcher.matches()) {
            return new String[]{matcher.group(1), matcher.group(2)};
        }

        throw new IllegalArgumentException("Invalid GitHub URL format: " + githubUrl);
    }

    /**
     * Retrieves the default branch of a repository (e.g., "main" or "master").
     */
    public String getDefaultBranch(String owner, String repo) {
        try {
            Map<?, ?> response = restClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("default_branch")) {
                return (String) response.get("default_branch");
            }
        } catch (Exception e) {
            log.error("Failed to fetch repository metadata for {}/{}: {}", owner, repo, e.getMessage());
        }
        return "main";
    }

    /**
     * Recursively fetches source code files from a GitHub repository.
     */
    public List<GitHubFile> fetchRepositoryFiles(String githubUrl) {
        String[] ownerAndRepo = extractOwnerAndRepo(githubUrl);
        String owner = ownerAndRepo[0];
        String repo = ownerAndRepo[1];

        String defaultBranch = getDefaultBranch(owner, repo);
        log.info("Fetching repository tree for {}/{} (branch: {})...", owner, repo, defaultBranch);

        Map<?, ?> treeResponse = restClient.get()
                .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, defaultBranch)
                .retrieve()
                .body(Map.class);

        if (treeResponse == null || !treeResponse.containsKey("tree")) {
            log.warn("No files found in tree response for {}/{}", owner, repo);
            return Collections.emptyList();
        }

        List<Map<String, Object>> items = (List<Map<String, Object>>) treeResponse.get("tree");

        // Filter valid blobs first
        List<String> validPaths = items.stream()
                .filter(item -> "blob".equals(item.get("type")))
                .map(item -> (String) item.get("path"))
                .filter(this::shouldIncludeFile)
                .toList();

        log.info("Found {} valid source code files to fetch concurrently...", validPaths.size());

        // Fetch file contents concurrently in parallel for maximum speed
        List<GitHubFile> resultFiles = validPaths.parallelStream()
                .map(path -> {
                    try {
                        String content = fetchFileContent(owner, repo, path);
                        if (content != null && !content.isBlank()) {
                            return new GitHubFile(path, content);
                        }
                    } catch (Exception e) {
                        log.error("Failed fetching file content for {}: {}", path, e.getMessage());
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .toList();

        log.info("Successfully fetched {} source code files from {}/{}", resultFiles.size(), owner, repo);
        return resultFiles;
    }

    /**
     * Determines whether a file path should be included based on extension and path rules.
     */
    public boolean shouldIncludeFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }

        String lowerPath = path.toLowerCase();

        // 1. Exclude ignored directories
        for (String dir : EXCLUDED_DIRECTORIES) {
            if (lowerPath.startsWith(dir) || lowerPath.contains("/" + dir)) {
                return false;
            }
        }

        // Extract filename and extension
        String fileName = lowerPath.contains("/") ? lowerPath.substring(lowerPath.lastIndexOf('/') + 1) : lowerPath;

        // 2. Exclude specific blacklisted files (like lockfiles)
        if (EXCLUDED_FILES.contains(fileName)) {
            return false;
        }

        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < fileName.length() - 1) {
            extension = fileName.substring(dotIndex + 1);
        }

        // 3. Exclude binary / media extensions
        if (EXCLUDED_EXTENSIONS.contains(extension)) {
            return false;
        }

        // 4. Include if extension is explicitly allowed or file is a Dockerfile/Makefile
        return ALLOWED_EXTENSIONS.contains(extension)
                || fileName.equals("dockerfile")
                || fileName.equals("makefile");
    }

    /**
     * Fetches raw string content for a specific file from the repository.
     */
    public String fetchFileContent(String owner, String repo, String path) {
        return restClient.get()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.raw")
                .retrieve()
                .body(String.class);
    }
}

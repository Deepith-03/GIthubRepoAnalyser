package github_chatbot.controller;

import github_chatbot.model.GitHubFile;
import github_chatbot.service.GitHubService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/repo")
public class RepoController {

    private final GitHubService gitHubService;

    public RepoController(GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    /**
     * Endpoint to test fetching filtered files from a GitHub repository.
     * Example: GET /api/repo/test-fetch?url=https://github.com/octocat/Hello-World
     */
    @GetMapping("/test-fetch")
    public Map<String, Object> testFetchRepo(@RequestParam String url) {
        List<GitHubFile> files = gitHubService.fetchRepositoryFiles(url);
        return Map.of(
                "totalFiles", files.size(),
                "filePaths", files.stream().map(GitHubFile::path).toList()
        );
    }
}

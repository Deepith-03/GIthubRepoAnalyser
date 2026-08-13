package github_chatbot;

import github_chatbot.service.GitHubService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;

class GitHubServiceTest {

    private GitHubService gitHubService;

    @BeforeEach
    void setUp() {
        gitHubService = new GitHubService(RestClient.builder(), "");
    }

    @Test
    @DisplayName("Should correctly extract owner and repo from various GitHub URL formats")
    void testExtractOwnerAndRepo() {
        String[] res1 = gitHubService.extractOwnerAndRepo("https://github.com/spring-projects/spring-boot.git");
        assertEquals("spring-projects", res1[0]);
        assertEquals("spring-boot", res1[1]);

        String[] res2 = gitHubService.extractOwnerAndRepo("https://github.com/torvalds/linux/");
        assertEquals("torvalds", res2[0]);
        assertEquals("linux", res2[1]);

        String[] res3 = gitHubService.extractOwnerAndRepo("owner/repository");
        assertEquals("owner", res3[0]);
        assertEquals("repository", res3[1]);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for invalid URLs")
    void testInvalidUrls() {
        assertThrows(IllegalArgumentException.class, () -> gitHubService.extractOwnerAndRepo(null));
        assertThrows(IllegalArgumentException.class, () -> gitHubService.extractOwnerAndRepo("   "));
        assertThrows(IllegalArgumentException.class, () -> gitHubService.extractOwnerAndRepo("invalid-url-string"));
    }

    @Test
    @DisplayName("Should include valid source code files and filter binaries/lockfiles")
    void testShouldIncludeFile() {
        // Included files
        assertTrue(gitHubService.shouldIncludeFile("src/main/java/App.java"));
        assertTrue(gitHubService.shouldIncludeFile("frontend/src/App.tsx"));
        assertTrue(gitHubService.shouldIncludeFile("scripts/deploy.py"));
        assertTrue(gitHubService.shouldIncludeFile("Dockerfile"));

        // Excluded directories
        assertFalse(gitHubService.shouldIncludeFile(".git/config"));
        assertFalse(gitHubService.shouldIncludeFile("node_modules/express/index.js"));
        assertFalse(gitHubService.shouldIncludeFile("target/classes/App.class"));

        // Excluded lockfiles and binaries
        assertFalse(gitHubService.shouldIncludeFile("package-lock.json"));
        assertFalse(gitHubService.shouldIncludeFile("yarn.lock"));
        assertFalse(gitHubService.shouldIncludeFile("logo.png"));
        assertFalse(gitHubService.shouldIncludeFile("build/output.jar"));
    }
}

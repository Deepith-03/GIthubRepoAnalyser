package github_chatbot.model;

public record ChatRequest(
    String question,
    String repoUrl,
    Integer topK
) {}

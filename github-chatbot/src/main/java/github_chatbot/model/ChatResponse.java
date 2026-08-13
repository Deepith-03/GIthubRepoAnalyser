package github_chatbot.model;

import java.util.List;

public record ChatResponse(
    String answer,
    List<String> sources
) {}

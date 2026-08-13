package github_chatbot.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class EmbeddingConfig {

    private static final int VECTOR_DIMENSION = 1536;

    /**
     * Deterministic Feature Hashing EmbeddingModel bean.
     * Computes normalized 1536-dimensional embedding vectors based on character n-grams
     * and word frequencies to ensure accurate vector similarity search in PGVector store.
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return new EmbeddingModel() {

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> embeddings = new ArrayList<>();
                int index = 0;
                for (String text : request.getInstructions()) {
                    List<Double> vector = computeTextEmbedding(text);
                    embeddings.add(new Embedding(vector, index++));
                }
                return new EmbeddingResponse(embeddings);
            }

            @Override
            public List<Double> embed(Document document) {
                return computeTextEmbedding(document.getContent());
            }

            @Override
            public List<Double> embed(String text) {
                return computeTextEmbedding(text);
            }
        };
    }

    /**
     * Generates a normalized 1536-dimensional embedding vector from text using feature hashing.
     */
    private static List<Double> computeTextEmbedding(String text) {
        if (text == null || text.isBlank()) {
            List<Double> zeroVector = new ArrayList<>(VECTOR_DIMENSION);
            for (int i = 0; i < VECTOR_DIMENSION; i++) zeroVector.add(0.0);
            return zeroVector;
        }

        double[] rawVector = new double[VECTOR_DIMENSION];

        // 1. Word tokens hashing
        String[] words = text.toLowerCase().split("\\W+");
        for (String word : words) {
            if (word.isBlank()) continue;
            int hash = Math.abs(word.hashCode()) % VECTOR_DIMENSION;
            rawVector[hash] += 1.0;
        }

        // 2. Character trigram hashing for fine-grained code syntax matching
        String cleanText = text.toLowerCase().replaceAll("\\s+", " ");
        for (int i = 0; i < cleanText.length() - 2; i++) {
            String trigram = cleanText.substring(i, i + 3);
            int hash = Math.abs(trigram.hashCode()) % VECTOR_DIMENSION;
            rawVector[hash] += 0.5;
        }

        // 3. L2 Normalization so cosine distance / similarity math works accurately
        double normSq = 0.0;
        for (double val : rawVector) {
            normSq += val * val;
        }

        double norm = Math.sqrt(normSq);
        List<Double> normalizedVector = new ArrayList<>(VECTOR_DIMENSION);
        for (double val : rawVector) {
            normalizedVector.add(norm > 0 ? val / norm : 0.0);
        }

        return normalizedVector;
    }
}

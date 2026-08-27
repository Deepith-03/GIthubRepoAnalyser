package github_chatbot.model;

/**
 * Represents a directed dependency edge between two file nodes in the architecture graph.
 *
 * <p>An edge from {@code source} to {@code target} means: "the source file
 * imports or depends on the target file."</p>
 *
 * <p>The edge ID is derived on the frontend as {@code source -> target} to
 * satisfy React Flow's requirement for unique edge identifiers.</p>
 *
 * @param source The full file path of the file that declares the import
 *               (e.g., "src/main/Service.java"). Must match a {@link GraphNode#id()}.
 * @param target The full file path of the file being imported
 *               (e.g., "src/main/Repository.java"). Must match a {@link GraphNode#id()}.
 */
public record GraphEdge(
        String source,
        String target
) {}

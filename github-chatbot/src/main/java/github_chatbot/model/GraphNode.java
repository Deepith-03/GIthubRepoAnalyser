package github_chatbot.model;

/**
 * Represents a single file node in the architecture dependency graph.
 *
 * <p>Each node corresponds to one source file in the repository.
 * The frontend (React Flow / @xyflow/react) uses these fields to render
 * a colored, labelled box on the canvas.</p>
 *
 * @param id    The full repository-relative file path (e.g., "src/main/App.java").
 *              Used as the unique identifier for edges to reference via source/target.
 * @param label The short display name — just the filename (e.g., "App.java").
 * @param type  The detected language/file-type category.
 *              Possible values: "java", "js", "ts", "tsx", "jsx", "py",
 *              "html", "css", "yml", "json", "other".
 *              The frontend uses this to apply color-coding to nodes.
 */
public record GraphNode(
        String id,
        String label,
        String type
) {}

package github_chatbot.model;

import java.util.List;

/**
 * The root response object returned by the {@code GET /api/architecture} endpoint.
 *
 * <p>Contains the complete dependency graph for an analysed GitHub repository,
 * with all file nodes and the directed import edges that connect them.</p>
 *
 * <p>Example JSON shape:</p>
 * <pre>
 * {
 *   "nodes": [
 *     { "id": "src/App.java", "label": "App.java", "type": "java" }
 *   ],
 *   "edges": [
 *     { "source": "src/App.java", "target": "src/Service.java" }
 *   ]
 * }
 * </pre>
 *
 * @param nodes List of all file nodes discovered in the repository.
 * @param edges List of all directed dependency relationships between those nodes.
 */
public record DependencyGraph(
        List<GraphNode> nodes,
        List<GraphEdge> edges
) {}

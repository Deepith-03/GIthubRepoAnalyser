package github_chatbot.service;

import github_chatbot.model.DependencyGraph;
import github_chatbot.model.GraphEdge;
import github_chatbot.model.GraphNode;
import github_chatbot.model.GitHubFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DependencyParserService
 *
 * <p>Analyses a list of source code files fetched from GitHub and builds a
 * structured dependency graph by detecting inter-file import relationships
 * using language-specific regular expressions.</p>
 *
 * <h2>Supported Languages</h2>
 * <ul>
 *   <li><b>Java</b>  — {@code import com.example.Foo;}</li>
 *   <li><b>JavaScript / JSX</b> — {@code import X from './foo'}, {@code require('./bar')}</li>
 *   <li><b>TypeScript / TSX</b> — same ES6/CJS patterns as JS</li>
 *   <li><b>Python</b> — {@code import foo}, {@code from foo.bar import Baz}</li>
 * </ul>
 *
 * <h2>Resolution Strategy (Path-Suffix Matching)</h2>
 * <p>Instead of a simple key-based lookup map, this service converts each
 * extracted import token into a <em>path suffix</em> that the target file's
 * full path <em>must end with</em>. It then scans all known node IDs for a
 * match via {@link String#endsWith(String)}. This correctly handles:</p>
 * <ul>
 *   <li>Java FQN {@code com.example.service.GitHubService}
 *       → suffix {@code com/example/service/GitHubService.java}</li>
 *   <li>JS/TS relative path {@code ./components/Button}
 *       → suffix {@code components/Button} tried with .tsx/.ts/.jsx/.js extensions</li>
 *   <li>Python dotted module {@code utils.helpers}
 *       → suffix {@code utils/helpers.py}</li>
 * </ul>
 */
@Service
public class DependencyParserService {

    private static final Logger log = LoggerFactory.getLogger(DependencyParserService.class);

    // ─────────────────────────────────────────────────────────────────
    // Regex Patterns
    // ─────────────────────────────────────────────────────────────────

    /**
     * Java: matches {@code import fully.qualified.ClassName;} or
     * {@code import static fully.qualified.ClassName.method;}
     * <p>Capture group 1 → the <em>full</em> FQN (e.g., {@code com.example.service.UserService}).
     * We now capture the entire FQN (not just the simple name) so we can build an
     * accurate path suffix for endsWith matching.</p>
     */
    private static final Pattern JAVA_IMPORT =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w]+(\\.[\\w]+)+);");

    /**
     * JS / TS: matches ES6 static import syntax.
     * {@code import ... from './relative/path'} or {@code import './side-effect'}
     * Capture group 1 → the raw module specifier string (unquoted).
     */
    private static final Pattern JS_ES6_IMPORT =
            Pattern.compile("^\\s*import\\s+(?:[^'\"]*from\\s+)?['\"]([^'\"]+)['\"]");

    /**
     * JS / TS: matches CommonJS require calls.
     * {@code require('./some/module')}
     * Capture group 1 → the raw module specifier string (unquoted).
     */
    private static final Pattern JS_REQUIRE =
            Pattern.compile("require\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    /**
     * Python: matches top-level module imports.
     * {@code import foo.bar} or {@code from foo.bar import Baz}
     * Capture group 1 → used with "from X import"; group 2 → used with "import X".
     */
    private static final Pattern PYTHON_IMPORT =
            Pattern.compile("^\\s*(?:from\\s+([\\w.]+)\\s+import|import\\s+([\\w.]+))");

    /**
     * Common JS/TS source file extensions, tried in priority order when
     * resolving extensionless relative imports like {@code ./components/Button}.
     */
    private static final List<String> JS_EXTENSIONS =
            List.of(".tsx", ".ts", ".jsx", ".js");

    // ─────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Entry point. Builds a complete {@link DependencyGraph} from raw repository files.
     *
     * <p>Steps:
     * <ol>
     *   <li>Create one {@link GraphNode} per file and collect all node IDs into a list.</li>
     *   <li>For every file, scan each line with language-appropriate regex patterns to
     *       extract import tokens.</li>
     *   <li>Convert each import token into a <em>path suffix</em> and resolve it to
     *       a known node ID via {@link String#endsWith(String)} matching.</li>
     *   <li>Create a {@link GraphEdge} for every successfully resolved import.</li>
     *   <li>Deduplicate edges to avoid React Flow rendering duplicate connections.</li>
     * </ol>
     * </p>
     *
     * @param files Raw files fetched by {@link GitHubService}.
     * @return A {@link DependencyGraph} ready to be serialised as JSON.
     */
    public DependencyGraph buildGraph(List<GitHubFile> files) {
        log.info("Building dependency graph for {} files...", files.size());

        // ── Step 1: Create all nodes and collect their IDs ────────────
        List<GraphNode> nodes = new ArrayList<>();
        // Collect all node IDs (full paths) for O(n) endsWith scanning.
        // Using a plain List is intentional — we need substring matching, not O(1) hash lookup.
        List<String> allNodeIds = new ArrayList<>();

        for (GitHubFile file : files) {
            nodes.add(new GraphNode(
                    file.path(),                    // id    — full repo-relative path
                    extractFileName(file.path()),   // label — short display name
                    detectFileType(file.path())     // type  — language category for colour-coding
            ));
            allNodeIds.add(file.path());
        }

        // ── Step 2, 3 & 4: Scan files, resolve imports, build edges ──
        Set<String> seenEdgeKeys = new HashSet<>(); // deduplication guard
        List<GraphEdge> edges = new ArrayList<>();

        for (GitHubFile file : files) {
            String sourceId  = file.path();
            String extension = getExtension(file.path());

            // Extract a list of "path suffixes" — each one is a string that the
            // target node's full ID should end with.
            List<String> pathSuffixes = switch (extension) {
                case "java"      -> extractJavaPathSuffixes(file.content());
                case "js", "jsx",
                     "ts", "tsx" -> extractJsPathSuffixes(file.content());
                case "py"        -> extractPythonPathSuffixes(file.content());
                default          -> List.of(); // unsupported — skip
            };

            // For each suffix, find the matching node ID and create an edge
            for (String suffix : pathSuffixes) {
                String targetId = resolveByPathSuffix(suffix, allNodeIds);

                if (targetId != null && !targetId.equals(sourceId)) {
                    String edgeKey = sourceId + "→" + targetId;
                    if (seenEdgeKeys.add(edgeKey)) { // add() returns false if already present
                        edges.add(new GraphEdge(sourceId, targetId));
                        log.debug("Edge: [{}] ──→ [{}]", extractFileName(sourceId), extractFileName(targetId));
                    }
                }
            }
        }

        log.info("Dependency graph built: {} nodes, {} edges.", nodes.size(), edges.size());
        return new DependencyGraph(nodes, edges);
    }

    // ─────────────────────────────────────────────────────────────────
    // Language-specific import → path-suffix extractors
    // ─────────────────────────────────────────────────────────────────

    /**
     * Scans Java source code and converts each import statement into a file
     * path suffix that the target file's full path must end with.
     *
     * <p><b>Conversion example:</b><br>
     * {@code import com.example.service.GitHubService;}
     * → regex captures {@code "com.example.service.GitHubService"}
     * → replace {@code .} with {@code /} + append {@code .java}
     * → suffix {@code "com/example/service/GitHubService.java"}
     * → matches node ID {@code "src/main/java/com/example/service/GitHubService.java"}
     *   via {@link String#endsWith(String)}</p>
     *
     * <p>Inner-class or annotation imports (e.g., {@code com.example.Foo.Bar})
     * gracefully fall back because the suffix still ends with the file name stem.</p>
     *
     * @param content Raw Java source code.
     * @return List of path suffixes derived from import statements.
     */
    private List<String> extractJavaPathSuffixes(String content) {
        List<String> suffixes = new ArrayList<>();
        for (String line : content.lines().toList()) {
            Matcher m = JAVA_IMPORT.matcher(line);
            if (m.find()) {
                // Group 1 is the full FQN, e.g., "com.example.service.GitHubService"
                String fqn = m.group(1);

                // Replace dots with slashes and append ".java" to form a path suffix.
                // e.g., "com/example/service/GitHubService.java"
                String suffix = fqn.replace('.', '/') + ".java";
                suffixes.add(suffix);
            }
        }
        return suffixes;
    }

    /**
     * Scans JavaScript / TypeScript source code for both ES6 and CommonJS imports
     * and converts each relative specifier into a path suffix for matching.
     *
     * <p><b>Only relative imports</b> (starting with {@code .}) are processed,
     * because bare specifiers like {@code 'react'} refer to npm packages — not
     * in-repo files — and would produce false edges.</p>
     *
     * <p><b>Conversion example (extensionless):</b><br>
     * {@code import { Button } from './components/Button'}
     * → raw specifier: {@code "./components/Button"}
     * → strip {@code ./}: {@code "components/Button"}
     * → try suffixes: {@code "components/Button.tsx"}, {@code "components/Button.ts"}, …
     * → caller's {@link #resolveByPathSuffix} finds the first match in node IDs.</p>
     *
     * <p><b>Conversion example (with extension):</b><br>
     * {@code import './styles/global.css'}
     * → raw specifier: {@code "./styles/global.css"}
     * → strip {@code ./}: {@code "styles/global.css"}
     * → suffix already has extension — returned as-is.</p>
     *
     * <p><b>Path alias handling:</b><br>
     * Imports using path aliases like {@code @/components/Button} are treated as
     * extensionless relative imports — the {@code @/} prefix is stripped so the
     * segment {@code "components/Button"} is used for endsWith matching.</p>
     *
     * @param content Raw JS / TS source code.
     * @return List of path suffixes (may include multiple candidates per import for
     *         extensionless specifiers — {@link #resolveByPathSuffix} returns the first hit).
     */
    private List<String> extractJsPathSuffixes(String content) {
        List<String> suffixes = new ArrayList<>();

        for (String line : content.lines().toList()) {
            String raw = null;

            // Try ES6 import first
            Matcher es6 = JS_ES6_IMPORT.matcher(line);
            if (es6.find()) {
                raw = es6.group(1);
            } else {
                // Fall back to CommonJS require
                Matcher cjs = JS_REQUIRE.matcher(line);
                if (cjs.find()) {
                    raw = cjs.group(1);
                }
            }

            if (raw == null) continue;

            // ── Determine whether this is a relative or alias import ──
            boolean isRelative = raw.startsWith(".");
            boolean isAlias    = raw.startsWith("@/") || raw.startsWith("~/");

            if (!isRelative && !isAlias) {
                // Bare specifier (e.g., "react", "lodash") — external package, skip
                continue;
            }

            // ── Strip the leading relative prefix or alias ────────────
            String stripped;
            if (isRelative) {
                // Remove all leading "./" or "../" components
                stripped = raw.replaceFirst("^(\\.{1,2}/)+", "");
                // Now 'stripped' is a path like "components/Button" or "utils/helpers.ts"
            } else {
                // Strip "@/" or "~/" alias prefix
                stripped = raw.replaceFirst("^[@~]/", "");
            }

            // Normalise backslashes (unlikely in JS but defensive)
            stripped = stripped.replace('\\', '/');

            // ── Check whether the specifier already has an extension ──
            String lastSegment = stripped.contains("/")
                    ? stripped.substring(stripped.lastIndexOf('/') + 1)
                    : stripped;

            boolean hasExtension = lastSegment.contains(".")
                    && !lastSegment.startsWith(".");   // guard against hidden files

            if (hasExtension) {
                // Already has extension — use as a single suffix directly
                suffixes.add(stripped);
            } else {
                // Extensionless — emit one candidate suffix per known JS/TS extension.
                // resolveByPathSuffix will return the first hit, so priority order matters.
                for (String ext : JS_EXTENSIONS) {
                    suffixes.add(stripped + ext);
                }
                // Also try the index file convention, e.g., "components/Button/index.tsx"
                for (String ext : JS_EXTENSIONS) {
                    suffixes.add(stripped + "/index" + ext);
                }
            }
        }
        return suffixes;
    }

    /**
     * Scans Python source code for import statements and converts module paths
     * into file path suffixes.
     *
     * <p><b>Conversion example:</b><br>
     * {@code from utils.helpers import format_date}
     * → regex captures {@code "utils.helpers"}
     * → replace {@code .} with {@code /} + append {@code .py}
     * → suffix {@code "utils/helpers.py"}
     * → matches node ID {@code "src/utils/helpers.py"} via endsWith.</p>
     *
     * <p>Relative imports ({@code from . import sibling}) are skipped because
     * the module name is empty or a single dot.</p>
     *
     * @param content Raw Python source code.
     * @return List of path suffixes derived from import statements.
     */
    private List<String> extractPythonPathSuffixes(String content) {
        List<String> suffixes = new ArrayList<>();
        for (String line : content.lines().toList()) {
            Matcher m = PYTHON_IMPORT.matcher(line);
            if (m.find()) {
                // Group 1: "from X import" form; Group 2: bare "import X" form
                String module = m.group(1) != null ? m.group(1) : m.group(2);

                if (module == null || module.isBlank() || module.equals(".")) continue;

                // Strip leading dots from relative imports (e.g., "..utils" → "utils")
                String cleaned = module.replaceFirst("^\\.+", "");
                if (cleaned.isBlank()) continue;

                // Convert dotted module path to a slash-separated path with .py extension
                // e.g., "utils.helpers" → "utils/helpers.py"
                String suffix = cleaned.replace('.', '/') + ".py";
                suffixes.add(suffix);
            }
        }
        return suffixes;
    }

    // ─────────────────────────────────────────────────────────────────
    // Core resolver: endsWith scan over all node IDs
    // ─────────────────────────────────────────────────────────────────

    /**
     * Resolves a path suffix to a known graph node ID by scanning all node IDs
     * for one whose value ends with the given suffix.
     *
     * <p>This is the key change from the previous lookup-map approach.
     * Instead of requiring an exact key match, we check whether any node's
     * full file path <em>ends with</em> the derived suffix. This correctly
     * handles the mismatch between import paths and actual repository paths:</p>
     *
     * <pre>
     * Suffix:  "com/example/service/GitHubService.java"
     * Node ID: "src/main/java/com/example/service/GitHubService.java"  ← endsWith ✓
     *
     * Suffix:  "components/Button.tsx"
     * Node ID: "frontend/src/components/Button.tsx"                    ← endsWith ✓
     *
     * Suffix:  "utils/helpers.py"
     * Node ID: "myapp/utils/helpers.py"                                ← endsWith ✓
     * </pre>
     *
     * <p>The comparison is case-insensitive to tolerate OS-level case differences
     * and inconsistencies in how paths are reported by the GitHub API.</p>
     *
     * <p>If multiple node IDs match the same suffix (e.g., two files both named
     * {@code Utils.java} in different packages), the first match encountered is
     * returned. The more specific the FQN, the less likely a false collision.</p>
     *
     * @param pathSuffix  The normalised path suffix to search for
     *                    (e.g., {@code "com/example/Foo.java"}).
     * @param allNodeIds  The complete list of all node IDs (full file paths) in the graph.
     * @return The matching node ID (full path), or {@code null} if no match is found
     *         (indicating a third-party library or unresolvable import).
     */
    private String resolveByPathSuffix(String pathSuffix, List<String> allNodeIds) {
        if (pathSuffix == null || pathSuffix.isBlank()) return null;

        // Normalise the suffix: lowercase + forward slashes for consistent comparison
        String normSuffix = pathSuffix.toLowerCase().replace('\\', '/');

        for (String nodeId : allNodeIds) {
            // Normalise the node ID the same way
            String normId = nodeId.toLowerCase().replace('\\', '/');
            if (normId.endsWith(normSuffix)) {
                return nodeId; // return the original (unmodified) node ID
            }
        }
        return null; // no match → external/third-party import, discard
    }

    // ─────────────────────────────────────────────────────────────────
    // Utility helpers
    // ─────────────────────────────────────────────────────────────────

    /**
     * Strips the directory prefix and returns just the filename.
     * Example: {@code "src/main/java/App.java"} → {@code "App.java"}
     */
    private String extractFileName(String path) {
        if (path == null) return "";
        int slash = path.replace('\\', '/').lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * Returns the file extension in lowercase, without the leading dot.
     * Example: {@code "src/App.java"} → {@code "java"}
     */
    private String getExtension(String path) {
        String name = extractFileName(path);
        int dot = name.lastIndexOf('.');
        return (dot >= 0 && dot < name.length() - 1) ? name.substring(dot + 1).toLowerCase() : "";
    }

    /**
     * Detects the language/type category from the file extension.
     * The returned string is used as the {@link GraphNode#type()} field and drives
     * colour-coding on the frontend.
     *
     * @param path Full file path.
     * @return A lowercase language identifier string.
     */
    private String detectFileType(String path) {
        return switch (getExtension(path)) {
            case "java"           -> "java";
            case "js"             -> "js";
            case "jsx"            -> "jsx";
            case "ts"             -> "ts";
            case "tsx"            -> "tsx";
            case "py"             -> "py";
            case "html"           -> "html";
            case "css", "scss"    -> "css";
            case "yml", "yaml"    -> "yml";
            case "json"           -> "json";
            case "go"             -> "go";
            case "rs"             -> "rs";
            case "kt", "kts"      -> "kt";
            default               -> "other";
        };
    }
}

package io.archsim.model.issue;

import io.archsim.model.codec.BindingError;
import io.archsim.model.text.PythonText;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Turns the codec's errors about a document into {@code document.schema} issues, naming the node
 * or link an error is in when the location leads through one:
 * {@code link 'lb-api': Input should be 'read' or 'write' (at architecture.edges.1.config.requestTypes.1)}.
 */
public final class SchemaIssues {

    private SchemaIssues() {}

    /**
     * One issue per error, in order.
     *
     * @param errors what the codec found
     * @param raw the JSON that was read, to find node and edge ids by their index
     * @param prefix put before each error's location in the message and the path
     */
    public static List<Issue> of(List<BindingError> errors, @Nullable JsonNode raw, String prefix) {
        return errors.stream().map(error -> issue(error, raw, prefix)).toList();
    }

    private static Issue issue(BindingError error, @Nullable JsonNode raw, String prefix) {
        List<Object> loc = error.location().parts();
        String nodeId = null;
        String edgeId = null;
        boolean named = false; // Python names the node or link only when its id is truthy
        if (raw != null && raw.isObject() && loc.size() >= 3 && "architecture".equals(loc.get(0))) {
            JsonNode items = raw.path("architecture").path(String.valueOf(loc.get(1)));
            if (loc.get(2) instanceof Integer index && items.isArray() && index < items.size()) {
                JsonNode id = items.get(index).isObject() ? items.get(index).get("id") : null;
                String ident = id == null || id.isNull() ? null : PythonText.str(id);
                named = id != null && PythonText.truthy(id);
                if ("nodes".equals(loc.get(1))) {
                    nodeId = ident;
                } else if ("edges".equals(loc.get(1))) {
                    edgeId = ident;
                }
            }
        }
        String path = error.location().toString();
        String where = !named ? path : nodeId != null ? "node '%s'".formatted(nodeId) : "link '%s'".formatted(edgeId);
        return Issue.error("document.schema", "%s: %s (at %s%s)".formatted(where, error.message(), prefix, path))
                .atNode(nodeId)
                .atEdge(edgeId)
                .atPath(prefix + path);
    }
}

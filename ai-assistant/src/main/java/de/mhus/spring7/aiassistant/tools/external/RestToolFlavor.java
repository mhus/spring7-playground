package de.mhus.spring7.aiassistant.tools.external;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST {@link ToolFlavor}. Config JSON:
 * <pre>
 * {
 *   "type": "rest",
 *   "name": "currentWeather",
 *   "description": "...",
 *   "method": "GET",                          // default GET
 *   "urlTemplate": "https://host/path/{city}",
 *   "headers": { "Authorization": "Bearer ..." },  // optional
 *   "body":   "...json template with {arg}..." ,   // optional (for POST/PUT etc.)
 *   "params": {                                    // optional, describes args for the LLM
 *     "city": { "type": "string", "description": "City name" }
 *   }
 * }
 * </pre>
 * Placeholders {@code {name}} in urlTemplate and body are URL-encoded (url) / raw-substituted
 * (body) from the JSON args passed to invoke().
 */
@Component
public class RestToolFlavor implements ToolFlavor {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override public String type() { return "rest"; }

    @Override
    @SuppressWarnings("unchecked")
    public ToolInstance create(Map<String, Object> cfg) {
        String name = requireString(cfg, "name");
        String description = asString(cfg.get("description"));
        String method = asString(cfg.getOrDefault("method", "GET")).toUpperCase();
        String urlTemplate = requireString(cfg, "urlTemplate");
        Map<String, Object> headers = (Map<String, Object>) cfg.getOrDefault("headers", Map.of());
        String bodyTemplate = asString(cfg.get("body"));
        Map<String, Object> paramsSchema = (Map<String, Object>) cfg.getOrDefault("params", Map.of());

        ToolDescriptor descriptor = new ToolDescriptor(name, description, paramsSchema);

        return new ToolInstance() {
            @Override public List<ToolDescriptor> descriptors() { return List.of(descriptor); }

            @Override public String invoke(String toolName, String jsonArgs) {
                try {
                    Map<String, Object> args = jsonArgs == null || jsonArgs.isBlank()
                            ? Map.of()
                            : json.readValue(jsonArgs, new TypeReference<Map<String, Object>>() {});
                    String url = renderUrl(urlTemplate, args);
                    HttpRequest.Builder b = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(30));
                    headers.forEach((k, v) -> b.header(k, String.valueOf(v)));
                    HttpRequest.BodyPublisher body;
                    if (bodyTemplate != null && !bodyTemplate.isBlank()) {
                        String rendered = renderRaw(bodyTemplate, args);
                        body = HttpRequest.BodyPublishers.ofString(rendered, StandardCharsets.UTF_8);
                        if (!containsHeader(headers, "content-type")) b.header("Content-Type", "application/json");
                    } else {
                        body = HttpRequest.BodyPublishers.noBody();
                    }
                    b.method(method, body);
                    HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() >= 400) {
                        return "ERROR: HTTP " + resp.statusCode() + "\n" + truncate(resp.body());
                    }
                    return truncate(resp.body());
                } catch (Exception e) {
                    return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }
        };
    }

    private static String renderUrl(String template, Map<String, Object> args) {
        String out = template;
        for (var e : args.entrySet()) {
            String placeholder = "{" + e.getKey() + "}";
            if (out.contains(placeholder)) {
                String encoded = URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8);
                out = out.replace(placeholder, encoded);
            }
        }
        return out;
    }

    private static String renderRaw(String template, Map<String, Object> args) {
        String out = template;
        for (var e : args.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return out;
    }

    private static boolean containsHeader(Map<String, Object> headers, String name) {
        for (String k : headers.keySet()) if (k.equalsIgnoreCase(name)) return true;
        return false;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 8000 ? s : s.substring(0, 8000) + "\n…[truncated]";
    }

    private static String requireString(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null) throw new IllegalArgumentException("missing '" + key + "'");
        return v.toString();
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
}

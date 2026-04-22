package de.mhus.spring7.aiassistant.tools.external;

import java.util.Map;

/**
 * Plugin interface for a tool transport (REST, MCP, …). Register a Spring {@code @Component}
 * implementing this interface — {@link ToolService} collects all of them and dispatches
 * by the {@link #type()} value found in each tool-config JSON.
 */
public interface ToolFlavor {

    /** Matches the {@code type} field of the JSON config. */
    String type();

    /** Build (but don't yet start) a tool instance from the raw parsed JSON config. */
    ToolInstance create(Map<String, Object> config);
}

package de.mhus.spring7.aiassistant.tools.external;

import java.util.List;

/**
 * A running (possibly stateful) instance of an external tool — e.g. one REST endpoint, or
 * one connected MCP server that exposes several tools. Lifecycle is managed by {@link ToolService}.
 */
public interface ToolInstance {

    /** Tools this instance exposes. One REST config = one descriptor; one MCP server = many. */
    List<ToolDescriptor> descriptors();

    /** Invoke a specific tool by name (must be one of this instance's descriptor names). */
    String invoke(String toolName, String jsonArgs);

    /** Called once before first use. Build connection, acquire auth, probe. */
    default void start() {}

    /** Called on reload or shutdown. Close connections, release resources. */
    default void stop() {}
}

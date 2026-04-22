package de.mhus.spring7.aiassistant.tools.external;

import java.util.Map;

/**
 * What the agent sees about a tool: unique name, human/LLM-readable description,
 * and a JSON-schema-ish parameter specification.
 */
public record ToolDescriptor(String name, String description, Map<String, Object> params) {}

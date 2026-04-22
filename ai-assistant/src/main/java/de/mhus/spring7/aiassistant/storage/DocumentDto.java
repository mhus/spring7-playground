package de.mhus.spring7.aiassistant.storage;

import java.util.Map;

public record DocumentDto(String id, String text, Map<String, Object> metadata) {}

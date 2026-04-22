package de.mhus.spring7.aiassistant.storage;

/**
 * Metadata for an exec job (no output). Output lives in stdout.log / stderr.log inside the job dir.
 */
public record ExecJobDto(String id, String command, String startedAt, String finishedAt,
                         String status, Integer exitCode) {}

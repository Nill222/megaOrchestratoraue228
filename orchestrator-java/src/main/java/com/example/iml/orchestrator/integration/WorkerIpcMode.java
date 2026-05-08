package com.example.iml.orchestrator.integration;

enum WorkerIpcMode {
    STDIO,
    NAMED_PIPE;

    static WorkerIpcMode fromConfig(Object raw) {
        if (raw == null) return STDIO;
        String mode = String.valueOf(raw).trim().toLowerCase();
        return switch (mode) {
            case "stdio", "binary_stdio", "binary-stdio" -> STDIO;
            case "named_pipe", "named-pipe", "pipe" -> NAMED_PIPE;
            default -> STDIO;
        };
    }
}

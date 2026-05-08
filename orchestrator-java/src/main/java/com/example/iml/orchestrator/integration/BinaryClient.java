package com.example.iml.orchestrator.integration;

import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.io.IOException;
import java.util.Map;

interface BinaryClient extends AutoCloseable {
    BinaryProtocol.Message command(Map<String, Object> header) throws IOException;

    BinaryProtocol.Message command(Map<String, Object> header, byte[] payload) throws IOException;

    boolean isAlive();

    @Override
    void close();
}

package com.example.iml.orchestrator.integration;

import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class BinaryProcessClient implements BinaryClient {

    private final Process process;
    private final DataInputStream in;
    private final DataOutputStream out;

    public BinaryProcessClient(List<String> command, Path workingDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        this.process = pb.start();
        this.in = new DataInputStream(process.getInputStream());
        this.out = new DataOutputStream(process.getOutputStream());
    }

    public BinaryProtocol.Message command(Map<String, Object> header) throws IOException {
        return command(header, new byte[0]);
    }

    public BinaryProtocol.Message command(Map<String, Object> header, byte[] payload) throws IOException {
        BinaryProtocol.write(out, BinaryProtocol.MSG_COMMAND, header, payload);
        return BinaryProtocol.read(in);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            process.destroy();
        } catch (Exception ignored) {
        }
    }
}

package com.example.iml.orchestrator.integration;

import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class NamedPipeBinaryClient implements BinaryClient {
    private final Process process;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final FileInputStream respPipe;
    private final FileOutputStream cmdPipe;

    NamedPipeBinaryClient(List<String> command, Path workingDir, String pipeBasePath, int connectTimeoutMs) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        this.process = pb.start();
        String cmdPath = pipeBasePath + ".cmd";
        String respPath = pipeBasePath + ".resp";
        this.cmdPipe = connectOutputPipe(cmdPath, connectTimeoutMs);
        this.respPipe = connectInputPipe(respPath, connectTimeoutMs);
        this.in = new DataInputStream(respPipe);
        this.out = new DataOutputStream(cmdPipe);
    }

    private static FileInputStream connectInputPipe(String pipePath, int connectTimeoutMs) throws IOException {
        long deadlineNs = System.nanoTime() + Duration.ofMillis(Math.max(100, connectTimeoutMs)).toNanos();
        FileNotFoundException lastNotFound = null;
        while (System.nanoTime() < deadlineNs) {
            try {
                return new FileInputStream(pipePath);
            } catch (FileNotFoundException e) {
                lastNotFound = e;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting named pipe: " + pipePath, ie);
                }
            }
        }
        if (lastNotFound != null) throw new IOException("failed to connect input pipe: " + pipePath, lastNotFound);
        throw new IOException("failed to connect input pipe: " + pipePath);
    }

    private static FileOutputStream connectOutputPipe(String pipePath, int connectTimeoutMs) throws IOException {
        long deadlineNs = System.nanoTime() + Duration.ofMillis(Math.max(100, connectTimeoutMs)).toNanos();
        FileNotFoundException lastNotFound = null;
        while (System.nanoTime() < deadlineNs) {
            try {
                return new FileOutputStream(pipePath);
            } catch (FileNotFoundException e) {
                lastNotFound = e;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting named pipe: " + pipePath, ie);
                }
            }
        }
        if (lastNotFound != null) throw new IOException("failed to connect output pipe: " + pipePath, lastNotFound);
        throw new IOException("failed to connect output pipe: " + pipePath);
    }

    @Override
    public BinaryProtocol.Message command(Map<String, Object> header) throws IOException {
        return command(header, new byte[0]);
    }

    @Override
    public BinaryProtocol.Message command(Map<String, Object> header, byte[] payload) throws IOException {
        synchronized (this) {
            BinaryProtocol.write(out, BinaryProtocol.MSG_COMMAND, header, payload);
            return BinaryProtocol.read(in);
        }
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            respPipe.close();
        } catch (Exception ignored) {
        }
        try {
            cmdPipe.close();
        } catch (Exception ignored) {
        }
        try {
            process.destroy();
        } catch (Exception ignored) {
        }
    }
}

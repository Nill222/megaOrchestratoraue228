package com.example.iml.orchestrator.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class BinaryProtocol {

    public static final int MSG_COMMAND = 1;
    public static final int MSG_RESPONSE = 2;
    public static final int MSG_ERROR = 3;

    private static final byte[] MAGIC = new byte[]{'I', 'M', 'L', 'B'};
    private static final int VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BinaryProtocol() {
    }

    public static Message read(DataInputStream in) throws IOException {
        byte[] magic = in.readNBytes(4);
        if (magic.length < 4) {
            throw new IOException("EOF");
        }
        for (int i = 0; i < 4; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IOException("Bad magic");
            }
        }
        int version = in.readUnsignedByte();
        int type = in.readUnsignedByte();
        in.readUnsignedShort();
        int headerLen = in.readInt();
        int payloadLen = in.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported protocol version " + version);
        }

        byte[] headerBytes = in.readNBytes(headerLen);
        if (headerBytes.length != headerLen) {
            throw new IOException("Unexpected EOF header");
        }
        byte[] payload = in.readNBytes(payloadLen);
        if (payload.length != payloadLen) {
            throw new IOException("Unexpected EOF payload");
        }

        Map<String, Object> header = headerLen == 0
                ? Map.of()
                : MAPPER.readValue(headerBytes, new TypeReference<>() {});
        return new Message(type, header, payload);
    }

    public static void write(DataOutputStream out, int type, Map<String, Object> header, byte[] payload) throws IOException {
        byte[] headerBytes = MAPPER.writeValueAsString(header).getBytes(StandardCharsets.UTF_8);
        byte[] body = payload == null ? new byte[0] : payload;

        out.write(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(type);
        out.writeShort(0);
        out.writeInt(headerBytes.length);
        out.writeInt(body.length);
        out.write(headerBytes);
        if (body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    public record Message(int type, Map<String, Object> header, byte[] payload) {
    }
}

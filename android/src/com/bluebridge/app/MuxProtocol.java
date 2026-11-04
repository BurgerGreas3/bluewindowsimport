package com.bluebridge.app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MuxProtocol {
    public static final short MAGIC = (short) 0x4242;

    // SOCKS Tunnel commands (legacy compatibility)
    public static final byte CMD_CONNECT = 0x01;
    public static final byte CMD_CONNECTED = 0x02;
    public static final byte CMD_DATA = 0x03;
    public static final byte CMD_CLOSE = 0x04;
    public static final byte CMD_ERROR = 0x05;

    // Batch File & Text Transfer commands
    public static final byte CMD_BATCH_START = 0x10;
    public static final byte CMD_ITEM_START  = 0x11;
    public static final byte CMD_CHUNK_DATA  = 0x12;
    public static final byte CMD_ITEM_END    = 0x13;
    public static final byte CMD_BATCH_END   = 0x14;
    public static final byte CMD_ACK         = 0x20;
    public static final byte CMD_PING        = 0x21;
    public static final byte CMD_ANSWERS     = 0x30;
    public static final byte CMD_PROGRESS    = 0x31;
    public static final byte CMD_ANS_BEGIN   = 0x32;
    public static final byte CMD_ANS_CHUNK   = 0x33;
    public static final byte CMD_ANS_END     = 0x34;
    public static final byte CMD_FETCH       = 0x35;
    // Chatbot (separate feature, same transport; never touches batch flow)
    public static final byte CMD_CHAT        = 0x40;
    public static final byte CMD_CHAT_ANS    = 0x41;
    // Control (reset / pause)
    public static final byte CMD_CANCEL      = 0x42;
    public static final byte CMD_PAUSE       = 0x43;
    // Single-question re-solve ("0" key feature)
    public static final byte CMD_RESOLVE_ONE = 0x44;

    // Item Types
    public static final byte TYPE_TEXT  = 0x01;
    public static final byte TYPE_IMAGE = 0x02;
    public static final byte TYPE_AUDIO = 0x03;

    public static class Frame {
        public short streamId; // Item index (1..30) or stream ID
        public byte cmd;
        public byte[] payload;

        public Frame(short streamId, byte cmd, byte[] payload) {
            this.streamId = streamId;
            this.cmd = cmd;
            this.payload = payload != null ? payload : new byte[0];
        }
    }

    public static synchronized void writeFrame(OutputStream out, short streamId, byte cmd, byte[] payload) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeShort(MAGIC);
        dos.writeShort(streamId);
        dos.writeByte(cmd);
        int len = payload != null ? payload.length : 0;
        if (len > 60000) throw new IOException("Mux frame too large: " + len + " (use chunked transfer)");
        dos.writeShort((short) len);
        if (len > 0) {
            dos.write(payload);
        }
        dos.flush();
    }

    /** Chunked answers transfer: BEGIN + 800B CHUNKs + END. Survives >64KB payloads. */
    public static void sendChunkedAnswers(OutputStream out, byte[] data) throws IOException {
        writeFrame(out, (short) 0, CMD_ANS_BEGIN, new byte[0]);
        int off = 0;
        while (off < data.length) {
            int n = Math.min(800, data.length - off);
            byte[] chunk = new byte[n];
            System.arraycopy(data, off, chunk, 0, n);
            writeFrame(out, (short) 0, CMD_ANS_CHUNK, chunk);
            off += n;
        }
        writeFrame(out, (short) 0, CMD_ANS_END, new byte[0]);
    }

    public static Frame readFrame(DataInputStream dis) throws IOException {
        int b1 = dis.readUnsignedByte();
        int b2 = dis.readUnsignedByte();
        while (true) {
            if (b1 == 0x42 && b2 == 0x42) {
                break;
            }
            b1 = b2;
            b2 = dis.readUnsignedByte();
        }
        short streamId = dis.readShort();
        byte cmd = dis.readByte();
        int len = dis.readUnsignedShort();
        byte[] payload = new byte[len];
        dis.readFully(payload);
        return new Frame(streamId, cmd, payload);
    }
}

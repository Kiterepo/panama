package org.leo.server.panama.vpn.security.wrapper;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.leo.server.panama.vpn.security.chipher.KeyHelper;

/** Incremental decoder for the existing continuation/final-frame wire format. */
public class FrameWrapper extends Wrapper {
    public static final int MAX_PACKET_LENGTH = 8 * 1024 * 1024;
    private final int frameLength;
    private final int reservedHeaderLength;
    private final Wrapper frameHandler;
    private final ByteArrayOutputStream packet = new ByteArrayOutputStream();
    private int delimiter = -1;
    private int lengthBytes;
    private int decodedLength;
    private int remaining = -1;

    public FrameWrapper(int fixedFrameLength) { this(fixedFrameLength, null); }
    public FrameWrapper(int fixedFrameLength, Wrapper frameHandler) {
        if (fixedFrameLength < 4 || fixedFrameLength > MAX_PACKET_LENGTH)
            throw new IllegalArgumentException("Invalid fixed frame length");
        this.frameLength = fixedFrameLength - 1;
        this.frameHandler = frameHandler;
        reservedHeaderLength = fixedFrameLength < 0xFF ? 1 : fixedFrameLength < 0xFFFF - 2 ? 2
                : fixedFrameLength < 0xFFFFFF - 3 ? 3 : 4;
    }
    @Override public byte[] wrap(byte[] bytes) {
        byte[] payload = frameHandler == null ? bytes : frameHandler.wrap(bytes);
        if (payload.length > MAX_PACKET_LENGTH) throw new IllegalArgumentException("Frame packet too large");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (payload.length - offset > frameLength) {
            out.write(1); out.write(payload, offset, frameLength); offset += frameLength;
        }
        out.write(0);
        byte[] size = KeyHelper.getBytes(reservedHeaderLength, payload.length - offset);
        out.write(size, 0, size.length);
        out.write(payload, offset, payload.length - offset);
        return out.toByteArray();
    }
    @Override public byte[] unwrap(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] frame : unwrapFrames(bytes)) {
            byte[] data = frameHandler == null ? frame : frameHandler.unwrap(frame);
            out.write(data, 0, data.length);
        }
        return out.toByteArray();
    }
    public byte[] unwrapAll(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] data : unwrapFrames(bytes)) out.write(data, 0, data.length);
        return out.toByteArray();
    }
    public List<byte[]> unwrapFrames(byte[] bytes) {
        List<byte[]> result = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.length) {
            if (delimiter == -1) {
                delimiter = bytes[offset++] & 255;
                if (delimiter != 0 && delimiter != 1) throw new IllegalArgumentException("Invalid frame delimiter");
                remaining = delimiter == 1 ? frameLength : -1;
                lengthBytes = 0; decodedLength = 0;
            }
            if (remaining == -1) {
                while (lengthBytes < reservedHeaderLength && offset < bytes.length) {
                    decodedLength = (decodedLength << 8) | (bytes[offset++] & 255);
                    lengthBytes++;
                }
                if (lengthBytes != reservedHeaderLength) break;
                if (decodedLength < 0 || decodedLength > frameLength) throw new IllegalArgumentException("Invalid final frame length");
                remaining = decodedLength;
            }
            int count = Math.min(remaining, bytes.length - offset);
            if (count > MAX_PACKET_LENGTH - packet.size()) throw new IllegalArgumentException("Frame packet too large");
            packet.write(bytes, offset, count); offset += count; remaining -= count;
            if (remaining == 0) {
                if (delimiter == 0) { result.add(packet.toByteArray()); packet.reset(); }
                delimiter = -1;
            }
        }
        return result;
    }
}

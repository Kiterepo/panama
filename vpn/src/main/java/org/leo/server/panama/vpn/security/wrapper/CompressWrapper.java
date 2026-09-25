package org.leo.server.panama.vpn.security.wrapper;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class CompressWrapper extends Wrapper {
    @Override public byte[] wrap(byte[] bytes) {
        if (bytes.length > FrameWrapper.MAX_PACKET_LENGTH) throw new IllegalArgumentException("Input too large");
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(bytes); deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count == 0 && !deflater.finished()) throw new IllegalArgumentException("Compression made no progress");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } finally { deflater.end(); }
    }
    @Override public byte[] unwrap(byte[] bytes) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(bytes);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && !inflater.finished()) throw new IllegalArgumentException("Truncated or unsupported compressed packet");
                if (count > FrameWrapper.MAX_PACKET_LENGTH - out.size()) throw new IllegalArgumentException("Decompressed packet too large");
                out.write(buffer, 0, count);
            }
            if (inflater.getRemaining() != 0) throw new IllegalArgumentException("Trailing compressed data");
            return out.toByteArray();
        } catch (DataFormatException error) { throw new IllegalArgumentException("Invalid compressed packet", error); }
        finally { inflater.end(); }
    }
}

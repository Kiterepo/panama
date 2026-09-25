package org.leo.server.panama.vpn.reverse.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.leo.server.panama.util.NumberUtils;
import java.util.ArrayList;
import java.util.List;

/** Wire format: tag (4 bytes), payload length (4 bytes), payload. */
public final class ReverseProtocol {
    public static final int MAX_FRAME_LENGTH = 8 * 1024 * 1024;

    public static ByteBuf encodeProtocol(int tag, byte[] body) {
        checkLength(body.length);
        return Unpooled.wrappedBuffer(NumberUtils.intToByteArray(tag),
                NumberUtils.intToByteArray(body.length), body);
    }

    /** Escape a data payload matching the legacy close marker using two ordinary data frames. */
    public static ByteBuf encodeDataProtocol(int tag, byte[] body) {
        if (body.length == 4 && NumberUtils.byteArrayToInt(body) == org.leo.server.panama.vpn.reverse.constant.ReverseConstants.CLOSE_MAGIC) {
            return Unpooled.wrappedBuffer(encodeProtocol(tag, java.util.Arrays.copyOfRange(body, 0, 2)),
                    encodeProtocol(tag, java.util.Arrays.copyOfRange(body, 2, 4)));
        }
        return encodeProtocol(tag, body);
    }

    private static void checkLength(int length) {
        if (length < 0 || length > MAX_FRAME_LENGTH)
            throw new IllegalArgumentException("Invalid reverse frame length: " + length);
    }

    public static List<ReverseProtocolData> decodeProtocol(byte[] content) {
        return decodeProtocol(content, null);
    }

    /** Compatibility API: the final incomplete item must be supplied on the next call. */
    public static List<ReverseProtocolData> decodeProtocol(byte[] content, ReverseProtocolData previous) {
        Decoder decoder = new Decoder();
        if (previous != null && !previous.isComplete()) decoder.pending = previous;
        List<ReverseProtocolData> frames = decoder.feed(content);
        if (decoder.pending != null) frames.add(decoder.pending);
        return frames;
    }

    /** One decoder per transport. Neither TCP headers nor payloads need arrive in one read. */
    public static final class Decoder {
        private ReverseProtocolData pending;

        public List<ReverseProtocolData> feed(byte[] bytes) {
            List<ReverseProtocolData> result = new ArrayList<>();
            int offset = 0;
            while (offset < bytes.length) {
                if (pending == null) pending = new ReverseProtocolData();
                if (pending.headerSize < 8) {
                    int count = Math.min(8 - pending.headerSize, bytes.length - offset);
                    System.arraycopy(bytes, offset, pending.header, pending.headerSize, count);
                    pending.headerSize += count;
                    offset += count;
                    if (pending.headerSize < 8) break;
                    pending.tag = NumberUtils.byteArrayToInt(pending.header, 0);
                    int length = NumberUtils.byteArrayToInt(pending.header, 4);
                    checkLength(length);
                    pending.data = new byte[length];
                }
                int count = Math.min(pending.data.length - pending.size, bytes.length - offset);
                System.arraycopy(bytes, offset, pending.data, pending.size, count);
                pending.size += count;
                offset += count;
                if (pending.size == pending.data.length) {
                    pending.complete = true;
                    result.add(pending);
                    pending = null;
                }
            }
            return result;
        }

        public void reset() { pending = null; }
    }

    public static class ReverseProtocolData {
        private final byte[] header = new byte[8];
        private int headerSize;
        private int tag;
        private byte[] data;
        private int size;
        private boolean complete;
        public int getTag() { return tag; }
        public void setTag(int tag) { this.tag = tag; }
        public byte[] getData() { return data; }
        public void setData(byte[] data) { this.data = data; this.headerSize = 8; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public boolean isComplete() { return complete; }
        public void setComplete(boolean complete) { this.complete = complete; }
    }
}

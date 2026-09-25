package org.leo.server.panama.core.util;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** Counts writes before Netty queues them across event loops, including unflushed socket output. */
public final class BoundedWrites {
    public static final int MAX_PENDING_BYTES = 8 * 1024 * 1024;
    private static final AttributeKey<AtomicLong> PENDING = AttributeKey.valueOf(BoundedWrites.class, "pending");
    private BoundedWrites() {}
    public static ChannelFuture writeAndFlush(Channel channel, ByteBuf data) {
        AtomicLong pending = channel.attr(PENDING).get();
        if (pending == null) {
            AtomicLong fresh = new AtomicLong();
            AtomicLong previous = channel.attr(PENDING).setIfAbsent(fresh);
            pending = previous == null ? fresh : previous;
        }
        int size = data.readableBytes();
        final AtomicLong count = pending;
        if (count.addAndGet(size) > MAX_PENDING_BYTES) {
            count.addAndGet(-size);
            data.release();
            return channel.newFailedFuture(new IOException("Pending output exceeds 8 MiB"));
        }
        ChannelFuture future = channel.writeAndFlush(data);
        future.addListener(done -> count.addAndGet(-size));
        return future;
    }
}

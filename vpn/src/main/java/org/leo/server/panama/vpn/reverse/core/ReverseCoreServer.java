package org.leo.server.panama.vpn.reverse.core;

import io.netty.channel.*;
import io.netty.util.AttributeKey;
import org.leo.server.panama.core.connector.impl.TCPRequest;
import org.leo.server.panama.core.handler.RequestHandler;
import org.leo.server.panama.core.handler.tcp.TCPRequestHandler;
import org.leo.server.panama.server.tcp.TCPServer;
import org.leo.server.panama.util.NumberUtils;
import org.leo.server.panama.vpn.reverse.constant.ReverseConstants;
import org.leo.server.panama.vpn.reverse.protocol.ReverseProtocol;
import org.leo.server.panama.vpn.util.Callback;
import java.util.*;
import java.util.function.Consumer;

/** Each stream stays on its selected tunnel for its entire lifetime. */
public class ReverseCoreServer extends TCPServer implements RequestHandler<TCPRequest> {
    private static final AttributeKey<ReverseProtocol.Decoder> DECODER =
            AttributeKey.valueOf(ReverseCoreServer.class, "decoder");
    private static final int MAX_STREAMS = 20000;
    private final List<Channel> channels = new ArrayList<>();
    private final Map<Integer, Stream> streams = new HashMap<>();
    private int nextChannel;

    public ReverseCoreServer(int port) { super(port); }

    public void send2Client(int tag, byte[] data, Consumer<byte[]> callback, Callback closed) {
        // Retain the old close control frame so existing peers remain compatible.
        if (isClose(data) && callback == null) { closeStream(tag); return; }
        Stream stream;
        synchronized (this) {
            stream = streams.get(tag);
            if (stream == null && callback != null && streams.size() < MAX_STREAMS) {
                channels.removeIf(channel -> !channel.isActive());
                if (!channels.isEmpty()) {
                    Channel channel = channels.get(Math.floorMod(nextChannel++, channels.size()));
                    stream = new Stream(channel, callback, closed);
                    streams.put(tag, stream);
                }
            }
        }
        if (stream == null) { if (closed != null) closed.call(); return; }
        final Stream selected = stream;
        synchronized (this) {
            if (streams.get(tag) != selected) return;
            if (!selected.channel.isActive()) { failStream(tag, selected, false); return; }
            org.leo.server.panama.core.util.BoundedWrites.writeAndFlush(selected.channel,
                    ReverseProtocol.encodeDataProtocol(tag, data))
                    .addListener(future -> { if (!future.isSuccess()) failStream(tag, selected, true); });
        }
    }

    public void closeStream(int tag) {
        Stream stream;
        synchronized (this) { stream = streams.remove(tag); }
        if (stream != null) sendClose(stream.channel, tag);
    }

    private void failStream(int tag, Stream expected, boolean notifyPeer) {
        synchronized (this) {
            if (!streams.remove(tag, expected)) return;
        }
        if (notifyPeer) sendClose(expected.channel, tag);
        if (expected.closed != null) expected.closed.call();
    }

    private void sendClose(Channel channel, int tag) {
        if (channel.isActive()) org.leo.server.panama.core.util.BoundedWrites.writeAndFlush(channel, ReverseProtocol.encodeProtocol(tag,
                NumberUtils.intToByteArray(ReverseConstants.CLOSE_MAGIC)))
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }

    @Override protected void setupPipeline(ChannelPipeline pipeline) {
        pipeline.addLast(new TCPRequestHandler(this));
    }
    @Override public synchronized void onConnect(ChannelHandlerContext ctx) {
        ctx.channel().attr(DECODER).set(new ReverseProtocol.Decoder());
        channels.add(ctx.channel());
    }
    @Override public void doRequest(TCPRequest request) {
        Channel channel = request.getChannelHandlerContext().channel();
        for (ReverseProtocol.ReverseProtocolData frame : channel.attr(DECODER).get().feed(request.getData())) {
            Stream stream;
            synchronized (this) { stream = streams.get(frame.getTag()); }
            // A different inner server must never inject bytes into this stream.
            if (stream == null || stream.channel != channel) continue;
            if (isClose(frame.getData())) failStream(frame.getTag(), stream, false);
            else stream.consumer.accept(frame.getData());
        }
    }
    @Override public void onClose(ChannelHandlerContext ctx) {
        List<Stream> removed = new ArrayList<>();
        synchronized (this) {
            channels.remove(ctx.channel());
            Iterator<Stream> iterator = streams.values().iterator();
            while (iterator.hasNext()) {
                Stream stream = iterator.next();
                if (stream.channel == ctx.channel()) { removed.add(stream); iterator.remove(); }
            }
        }
        ctx.channel().attr(DECODER).set(null);
        for (Stream stream : removed) if (stream.closed != null) stream.closed.call();
    }
    public static boolean isClose(byte[] data) {
        return data.length == 4 && NumberUtils.byteArrayToInt(data) == ReverseConstants.CLOSE_MAGIC;
    }
    private static final class Stream {
        final Channel channel;
        final Consumer<byte[]> consumer;
        final Callback closed;
        Stream(Channel channel, Consumer<byte[]> consumer, Callback closed) {
            this.channel = channel; this.consumer = consumer; this.closed = closed;
        }
    }
}

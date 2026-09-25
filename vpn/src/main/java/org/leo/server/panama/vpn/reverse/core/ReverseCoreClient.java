package org.leo.server.panama.vpn.reverse.core;

import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import org.leo.server.panama.client.Client;
import org.leo.server.panama.client.ClientResponseDelegate;
import org.leo.server.panama.client.handler.TCPClientHandler;
import org.leo.server.panama.client.tcp.TCPClient;
import org.leo.server.panama.core.connector.impl.TCPResponse;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/** Async reconnects are serialized on one owned event loop; explicit shutdown never reconnects. */
public class ReverseCoreClient extends TCPClient implements ClientResponseDelegate<TCPResponse> {
    private final EventLoopGroup group;
    private final InetSocketAddress address;
    private final Consumer<byte[]> consumer;
    private final Runnable disconnected;
    private final DnsAddressResolverGroup resolver = new DnsAddressResolverGroup(
            NioDatagramChannel.class, DnsServerAddressStreamProviders.platformDefault());
    private ScheduledFuture<?> retry;
    private volatile boolean stopped;
    private boolean started;

    public ReverseCoreClient(InetSocketAddress address, Consumer<byte[]> consumer) {
        this(address, consumer, () -> {});
    }
    public ReverseCoreClient(InetSocketAddress address, Consumer<byte[]> consumer, Runnable disconnected) {
        this(new NioEventLoopGroup(1), address, consumer, disconnected);
    }
    private ReverseCoreClient(EventLoopGroup group, InetSocketAddress address, Consumer<byte[]> consumer, Runnable disconnected) {
        super(group, null);
        this.group = group; this.address = address; this.consumer = consumer; this.disconnected = disconnected;
    }
    public Channel channel() { return getConnectFuture() == null ? null : getConnectFuture().channel(); }
    @Override public synchronized Client connect(InetSocketAddress ignored) {
        if (stopped) return this;
        group.next().execute(() -> { if (!started && !stopped) { started = true; tryConnect(); } });
        return this;
    }
    private void tryConnect() {
        retry = null;
        if (stopped) return;
        ChannelFuture connection = connectAsync(address, resolver);
        connection.addListener(future -> { if (!future.isSuccess()) scheduleRetry(); });
    }
    private void scheduleRetry() {
        if (!stopped && retry == null) retry = group.next().schedule(this::tryConnect, 3, TimeUnit.SECONDS);
    }
    @Override protected void setupPipeline(ChannelPipeline pipeline) {
        pipeline.addLast(new TCPClientHandler(this, this));
    }
    @Override public boolean shouldDoPerResponse() { return true; }
    @Override public boolean shouldDoCompleteResponse() { return false; }
    @Override public void doPerResponse(Client client, TCPResponse response) { consumer.accept(response.getData()); }
    @Override public void onConnectClosed(Client client) {
        setClose(true);
        disconnected.run();
        scheduleRetry();
    }
    public synchronized io.netty.util.concurrent.Future<?> shutdown() {
        if (stopped) return group.terminationFuture();
        stopped = true;
        group.next().execute(() -> {
            if (retry != null) retry.cancel(false);
            super.close();
            resolver.close();
        });
        return group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
    }
    @Override public void close() { if (!stopped) shutdown(); }
}

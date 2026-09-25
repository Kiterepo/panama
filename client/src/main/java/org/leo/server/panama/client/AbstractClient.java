package org.leo.server.panama.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.InetSocketAddress;

public abstract class AbstractClient implements Client {
    private volatile boolean close = true;
    private ChannelFuture connectFuture;
    private EventLoopGroup workGroup;

    public AbstractClient(EventLoopGroup eventLoopGroup) {
        this.workGroup = eventLoopGroup;
    }

    @Override
    public Client connect(InetSocketAddress inetSocketAddress) {
        if (!close) {
            return this;
        }

        try {
            connectAsync(inetSocketAddress, null).sync();
            return this;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            return null;
        } catch (Exception e) {
            close();
            return null;
        }
    }

    /** Non-blocking connection path for event-loop callers. */
    public ChannelFuture connectAsync(InetSocketAddress address,
            io.netty.resolver.AddressResolverGroup<InetSocketAddress> resolver) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workGroup).channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        setupPipeline(ch.pipeline());
                    }
                });
        if (resolver != null) bootstrap.resolver(resolver);
        close = false;
        connectFuture = bootstrap.connect(address);
        connectFuture.addListener(future -> {
            if (!future.isSuccess()) close = true;
        });
        return connectFuture;
    }

    protected abstract void setupPipeline(ChannelPipeline pipeline);

    @Override
    public void send(byte []data, int timeout) {
        ChannelFuture connection = connectFuture;
        if (connection == null || close || data.length == 0) return;
        // Keep writes ordered behind connection establishment; never block an event loop.
        connection.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess() && future.channel().isActive()) {
                future.channel().writeAndFlush(Unpooled.wrappedBuffer(data))
                        .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
        });
    }

    @Override
    public boolean isClose() {
        return close;
    }

    public void setClose(boolean close) {
        this.close = close;
    }

    @Override
    public void close() {
        close = true;
        ChannelFuture connection = connectFuture;
        if (connection != null) {
            connection.cancel(false);
            connection.channel().close();
        }
    }

    public ChannelFuture getConnectFuture() {
        return connectFuture;
    }
}

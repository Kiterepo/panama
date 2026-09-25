package org.leo.server.panama.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public abstract class AbstractServer implements Server {
    private final int port;
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);
    private EventLoopGroup mainGroup;
    private EventLoopGroup workGroup;
    private ChannelFuture binding;
    private CompletableFuture<Void> termination;

    public AbstractServer(int port) { this.port = port; }

    /** Asynchronous startup, also usable by embedding applications and integration tests. */
    public synchronized ChannelFuture bind(int maxThread) {
        if (binding != null || termination != null) throw new IllegalStateException("Server already started or stopped");
        mainGroup = new NioEventLoopGroup(1);
        workGroup = new NioEventLoopGroup(Math.max(1, maxThread));
        try {
            binding = new ServerBootstrap().group(mainGroup, workGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<NioSocketChannel>() {
                        @Override protected void initChannel(NioSocketChannel channel) {
                            channels.add(channel);
                            setupPipeline(channel.pipeline());
                        }
                    }).bind(port);
            binding.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    channels.add(future.channel());
                    future.channel().closeFuture().addListener(ignored -> shutdown());
                } else shutdown();
            });
            return binding;
        } catch (RuntimeException error) { shutdown(); throw error; }
    }

    @Override public void start(int maxThread) {
        try { bind(maxThread).sync().channel().closeFuture().sync(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally { shutdown(); }
    }
    @Override public synchronized int port() {
        return binding != null && binding.channel().localAddress() instanceof InetSocketAddress
                ? ((InetSocketAddress) binding.channel().localAddress()).getPort() : port;
    }
    @Override public synchronized Future<?> shutdown() {
        if (termination != null) return termination;
        termination = new CompletableFuture<>();
        if (binding != null && !binding.isDone()) {
            binding.cancel(false);
            binding.channel().close();
        }
        CompletableFuture<Void> done = termination;
        channels.close().addListener(ignored -> {
            CompletableFuture.allOf(stop(mainGroup), stop(workGroup)).whenComplete((value, error) -> {
                if (error == null) done.complete(null); else done.completeExceptionally(error);
            });
        });
        return termination;
    }
    private CompletableFuture<Void> stop(EventLoopGroup group) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (group == null) done.complete(null);
        else group.shutdownGracefully(0, 2, TimeUnit.SECONDS).addListener(future -> {
            if (future.isSuccess()) done.complete(null); else done.completeExceptionally(future.cause());
        });
        return done;
    }
    protected abstract void setupPipeline(ChannelPipeline pipeline);
}

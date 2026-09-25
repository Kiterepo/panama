package org.leo.server.panama.vpn;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import org.junit.Test;
import org.leo.server.panama.client.ClientResponseDelegate;
import org.leo.server.panama.client.tcp.TCPClient;
import org.leo.server.panama.core.connector.impl.TCPResponse;
import org.leo.server.panama.core.handler.tcp.TCPRequestHandler;
import org.leo.server.panama.vpn.configuration.ShadowSocksConfiguration;
import org.leo.server.panama.vpn.handler.ShadowSocksRequestHandler;
import org.leo.server.panama.vpn.security.wrapper.Wrapper;
import org.leo.server.panama.vpn.security.wrapper.WrapperFactory;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class RelayIntegrationTest {
    @Test(timeout = 20000) public void encryptedRelaySupportsGreetingLargeTransferAndClientCleanup() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        ExecutorService targetWorker = Executors.newSingleThreadExecutor();
        Channel server = null;
        byte[] payload = new byte[1024 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte)(i * 31);
        try (ServerSocket target = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            target.setSoTimeout(10000);
            Future<?> targetDone = targetWorker.submit(() -> {
                try (Socket socket = target.accept()) {
                    socket.setSoTimeout(10000);
                    socket.getOutputStream().write(new byte[]{42});
                    byte[] received = new byte[payload.length];
                    new DataInputStream(socket.getInputStream()).readFully(received);
                    assertArrayEquals(payload, received);
                    socket.getOutputStream().write(received);
                    socket.getOutputStream().flush();
                    assertEquals("Closing the client must close its target connection", -1, socket.getInputStream().read());
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            ShadowSocksConfiguration config = new ShadowSocksConfiguration();
            config.setPassword("test");
            server = startProxy(group, config);
            try (Socket client = new Socket()) {
                client.connect(server.localAddress(), 5000); client.setSoTimeout(10000);
                Wrapper codec = WrapperFactory.getInstance("aes-256-cfb", "test", "encrypt");
                int port = target.getLocalPort();
                byte[] header = {3, 9, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't', (byte)(port >>> 8), (byte)port};
                byte[] encryptedHeader = codec.wrap(header);
                for (byte b : encryptedHeader) { client.getOutputStream().write(b); client.getOutputStream().flush(); }
                assertArrayEquals(new byte[]{42}, readPlain(client, codec, 1));
                client.getOutputStream().write(codec.wrap(payload));
                client.getOutputStream().flush();
                assertArrayEquals(payload, readPlain(client, codec, payload.length));
            }
            targetDone.get(5, TimeUnit.SECONDS);
        } finally {
            if (server != null) server.close().sync();
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            targetWorker.shutdownNow();
        }
    }

    @Test(timeout = 10000) public void refusedTargetClosesClientInsteadOfHanging() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        Channel server = null;
        int unusedPort;
        try (ServerSocket unused = new ServerSocket(0)) { unusedPort = unused.getLocalPort(); }
        try {
            ShadowSocksConfiguration config = new ShadowSocksConfiguration(); config.setEncrypt("raw");
            server = startProxy(group, config);
            try (Socket client = new Socket()) {
                client.connect(server.localAddress(), 5000); client.setSoTimeout(5000);
                client.getOutputStream().write(new byte[]{1, 127, 0, 0, 1, (byte)(unusedPort >>> 8), (byte)unusedPort});
                assertEquals(-1, client.getInputStream().read());
            }
        } finally {
            if (server != null) server.close().sync();
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
        }
    }

    @Test(timeout = 10000) public void pendingDnsDoesNotBlockEventLoopAndQueuedWritesStayOrdered() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        AtomicReference<Promise<InetSocketAddress>> resolution = new AtomicReference<>();
        CountDownLatch resolving = new CountDownLatch(1);
        AddressResolverGroup<InetSocketAddress> resolver = new AddressResolverGroup<InetSocketAddress>() {
            @Override protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
                return new AbstractAddressResolver<InetSocketAddress>(executor, InetSocketAddress.class) {
                    @Override protected boolean doIsResolved(InetSocketAddress address) { return !address.isUnresolved(); }
                    @Override protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                        resolution.set(promise); resolving.countDown();
                    }
                    @Override protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
                        promise.setFailure(new UnsupportedOperationException());
                    }
                };
            }
        };
        TCPClient client = new TCPClient(group, new ClientResponseDelegate<TCPResponse>() {});
        try (ServerSocket target = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            target.setSoTimeout(5000);
            group.next().submit(() -> {
                client.connectAsync(InetSocketAddress.createUnresolved("delayed.test", 80), resolver);
                client.send(new byte[]{1, 2}, 0);
                client.send(new byte[]{3, 4}, 0);
            }).get(2, TimeUnit.SECONDS);
            assertTrue(resolving.await(2, TimeUnit.SECONDS));
            // This must run even while the DNS answer has not arrived.
            assertEquals(Integer.valueOf(123), group.next().submit(() -> 123).get(2, TimeUnit.SECONDS));
            resolution.get().setSuccess(new InetSocketAddress("127.0.0.1", target.getLocalPort()));
            try (Socket accepted = target.accept()) {
                accepted.setSoTimeout(3000);
                byte[] received = new byte[4];
                new DataInputStream(accepted.getInputStream()).readFully(received);
                assertArrayEquals(new byte[]{1, 2, 3, 4}, received);
                group.next().submit(client::close).get(2, TimeUnit.SECONDS);
                assertEquals(-1, accepted.getInputStream().read());
            }
        } finally {
            client.close(); resolver.close();
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
        }
    }

    @Test(timeout = 10000) public void backpressurePausesAndResumesBothDirections() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        AtomicReference<Channel> inbound = new AtomicReference<>();
        AtomicReference<TCPClient> outbound = new AtomicReference<>();
        ShadowSocksConfiguration config = new ShadowSocksConfiguration(); config.setEncrypt("raw");
        ShadowSocksRequestHandler handler = new ShadowSocksRequestHandler(config) {
            @Override protected org.leo.server.panama.vpn.proxy.TCPProxy createProxy(Channel c, ShadowSocksConfiguration configuration) {
                inbound.set(c);
                return new org.leo.server.panama.vpn.proxy.impl.ShadowSocksProxy(c, () -> close(c), configuration, null,
                        new org.leo.server.panama.vpn.shadowsocks.ShadowsocksRequestResolver()) {
                    @Override protected org.leo.server.panama.client.Client createClient(EventLoopGroup loop) {
                        TCPClient client = (TCPClient) super.createClient(loop);
                        outbound.set(client);
                        return client;
                    }
                };
            }
        };
        Channel server = null;
        try (ServerSocket target = new ServerSocket(0); Socket client = new Socket()) {
            target.setSoTimeout(3000);
            server = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel channel) {
                            channel.pipeline().addLast(new TCPRequestHandler(handler));
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            client.connect(server.localAddress(), 3000); client.setSoTimeout(3000);
            int port = target.getLocalPort();
            client.getOutputStream().write(new byte[]{1, 127, 0, 0, 1, (byte)(port >>> 8), (byte)port});
            try (Socket accepted = target.accept()) {
                accepted.setSoTimeout(3000);
                outbound.get().getConnectFuture().sync();
                Channel destination = outbound.get().getConnectFuture().channel();
                assertFlowControl(destination, inbound.get(), false);
                assertFlowControl(destination, inbound.get(), true);
                assertFlowControl(inbound.get(), destination, false);
                assertFlowControl(inbound.get(), destination, true);
                client.getOutputStream().write(7);
                assertEquals(7, accepted.getInputStream().read());
                accepted.getOutputStream().write(9);
                assertEquals(9, client.getInputStream().read());
            }
        } finally {
            if (server != null) server.close().sync();
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
        }
    }

    private void assertFlowControl(Channel destination, Channel source, boolean writable) throws Exception {
        destination.eventLoop().submit(() ->
                destination.unsafe().outboundBuffer().setUserDefinedWritability(1, writable)).get(3, TimeUnit.SECONDS);
        // Netty enqueues the writability event; verify after that event has been delivered.
        destination.eventLoop().submit(() -> assertEquals(writable, source.config().isAutoRead())).get(3, TimeUnit.SECONDS);
    }

    private Channel startProxy(EventLoopGroup group, ShadowSocksConfiguration config) throws InterruptedException {
        ShadowSocksRequestHandler handler = new ShadowSocksRequestHandler(config);
        return new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override protected void initChannel(Channel channel) {
                        channel.pipeline().addLast(new TCPRequestHandler(handler));
                    }
                }).bind("127.0.0.1", 0).sync().channel();
    }
    private byte[] readPlain(Socket socket, Wrapper codec, int length) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (result.size() < length) {
            int n = socket.getInputStream().read(buffer);
            if (n < 0) throw new EOFException();
            byte[] plain = codec.unwrap(Arrays.copyOf(buffer, n));
            result.write(plain);
        }
        assertEquals(length, result.size());
        return result.toByteArray();
    }
}

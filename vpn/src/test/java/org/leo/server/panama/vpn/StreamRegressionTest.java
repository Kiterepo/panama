package org.leo.server.panama.vpn;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.leo.server.panama.client.Client;
import org.leo.server.panama.client.ClientResponseDelegate;
import org.leo.server.panama.client.handler.TCPClientHandler;
import org.leo.server.panama.client.tcp.TCPClient;
import org.leo.server.panama.core.connector.impl.TCPResponse;
import org.leo.server.panama.core.handler.tcp.TCPRequestHandler;
import org.leo.server.panama.vpn.configuration.ShadowSocksConfiguration;
import org.leo.server.panama.vpn.handler.ShadowSocksRequestHandler;
import org.leo.server.panama.vpn.proxy.TCPProxy;
import org.leo.server.panama.vpn.proxy.impl.ShadowSocksProxy;
import org.leo.server.panama.vpn.security.wrapper.Wrapper;
import org.leo.server.panama.vpn.security.wrapper.WrapperFactory;
import org.leo.server.panama.vpn.shadowsocks.ShadowsocksRequestResolver;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class StreamRegressionTest {
    private static final byte[] HEADER = {1, 127, 0, 0, 1, 1, (byte)187};

    @Test public void decryptsEveryIvSplitAndOneByteChunks() {
        String[] types = {"aes-128-cfb", "aes-192-cfb", "aes-256-cfb", "aes-128-ofb", "aes-192-ofb", "aes-256-ofb", "bf-cfb"};
        byte[] payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte)i;
        for (String type : types) {
            byte[] encrypted = WrapperFactory.getInstance(type, "test", "encrypt").wrap(payload);
            for (int split = 0; split <= 20; split++) {
                Wrapper decoder = WrapperFactory.getInstance(type, "test", "encrypt");
                ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                append(decoded, decoder.unwrap(Arrays.copyOfRange(encrypted, 0, split)));
                for (int i = split; i < encrypted.length; i++)
                    append(decoded, decoder.unwrap(new byte[]{encrypted[i]}));
                assertArrayEquals(type + " split=" + split, payload, decoded.toByteArray());
            }
        }
    }

    @Test public void parserWaitsForCompleteIpv4DomainAndIpv6Headers() {
        byte[] ipv6 = new byte[19]; ipv6[0] = 4; ipv6[16] = 1; ipv6[18] = 80;
        byte[] domain = {3, 3, 'a', '.', 'b', 0, 80};
        byte[] longestDomain = new byte[259]; longestDomain[0] = 3; longestDomain[1] = (byte)255;
        Arrays.fill(longestDomain, 2, 257, (byte)'a'); longestDomain[258] = 80;
        ShadowsocksRequestResolver parser = new ShadowsocksRequestResolver();
        for (byte[] header : new byte[][]{HEADER, domain, ipv6, longestDomain}) {
            for (int n = 0; n < header.length; n++) assertNull(parser.parse(Arrays.copyOf(header, n)));
            assertNotNull(parser.parse(header));
            assertEquals(0, parser.parse(header).getSubsequentDataLength());
        }
    }

    @Test public void headerOnlyConnectsWithoutLeakingHeaderAndKeepsFollowingPayload() {
        EmbeddedChannel channel = new InetEmbeddedChannel();
        FakeClient target = new FakeClient();
        ShadowSocksProxy proxy = proxy(channel, target, "raw");
        for (byte value : HEADER) proxy.doProxy(new byte[]{value});
        assertEquals(1, target.connections);
        assertEquals("127.0.0.1", target.address.getHostString());
        assertEquals(443, target.address.getPort());
        assertArrayEquals(new byte[0], target.bytes.toByteArray());
        proxy.doProxy(new byte[]{10, 11});
        proxy.doProxy(new byte[]{12});
        assertArrayEquals(new byte[]{10, 11, 12}, target.bytes.toByteArray());
        proxy.close(); assertTrue(target.closed);
        channel.finishAndReleaseAll();
    }

    @Test public void encryptedHeaderAndPayloadSurviveEveryFirstReadBoundary() {
        byte[] plaintext = Arrays.copyOf(HEADER, HEADER.length + 3);
        plaintext[7] = 10; plaintext[8] = 11; plaintext[9] = 12;
        byte[] encrypted = WrapperFactory.getInstance("aes-256-cfb", "test", "encrypt").wrap(plaintext);
        for (int split = 0; split <= encrypted.length; split++) {
            EmbeddedChannel channel = new InetEmbeddedChannel();
            FakeClient target = new FakeClient();
            ShadowSocksProxy proxy = proxy(channel, target, "encrypt");
            proxy.doProxy(Arrays.copyOfRange(encrypted, 0, split));
            proxy.doProxy(Arrays.copyOfRange(encrypted, split, encrypted.length));
            assertEquals(1, target.connections);
            assertArrayEquals(new byte[]{10, 11, 12}, target.bytes.toByteArray());
            channel.finishAndReleaseAll();
        }
    }

    @Test public void requestHandlerHandlesHeapDirectAndSlicedBuffersWithoutLosingBytes() {
        for (boolean direct : new boolean[]{false, true}) {
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            EmbeddedChannel channel = new InetEmbeddedChannel(new TCPRequestHandler(request -> append(received, ((org.leo.server.panama.core.connector.impl.TCPRequest) request).getData())));
            ByteBuf buf = direct ? Unpooled.directBuffer() : Unpooled.buffer();
            buf.writeBytes(new byte[]{99, 1, 2, 3, 88});
            ByteBuf slice = buf.slice(1, 3);
            channel.writeInbound(slice);
            assertArrayEquals(new byte[]{1, 2, 3}, received.toByteArray());
            assertEquals(0, buf.refCnt());
            channel.finishAndReleaseAll();
        }
    }

    @Test public void responseHandlerHandlesHeapBuffersAndNotifiesCloseOnce() {
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicInteger closed = new AtomicInteger();
        TCPClient client = new TCPClient(null, null);
        EmbeddedChannel channel = new InetEmbeddedChannel(new TCPClientHandler(client, new ClientResponseDelegate<TCPResponse>() {
            @Override public void doCompleteResponse(Client c, TCPResponse response) { received.set(response.getData()); }
            @Override public void onConnectClosed(Client c) { closed.incrementAndGet(); }
        }));
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));
        assertArrayEquals(new byte[]{1, 2, 3}, received.get());
        channel.close();
        assertEquals(1, closed.get());
        channel.finishAndReleaseAll();
    }

    @Test public void connectionStateIsReusedAndTargetClosedOnDisconnect() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        ShadowSocksRequestHandler handler = new ShadowSocksRequestHandler(new ShadowSocksConfiguration()) {
            @Override protected TCPProxy createProxy(Channel c, ShadowSocksConfiguration configuration) {
                created.incrementAndGet();
                return new TCPProxy() {
                    @Override public void doProxy(byte[] data) {}
                    @Override public void close() { closed.incrementAndGet(); }
                };
            }
        };
        EmbeddedChannel channel = new InetEmbeddedChannel(new TCPRequestHandler(handler));
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{1}));
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{2}));
        assertEquals(1, created.get());
        channel.close(); channel.close();
        assertEquals(1, closed.get());
        channel.finishAndReleaseAll();
    }

    private ShadowSocksProxy proxy(Channel channel, FakeClient target, String encrypt) {
        ShadowSocksConfiguration config = new ShadowSocksConfiguration();
        config.setEncrypt(encrypt); config.setPassword("test");
        return new ShadowSocksProxy(channel, null, config, null, new ShadowsocksRequestResolver()) {
            @Override protected Client createClient(EventLoopGroup group) { return target; }
        };
    }
    private static void append(ByteArrayOutputStream out, byte[] bytes) { out.write(bytes, 0, bytes.length); }
    private static class InetEmbeddedChannel extends EmbeddedChannel {
        InetEmbeddedChannel(io.netty.channel.ChannelHandler... handlers) { super(handlers); }
        @Override protected java.net.SocketAddress remoteAddress0() { return new InetSocketAddress("127.0.0.1", 12345); }
    }
    private static class FakeClient implements Client {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InetSocketAddress address; int connections; boolean closed;
        @Override public Client connect(InetSocketAddress address) { this.address = address; connections++; return this; }
        @Override public void send(byte[] data, int timeout) { append(bytes, data); }
        @Override public boolean isClose() { return closed; }
        @Override public void setClose(boolean close) { closed = close; }
        @Override public void close() { closed = true; }
    }
}

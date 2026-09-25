package org.leo.server.panama.vpn;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.Test;
import org.leo.server.panama.core.connector.impl.NettyHttpRequest;
import org.leo.server.panama.core.connector.impl.NettyWebSocketRequest;
import org.leo.server.panama.core.connector.impl.UpgradeResponse;
import org.leo.server.panama.core.handler.http.HttpRequestHandler;
import org.leo.server.panama.core.handler.websocket.HttpWebSocketRequestHandler;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class HttpProtocolTest {
    private static class SocketChannel extends EmbeddedChannel {
        SocketChannel(ChannelHandler... handlers) { super(handlers); }
        @Override protected SocketAddress remoteAddress0() { return new InetSocketAddress("127.0.0.1", 12345); }
    }
    @Test public void httpWaitsForLastContentAndDecodesUtf8OnlyAfterAssembly() {
        List<NettyHttpRequest> requests = new ArrayList<>();
        EmbeddedChannel channel = new SocketChannel(new HttpRequestHandler(request -> requests.add((NettyHttpRequest) request)));
        try {
            byte[] body = "name=中文".getBytes(StandardCharsets.UTF_8);
            channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/echo?"));
            DefaultHttpContent first = new DefaultHttpContent(Unpooled.wrappedBuffer(Arrays.copyOf(body, 6)));
            channel.writeInbound(first);
            assertEquals(0, requests.size()); assertEquals(0, first.refCnt());
            channel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(Arrays.copyOfRange(body, 6, body.length))));
            assertEquals(1, requests.size()); assertEquals("中文", requests.get(0).getAttribute("name"));
            assertEquals("echo", requests.get(0).function()); assertEquals("127.0.0.1", requests.get(0).clientIp());
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test public void fullAndPipelinedHttpRequestsRemainSeparateAndReleaseBuffers() {
        List<NettyHttpRequest> requests = new ArrayList<>();
        EmbeddedChannel channel = new SocketChannel(new HttpRequestHandler(request -> requests.add((NettyHttpRequest) request)));
        try {
            FullHttpRequest first = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/one?", Unpooled.copiedBuffer("a=1",StandardCharsets.UTF_8));
            FullHttpRequest second = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/two?b=2");
            channel.writeInbound(first,second);
            assertEquals(2,requests.size()); assertEquals("1",requests.get(0).getAttribute("a"));
            assertEquals("2",requests.get(1).getAttribute("b")); assertNull(requests.get(1).getAttribute("a"));
            assertEquals(0,first.refCnt()); assertEquals(0,second.refCnt());
            requests.get(0).putAttribute("a",null); assertNull(requests.get(0).getAttribute("a"));
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test public void oversizedHttpBodyClosesWithoutCallingApplication() {
        EmbeddedChannel channel = new SocketChannel(new HttpRequestHandler(request -> fail("Oversized request delivered")));
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.POST,"/",Unpooled.buffer(1048577).writeZero(1048577));
        try { channel.writeInbound(request); assertFalse(channel.isActive()); assertEquals(0,request.refCnt()); }
        finally { channel.finishAndReleaseAll(); }
    }
    @Test public void ordinaryHttpStillWorksThroughWebSocketHandlerAndEmptyResponseKeepsFirstHeader() {
        EmbeddedChannel channel = new SocketChannel(new HttpWebSocketRequestHandler(request -> {
            org.leo.server.panama.core.connector.impl.HttpResponse response = new org.leo.server.panama.core.connector.impl.HttpResponse(null);
            response.addHeader("X-Test","first"); request.write(response);
        },request -> fail("Not WebSocket")));
        try {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,HttpMethod.GET,"/");
            request.headers().set(HttpHeaderNames.CONNECTION,"Upgrade"); // Missing Upgrade header must not throw.
            channel.writeInbound(request);
            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response);
            try { assertEquals("first",response.headers().get("X-Test")); assertEquals(0,response.content().readableBytes()); }
            finally { response.release(); }
            assertFalse(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test public void websocketKeepsMessageBoundariesAndReassemblesUtf8AroundPing() {
        List<String> messages = new ArrayList<>();
        EmbeddedChannel channel = new SocketChannel(new HttpWebSocketRequestHandler(request -> {}, request -> messages.add(((NettyWebSocketRequest)request).message())));
        try {
            TextWebSocketFrame a=new TextWebSocketFrame("a"), b=new TextWebSocketFrame("b");
            channel.writeInbound(a,b); assertEquals(Arrays.asList("a","b"),messages);
            assertEquals(0,a.refCnt()); assertEquals(0,b.refCnt());
            byte[] text="中文".getBytes(StandardCharsets.UTF_8);
            channel.writeInbound(new TextWebSocketFrame(false,0,Unpooled.wrappedBuffer(Arrays.copyOf(text,2))));
            PingWebSocketFrame ping=new PingWebSocketFrame(Unpooled.wrappedBuffer(new byte[]{9}));
            channel.writeInbound(ping);
            PongWebSocketFrame pong=channel.readOutbound(); assertNotNull(pong);
            try { assertEquals(9,pong.content().readByte()); } finally { pong.release(); }
            channel.writeInbound(new ContinuationWebSocketFrame(true,0,Unpooled.wrappedBuffer(Arrays.copyOfRange(text,2,text.length))));
            assertEquals(Arrays.asList("a","b","中文"),messages); assertEquals(0,ping.refCnt());
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test public void websocketUpgradeCompletesWithConsumedHttpBody() {
        java.util.concurrent.atomic.AtomicReference<org.leo.server.panama.core.connector.Request> pending = new java.util.concurrent.atomic.AtomicReference<>();
        EmbeddedChannel channel = new SocketChannel(new HttpServerCodec(),new HttpWebSocketRequestHandler(
                pending::set, request -> {}));
        try {
            String upgrade="GET /ws HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive, Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n";
            channel.writeInbound(Unpooled.copiedBuffer(upgrade,StandardCharsets.US_ASCII));
            assertNotNull(pending.get());
            pending.get().write(new UpgradeResponse("",true));
            io.netty.buffer.ByteBuf response=channel.readOutbound(); assertNotNull("Handshake stalled",response);
            try { assertTrue(response.toString(StandardCharsets.US_ASCII).contains("101 Switching Protocols")); }
            finally { response.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }
}

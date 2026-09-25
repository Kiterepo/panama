package org.leo.server.panama.core.handler.websocket;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.ReferenceCountUtil;
import org.leo.server.panama.core.connector.impl.NettyWebSocketRequest;
import org.leo.server.panama.core.connector.impl.WebSocketUpgradeRequest;
import org.leo.server.panama.core.handler.RequestHandler;
import org.leo.server.panama.core.handler.http.HttpRequestHandler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class HttpWebSocketRequestHandler extends HttpRequestHandler {
    private final RequestHandler webSocketHandler;
    private ByteArrayOutputStream text;
    private WebSocketUpgradeRequest upgrade;

    public HttpWebSocketRequestHandler(RequestHandler http, RequestHandler webSocket) {
        super(http); webSocketHandler = webSocket;
    }
    @Override protected void completeRequest(ChannelHandlerContext ctx, HttpRequest request, byte[] body) {
        if (!request.headers().containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true)
                || !HttpHeaderValues.WEBSOCKET.contentEqualsIgnoreCase(request.headers().get(HttpHeaderNames.UPGRADE))) {
            super.completeRequest(ctx, request, body); return;
        }
        FullHttpRequest full = new DefaultFullHttpRequest(request.protocolVersion(), request.method(), request.uri(), Unpooled.wrappedBuffer(body));
        full.headers().set(request.headers());
        try {
            upgrade = new WebSocketUpgradeRequest(ctx, full);
            upgrade.setMessage("");
            doRequest(upgrade);
        } finally { full.release(); }
    }
    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof WebSocketFrame)) { super.channelRead(ctx, msg); return; }
        WebSocketFrame frame = (WebSocketFrame) msg;
        try {
            if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain())); return;
            }
            if (frame instanceof PongWebSocketFrame) return;
            if (frame instanceof CloseWebSocketFrame) {
                text = null;
                if (upgrade != null) { upgrade.setCloseWebSocketFrame((CloseWebSocketFrame) frame); upgrade.close(); }
                else ctx.close();
                return;
            }
            if (frame instanceof TextWebSocketFrame) {
                if (text != null) throw new IllegalArgumentException("Overlapping fragmented text messages");
                text = new ByteArrayOutputStream();
            } else if (!(frame instanceof ContinuationWebSocketFrame) || text == null) {
                throw new IllegalArgumentException("Unsupported WebSocket frame");
            }
            int length = frame.content().readableBytes();
            if (length > MAX_MESSAGE_LENGTH - text.size()) throw new IllegalArgumentException("WebSocket message too large");
            byte[] data = new byte[length]; frame.content().readBytes(data); text.write(data, 0, data.length);
            if (frame.isFinalFragment()) {
                String message = new String(text.toByteArray(), StandardCharsets.UTF_8); text = null;
                TextWebSocketFrame complete = new TextWebSocketFrame(message);
                try {
                    NettyWebSocketRequest request = new NettyWebSocketRequest(ctx, complete);
                    request.setMessage(message);
                    webSocketHandler.doRequest(request);
                } finally { complete.release(); }
            }
        } finally { ReferenceCountUtil.release(frame); }
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        text = null; upgrade = null; super.channelInactive(ctx);
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        text = null; super.exceptionCaught(ctx, cause);
    }
}

package org.leo.server.panama.core.handler.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.leo.server.panama.core.connector.impl.NettyHttpRequest;
import org.leo.server.panama.core.handler.RequestHandler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class HttpRequestHandler extends ChannelInboundHandlerAdapter {
    protected static final int MAX_MESSAGE_LENGTH = 1024 * 1024;
    private io.netty.handler.codec.http.HttpRequest pending;
    private ByteArrayOutputStream body;
    private final RequestHandler requestHandler;
    public HttpRequestHandler(RequestHandler handler) { requestHandler = handler; }

    @Override public void channelActive(ChannelHandlerContext ctx) throws Exception {
        requestHandler.onConnect(ctx); super.channelActive(ctx);
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        pending = null; body = null;
        requestHandler.onClose(ctx); super.channelInactive(ctx);
    }
    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof HttpObject)) { ctx.fireChannelRead(msg); return; }
        try {
            if (!((HttpObject) msg).decoderResult().isSuccess()) throw new IllegalArgumentException("Invalid HTTP request");
            if (msg instanceof io.netty.handler.codec.http.HttpRequest) {
                if (pending != null) throw new IllegalArgumentException("HTTP request before previous body completed");
                io.netty.handler.codec.http.HttpRequest request = (io.netty.handler.codec.http.HttpRequest) msg;
                pending = new DefaultHttpRequest(request.protocolVersion(), request.method(), request.uri());
                pending.headers().set(request.headers());
                body = new ByteArrayOutputStream();
                if (HttpUtil.getContentLength(request, 0) > MAX_MESSAGE_LENGTH)
                    throw new IllegalArgumentException("HTTP body too large");
                if (HttpUtil.is100ContinueExpected(request))
                    ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
            }
            // FullHttpRequest implements both interfaces, so these must be separate checks.
            if (msg instanceof HttpContent) {
                if (pending == null) throw new IllegalArgumentException("HTTP content without request");
                ByteBuf content = ((HttpContent) msg).content();
                if (content.readableBytes() > MAX_MESSAGE_LENGTH - body.size())
                    throw new IllegalArgumentException("HTTP body too large");
                byte[] data = new byte[content.readableBytes()]; content.readBytes(data);
                body.write(data, 0, data.length);
                if (msg instanceof LastHttpContent) {
                    io.netty.handler.codec.http.HttpRequest request = pending;
                    byte[] complete = body.toByteArray(); pending = null; body = null;
                    completeRequest(ctx, request, complete);
                }
            }
        } finally { ReferenceCountUtil.release(msg); }
    }
    protected void completeRequest(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpRequest request, byte[] body) {
        NettyHttpRequest complete = new NettyHttpRequest(ctx, request);
        complete.setMessage(new String(body, StandardCharsets.UTF_8));
        doRequest(complete);
    }
    @Override public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush(); super.channelReadComplete(ctx);
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        pending = null; body = null; ctx.close();
    }
    protected void doRequest(org.leo.server.panama.core.connector.impl.HttpRequest request) {
        requestHandler.doRequest(request);
    }
}

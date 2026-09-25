package org.leo.server.panama.client.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.leo.server.panama.client.ClientResponseDelegate;
import org.leo.server.panama.client.tcp.TCPClient;
import org.leo.server.panama.core.connector.impl.TCPResponse;

import java.io.ByteArrayOutputStream;

public class TCPClientHandler extends ChannelInboundHandlerAdapter {
    private ClientResponseDelegate clientResponseDelegate;
    private TCPClient tcpClient;
    private ByteArrayOutputStream completeData;

    public TCPClientHandler(TCPClient tcpClient, ClientResponseDelegate clientResponseDelegate) {
        this.clientResponseDelegate = clientResponseDelegate;
        this.tcpClient = tcpClient;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        byte []readData = read(ctx, msg);
        if (clientResponseDelegate == null) return;
        if (clientResponseDelegate.shouldDoPerResponse()) {
            clientResponseDelegate.doPerResponse(tcpClient, new TCPResponse(readData));
        }

        if (clientResponseDelegate.shouldDoCompleteResponse()) {
            if (completeData == null) completeData = new ByteArrayOutputStream(readData.length);
            completeData.write(readData, 0, readData.length);
        }

//        super.channelRead(ctx, msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        try {
            if (null == completeData) {
                super.channelReadComplete(ctx);
                return;
            }

            if (null != clientResponseDelegate) {
                clientResponseDelegate.doCompleteResponse(tcpClient, new TCPResponse(completeData.toByteArray()));
            }

            completeData = null;
            super.channelReadComplete(ctx);
        } finally {
            if (null != clientResponseDelegate) {
                clientResponseDelegate.onResponseComplete(tcpClient);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        completeData = null;
        cause.printStackTrace();
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        completeData = null;
        if (tcpClient.getConnectFuture() == null || tcpClient.getConnectFuture().channel() == ctx.channel()) {
            tcpClient.setClose(true);
            if (clientResponseDelegate != null) clientResponseDelegate.onConnectClosed(tcpClient);
        }
        super.channelInactive(ctx);
    }

    protected byte[] read(ChannelHandlerContext ctx, Object msg) {
        ByteBuf byteBuf = (ByteBuf) msg;

        try {
            byte[] dataSequence = new byte[byteBuf.readableBytes()];
            byteBuf.readBytes(dataSequence);
            return dataSequence;
        } finally {
            ReferenceCountUtil.release(byteBuf);
        }
    }
}

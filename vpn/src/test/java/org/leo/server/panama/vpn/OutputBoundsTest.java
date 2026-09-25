package org.leo.server.panama.vpn;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.leo.server.panama.core.util.BoundedWrites;
import org.leo.server.panama.vpn.reverse.constant.ReverseConstants;
import org.leo.server.panama.vpn.reverse.core.ReverseCoreServer;
import org.leo.server.panama.vpn.reverse.protocol.ReverseProtocol;
import org.leo.server.panama.util.NumberUtils;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.junit.Assert.*;

public class OutputBoundsTest {
    @Test public void slowOutputIsBoundedAndCompletedWritesFreeCapacity() {
        List<ByteBuf> held=new ArrayList<>();List<ChannelPromise> promises=new ArrayList<>();
        EmbeddedChannel channel=new EmbeddedChannel(new ChannelOutboundHandlerAdapter(){
            @Override public void write(ChannelHandlerContext ctx,Object msg,ChannelPromise promise){held.add((ByteBuf)msg);promises.add(promise);}
        });
        try {
            ChannelFuture first=BoundedWrites.writeAndFlush(channel,Unpooled.buffer(BoundedWrites.MAX_PENDING_BYTES).writeZero(BoundedWrites.MAX_PENDING_BYTES));
            assertFalse(first.isDone());
            ByteBuf overflow=Unpooled.buffer(1).writeByte(1);
            ChannelFuture rejected=BoundedWrites.writeAndFlush(channel,overflow);
            assertTrue(rejected.isDone());assertFalse(rejected.isSuccess());assertEquals(0,overflow.refCnt());assertEquals(1,held.size());
            held.remove(0).release();promises.remove(0).setSuccess();assertTrue(first.isSuccess());
            ByteBuf next=Unpooled.buffer(1).writeByte(2);BoundedWrites.writeAndFlush(channel,next);
            assertEquals(1,held.size());held.remove(0).release();promises.remove(0).setSuccess();
        }finally {
            for(ByteBuf b:held)b.release();for(ChannelPromise p:promises)p.tryFailure(new java.io.IOException("test cleanup"));
            channel.finishAndReleaseAll();
        }
    }
    @Test public void closeMarkerInOrdinaryDataIsEscapedWithoutChangingPeerProtocol() throws Exception {
        byte[] data=NumberUtils.intToByteArray(ReverseConstants.CLOSE_MAGIC);
        ByteBuf wire=ReverseProtocol.encodeDataProtocol(123,data);
        byte[] bytes=new byte[wire.readableBytes()];try{wire.readBytes(bytes);}finally{wire.release();}
        ByteArrayOutputStream actual=new ByteArrayOutputStream();
        List<ReverseProtocol.ReverseProtocolData> frames=new ReverseProtocol.Decoder().feed(bytes);
        assertEquals(2,frames.size());
        for(ReverseProtocol.ReverseProtocolData frame:frames){assertEquals(123,frame.getTag());assertFalse(ReverseCoreServer.isClose(frame.getData()));actual.write(frame.getData());}
        assertArrayEquals(data,actual.toByteArray());
    }
}

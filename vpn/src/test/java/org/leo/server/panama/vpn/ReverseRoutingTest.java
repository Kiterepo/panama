package org.leo.server.panama.vpn;

import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.leo.server.panama.core.handler.tcp.TCPRequestHandler;
import org.leo.server.panama.vpn.reverse.core.ReverseCoreServer;
import org.leo.server.panama.vpn.reverse.protocol.ReverseProtocol;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ReverseRoutingTest {
    private static class Tunnel extends EmbeddedChannel {
        Tunnel(ReverseCoreServer server) { super(new TCPRequestHandler(server)); }
        @Override protected SocketAddress remoteAddress0() { return new InetSocketAddress("127.0.0.1",12345); }
        void discardSent() { runPendingTasks(); ByteBuf data; while((data=readOutbound())!=null)data.release(); }
    }
    @Test public void tunnelsKeepIndependentDecodersAndOnlyCloseTheirOwnStreams() {
        ReverseCoreServer server=new ReverseCoreServer(0);
        Tunnel first=new Tunnel(server), second=new Tunnel(server);
        AtomicInteger firstClosed=new AtomicInteger(), secondClosed=new AtomicInteger();
        List<byte[]> a=new ArrayList<>(),b=new ArrayList<>();
        try {
            server.send2Client(1,new byte[]{1},a::add,firstClosed::incrementAndGet);
            server.send2Client(2,new byte[]{2},b::add,secondClosed::incrementAndGet);
            first.discardSent(); second.discardSent();
            ByteBuf frame=ReverseProtocol.encodeProtocol(1,new byte[]{11,12});
            first.writeInbound(frame.readRetainedSlice(3));
            second.writeInbound(ReverseProtocol.encodeProtocol(2,new byte[]{21,22}));
            first.writeInbound(frame); // Rest of split header and payload.
            assertEquals(1,a.size()); assertEquals(1,b.size());
            assertArrayEquals(new byte[]{11,12},a.get(0)); assertArrayEquals(new byte[]{21,22},b.get(0));
            second.writeInbound(ReverseProtocol.encodeProtocol(1,new byte[]{99}));
            assertEquals("Other tunnel must not inject data",1,a.size());
            first.close(); assertEquals(1,firstClosed.get()); assertEquals(0,secondClosed.get());
            server.send2Client(2,new byte[]{3},b::add,secondClosed::incrementAndGet);
            second.runPendingTasks(); ByteBuf sent=second.readOutbound(); assertNotNull(sent);
            try { assertEquals(2,sent.readInt()); assertEquals(1,sent.readInt()); assertEquals(3,sent.readByte()); } finally {sent.release();}
            second.writeInbound(ReverseProtocol.encodeProtocol(2,new byte[]{23}));
            assertEquals(2,b.size()); second.close(); assertEquals(1,secondClosed.get());
        } finally { first.finishAndReleaseAll();second.finishAndReleaseAll();server.shutdown(); }
    }
    @Test public void unavailableInnerFailsImmediatelyAndExplicitCloseRemovesCallback() {
        ReverseCoreServer server=new ReverseCoreServer(0);
        AtomicInteger closed=new AtomicInteger();
        server.send2Client(1,new byte[]{1},data->fail("No peer"),closed::incrementAndGet);
        assertEquals(1,closed.get());
        Tunnel peer=new Tunnel(server);
        try {
            server.send2Client(2,new byte[]{2},data->fail("Closed stream delivered"),closed::incrementAndGet);
            peer.discardSent(); server.closeStream(2); peer.discardSent();
            peer.writeInbound(ReverseProtocol.encodeProtocol(2,new byte[]{3}));
            peer.close(); assertEquals(1,closed.get());
        } finally { peer.finishAndReleaseAll();server.shutdown(); }
    }
}

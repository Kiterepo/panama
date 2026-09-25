package org.leo.server.panama.vpn;

import io.netty.channel.*;
import org.junit.Test;
import org.leo.server.panama.server.tcp.TCPServer;
import org.leo.server.panama.vpn.configuration.ShadowSocksConfiguration;
import org.leo.server.panama.vpn.handler.*;
import org.leo.server.panama.vpn.reverse.core.*;
import org.leo.server.panama.vpn.reverse.server.ReverseShadowSocksServer;
import org.leo.server.panama.vpn.security.wrapper.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class OtherModesIntegrationTest {
    @Test(timeout=20000) public void proxyPassThroughAndCipherTranslationRelayLargePayloads() throws Exception {
        runChain(false,false,"encrypt"); runChain(false,true,"encrypt");
    }
    @Test(timeout=20000) public void proxyFramedCompressionAndPaddingHandleFragmentedHandshake() throws Exception {
        runChain(false,true,"compress"); runChain(false,true,"zero-padding");runChain(false,true,"random-padding");
    }
    @Test(timeout=20000) public void reverseInnerOuterRelayAndCleanUpWithBothCipherPaths() throws Exception {
        runChain(true,false,"encrypt");runChain(true,true,"encrypt");runChain(true,true,"compress");
    }
    private void runChain(boolean reverse,boolean translate,String wrapping) throws Exception {
        TCPServer back=null,front=null;
        ReverseCoreServer tunnel=null; ReverseShadowSocksServer inner=null;
        ExecutorService worker=Executors.newSingleThreadExecutor();
        byte[] payload=new byte[1024*1024];new Random(2).nextBytes(payload);
        try(ServerSocket target=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            target.setSoTimeout(5000);
            Future<?> targetDone=worker.submit(()-> {
                try(Socket socket=target.accept()) {
                    socket.setSoTimeout(10000);socket.getOutputStream().write(42);
                    byte[] data=new byte[payload.length]; new DataInputStream(socket.getInputStream()).readFully(data);
                    assertArrayEquals(payload,data);socket.getOutputStream().write(data);socket.getOutputStream().flush();
                    assertEquals("Client close must reach target",-1,socket.getInputStream().read());
                }catch(IOException error){throw new UncheckedIOException(error);}
            });
            ShadowSocksConfiguration backend=new ShadowSocksConfiguration();
            backend.setType(translate?"aes-128-cfb":"aes-256-cfb");backend.setPassword(translate?"inner-test":"outer-test");backend.setEncrypt(wrapping);
            ShadowSocksConfiguration frontend=new ShadowSocksConfiguration();
            frontend.setPassword("outer-test");frontend.setEncrypt(wrapping);frontend.setProxyType(backend.getType());frontend.setProxyPassword(backend.getPassword());
            if(reverse) {
                CountDownLatch connected=new CountDownLatch(1);
                tunnel=new ReverseCoreServer(0){ @Override public synchronized void onConnect(ChannelHandlerContext ctx){super.onConnect(ctx);connected.countDown();} };
                tunnel.bind(1).sync();backend.setReverseHost("127.0.0.1");backend.setReversePort(tunnel.port());
                inner=new ReverseShadowSocksServer(backend);inner.start(1);assertTrue("Inner did not connect",connected.await(5,TimeUnit.SECONDS));
                front=new TCPServer(0,new Redirect2ReverseShadowSocksRequestHandler(frontend,tunnel));
            }else {
                back=new TCPServer(0,new ShadowSocksRequestHandler(backend));back.bind(1).sync();
                frontend.setProxy("localhost");frontend.setProxyPort(back.port());front=new TCPServer(0,new AgentShadowSocksRequestHandler(frontend));
            }
            front.bind(1).sync();
            try(Socket client=new Socket("127.0.0.1",front.port())) {
                client.setSoTimeout(10000); Wrapper codec=WrapperFactory.getInstance(frontend.getType(),frontend.getPassword(),wrapping);
                int port=target.getLocalPort();byte[] header={1,127,0,0,1,(byte)(port>>>8),(byte)port};
                byte[] wire=codec.wrap(header);
                for(byte value:wire){client.getOutputStream().write(value);client.getOutputStream().flush();}
                assertArrayEquals(new byte[]{42},readPlain(client,codec,1));
                client.getOutputStream().write(codec.wrap(payload));client.getOutputStream().flush();
                assertArrayEquals(payload,readPlain(client,codec,payload.length));
            }
            targetDone.get(5,TimeUnit.SECONDS);
        }finally {
            if(front!=null)front.shutdown().get(5,TimeUnit.SECONDS);
            if(inner!=null)inner.shutdown().get(5,TimeUnit.SECONDS);
            if(tunnel!=null)tunnel.shutdown().get(5,TimeUnit.SECONDS);
            if(back!=null)back.shutdown().get(5,TimeUnit.SECONDS);
            worker.shutdownNow();
        }
    }
    @Test(timeout=15000) public void reverseReconnectsAfterDisconnectButExplicitShutdownIsFinal() throws Exception {
        CountDownLatch twice=new CountDownLatch(2),first=new CountDownLatch(1);
        AtomicReference<Channel> accepted=new AtomicReference<>();
        ReverseCoreServer server=new ReverseCoreServer(0) {
            @Override public synchronized void onConnect(ChannelHandlerContext ctx) {
                super.onConnect(ctx);accepted.set(ctx.channel());first.countDown();twice.countDown();
            }
        };
        ReverseCoreClient client=null;
        try {
            server.bind(1).sync();client=new ReverseCoreClient(new InetSocketAddress("127.0.0.1",server.port()),data->{});
            client.connect(null);assertTrue(first.await(3,TimeUnit.SECONDS));accepted.get().close().sync();
            assertTrue("Automatic reconnect did not happen",twice.await(7,TimeUnit.SECONDS));
            client.shutdown().get(3,TimeUnit.SECONDS);client.shutdown().get(3,TimeUnit.SECONDS);client.connect(null);
            assertFalse(client.channel().isActive());
        }finally {if(client!=null)client.shutdown().get(3,TimeUnit.SECONDS);server.shutdown().get(3,TimeUnit.SECONDS);}
    }
    @Test(timeout=10000) public void serverShutdownClosesAcceptedSocketsAndBindFailuresReleaseResources() throws Exception {
        TCPServer server=new TCPServer(0,request->{});TCPServer conflict=null;
        try {
            server.bind(1).sync();
            try(Socket socket=new Socket("127.0.0.1",server.port())) {
                socket.setSoTimeout(3000);
                conflict=new TCPServer(server.port(),request->{});
                ChannelFuture failed=conflict.bind(1);failed.await(3,TimeUnit.SECONDS);assertTrue(failed.isDone());assertFalse(failed.isSuccess());
                conflict.shutdown().get(3,TimeUnit.SECONDS);
                server.shutdown().get(3,TimeUnit.SECONDS);assertEquals(-1,socket.getInputStream().read());
            }
            TCPServer race=new TCPServer(0,request->{});race.bind(1);race.shutdown().get(3,TimeUnit.SECONDS);
        }finally{server.shutdown().get(3,TimeUnit.SECONDS);if(conflict!=null)conflict.shutdown().get(3,TimeUnit.SECONDS);}
    }
    private byte[] readPlain(Socket socket,Wrapper codec,int size)throws IOException {
        ByteArrayOutputStream result=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
        while(result.size()<size){int n=socket.getInputStream().read(buffer);if(n<0)throw new EOFException();result.write(codec.unwrap(Arrays.copyOf(buffer,n)));}
        assertEquals(size,result.size());return result.toByteArray();
    }
}

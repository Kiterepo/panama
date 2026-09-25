package org.leo.server.panama.vpn.reverse.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import org.leo.server.panama.server.Server;
import org.leo.server.panama.util.NumberUtils;
import org.leo.server.panama.vpn.configuration.ShadowSocksConfiguration;
import org.leo.server.panama.vpn.proxy.Proxy;
import org.leo.server.panama.vpn.proxy.factory.ShadowSocksProxyFactory;
import org.leo.server.panama.vpn.proxy.impl.ReverseShadowSocksProxy;
import org.leo.server.panama.vpn.reverse.constant.ReverseConstants;
import org.leo.server.panama.vpn.reverse.core.ReverseCoreClient;
import org.leo.server.panama.vpn.reverse.core.ReverseCoreServer;
import org.leo.server.panama.vpn.reverse.protocol.ReverseProtocol;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

public class ReverseShadowSocksServer implements Server {
    private final ReverseCoreClient reverseCoreClient;
    private final ShadowSocksConfiguration configuration;
    private final ReverseProtocol.Decoder decoder = new ReverseProtocol.Decoder();
    // All access is confined to the reverse client's event loop.
    private final Map<Integer, Proxy> proxies = new HashMap<>();
    private static final int MAX_STREAMS = 20000;

    public ReverseShadowSocksServer(ShadowSocksConfiguration configuration) {
        this.configuration = configuration;
        this.reverseCoreClient = new ReverseCoreClient(InetSocketAddress.createUnresolved(
                configuration.getReverseHost(), configuration.getReversePort()), this::doRequest, this::clearStreams);
    }
    @Override public void start(int maxThread) { reverseCoreClient.connect(null); }
    @Override public int port() { return configuration.getReversePort(); }
    @Override public Future<?> shutdown() { return reverseCoreClient.shutdown(); }

    protected Proxy createProxy(int tag) {
        ReverseShadowSocksProxy proxy = (ReverseShadowSocksProxy) ShadowSocksProxyFactory.createReverseShadowSocksProxy(
                reverseCoreClient.channel(), () -> closeStream(tag, true), configuration);
        proxy.setAppendTagFunc(data -> ReverseProtocol.encodeDataProtocol(tag, data));
        return proxy;
    }
    private void doRequest(byte[] data) {
        for (ReverseProtocol.ReverseProtocolData frame : decoder.feed(data)) {
            int tag = frame.getTag();
            if (ReverseCoreServer.isClose(frame.getData())) { closeStream(tag, false); continue; }
            Proxy proxy = proxies.get(tag);
            if (proxy == null) {
                if (proxies.size() >= MAX_STREAMS) { sendClose(tag); continue; }
                proxy = createProxy(tag);
                proxies.put(tag, proxy);
            }
            try { proxy.doProxy(frame.getData()); }
            catch (RuntimeException error) { closeStream(tag, true); }
        }
    }
    private void closeStream(int tag, boolean notifyPeer) {
        Proxy proxy = proxies.remove(tag);
        if (proxy == null) return;
        proxy.close();
        if (notifyPeer) sendClose(tag);
    }
    private void clearStreams() {
        decoder.reset();
        Proxy[] previous = proxies.values().toArray(new Proxy[0]);
        proxies.clear();
        for (Proxy proxy : previous) proxy.close();
    }
    private void sendClose(int tag) {
        Channel channel = reverseCoreClient.channel();
        if (channel != null && channel.isActive()) org.leo.server.panama.core.util.BoundedWrites.writeAndFlush(channel, ReverseProtocol.encodeProtocol(tag,
                NumberUtils.intToByteArray(ReverseConstants.CLOSE_MAGIC)))
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    }
}

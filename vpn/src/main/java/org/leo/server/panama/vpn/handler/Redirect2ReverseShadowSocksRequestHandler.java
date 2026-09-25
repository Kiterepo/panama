package org.leo.server.panama.vpn.handler;

import io.netty.channel.Channel;
import org.leo.server.panama.vpn.configuration.ShadowSocksConfiguration;
import org.leo.server.panama.vpn.proxy.TCPProxy;
import org.leo.server.panama.vpn.proxy.factory.ShadowSocksProxyFactory;
import org.leo.server.panama.vpn.reverse.core.ReverseCoreServer;

public class Redirect2ReverseShadowSocksRequestHandler extends AgentShadowSocksRequestHandler implements AutoCloseable {
    private final ReverseCoreServer reverseServer;
    public Redirect2ReverseShadowSocksRequestHandler(ShadowSocksConfiguration configuration) {
        this(configuration, ShadowSocksProxyFactory.createReverseServer(configuration));
    }
    public Redirect2ReverseShadowSocksRequestHandler(ShadowSocksConfiguration configuration, ReverseCoreServer reverseServer) {
        super(configuration);
        this.reverseServer = reverseServer;
    }
    @Override protected TCPProxy createProxy(Channel channel, ShadowSocksConfiguration configuration) {
        return ShadowSocksProxyFactory.createRedirect2ReverseShadowSocksProxy(
                channel, () -> this.close(channel), configuration, reverseServer);
    }
    @Override public void close() { reverseServer.shutdown(); }
}

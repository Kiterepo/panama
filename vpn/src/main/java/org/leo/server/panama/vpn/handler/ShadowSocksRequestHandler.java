package org.leo.server.panama.vpn.handler;

import io.netty.util.AttributeKey;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.Channel;
import org.leo.server.panama.core.connector.impl.TCPRequest;
import org.leo.server.panama.core.handler.RequestHandler;
import org.leo.server.panama.vpn.configuration.ShadowSocksConfiguration;
import org.leo.server.panama.vpn.proxy.TCPProxy;
import org.leo.server.panama.vpn.proxy.factory.ShadowSocksProxyFactory;

public class ShadowSocksRequestHandler implements RequestHandler<TCPRequest> {
    // Cipher and destination state belong to the connection, never to an expiring cache.
    private static final AttributeKey<TCPProxy> PROXY = AttributeKey.valueOf(ShadowSocksRequestHandler.class, "proxy");

    private ShadowSocksConfiguration shadowSocksConfiguration;
    public ShadowSocksRequestHandler(ShadowSocksConfiguration shadowSocksConfiguration) {
        this.shadowSocksConfiguration = shadowSocksConfiguration;
    }

    protected TCPProxy createProxy(Channel channel, ShadowSocksConfiguration shadowSocksConfiguration) {
        return ShadowSocksProxyFactory.createShadowSocksProxy(
                channel,
                () -> this.close(channel),
                shadowSocksConfiguration);
    }

    protected void close(Channel channel) {
        TCPProxy proxy = channel.attr(PROXY).getAndSet(null);
        if (proxy != null) proxy.close();
    }

    @Override
    public void onClose(ChannelHandlerContext ctx) {
        close(ctx.channel());
    }

    @Override
    public void doRequest(TCPRequest request) {
        Channel channel = request.getChannelHandlerContext().channel();
        TCPProxy proxy = channel.attr(PROXY).get();
        if (null == proxy) {
            proxy = createProxy(channel, shadowSocksConfiguration);
            channel.attr(PROXY).set(proxy);
        }

        proxy.doProxy(request.getData());
    }
}

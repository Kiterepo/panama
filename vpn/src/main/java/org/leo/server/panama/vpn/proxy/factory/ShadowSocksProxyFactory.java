package org.leo.server.panama.vpn.proxy.factory;

import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.leo.server.panama.vpn.configuration.ShadowSocksConfiguration;
import org.leo.server.panama.vpn.proxy.TCPProxy;
import org.leo.server.panama.vpn.proxy.impl.*;
import org.leo.server.panama.vpn.reverse.core.ReverseCoreServer;
import org.leo.server.panama.vpn.shadowsocks.ShadowsocksRequestResolver;
import org.leo.server.panama.vpn.util.Callback;

/**
 * @author xuyangze
 * @date 2018/11/20 8:19 PM
 */
public class ShadowSocksProxyFactory {
    // 发送请求给代理服务器
    private static final NioEventLoopGroup eventLoopGroup = null; // Relays use their inbound channel event loop.

    // 代理服务请求返回数据解析
    private static ShadowsocksRequestResolver requestResolver = new ShadowsocksRequestResolver();

    /** Bind before accepting client traffic; each outer application owns its tunnel server. */
    public static ReverseCoreServer createReverseServer(ShadowSocksConfiguration configuration) {
        ReverseCoreServer server = new ReverseCoreServer(configuration.getReversePort());
        server.bind(1).syncUninterruptibly();
        return server;
    }

    public static TCPProxy createRePlayShadowSocksProxy(Channel channel, Callback callback, ShadowSocksConfiguration shadowSocksConfiguration) {
        // 测试代理，测试用
        return new RePlayShadowSocksProxy(
                channel,
                callback,
                shadowSocksConfiguration,
                eventLoopGroup,
                requestResolver);
    }

    public static TCPProxy createReverseShadowSocksProxy(Channel channel, Callback callback, ShadowSocksConfiguration shadowSocksConfiguration) {
        // 反向代理TCP服务
        return new ReverseShadowSocksProxy(
                channel,
                callback,
                shadowSocksConfiguration,
                eventLoopGroup,
                requestResolver);
    }

    public static TCPProxy createRedirect2ReverseShadowSocksProxy(Channel channel, Callback callback, ShadowSocksConfiguration shadowSocksConfiguration, ReverseCoreServer reverseCoreServer) {
        // 反向代理TCP服务
        return new Redirect2ReverseShadowSocksProxy(
                channel,
                callback,
                shadowSocksConfiguration,
                eventLoopGroup,
                requestResolver,
                reverseCoreServer);
    }

    public static TCPProxy createAgentShadowSocksProxy(Channel channel, Callback callback, ShadowSocksConfiguration shadowSocksConfiguration) {
        return new AgentShadowSocksProxy(
                channel,
                callback,
                shadowSocksConfiguration,
                eventLoopGroup,
                requestResolver);
    }

    public static TCPProxy createShadowSocksProxy(Channel channel, Callback callback, ShadowSocksConfiguration shadowSocksConfiguration) {
        return new ShadowSocksProxy(
                channel,
                callback,
                shadowSocksConfiguration,
                eventLoopGroup,
                requestResolver);
    }
}

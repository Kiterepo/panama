package org.leo.server.panama.vpn.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import java.net.InetSocketAddress;
import org.apache.log4j.Logger;
import org.leo.server.panama.client.AbstractClient;
import org.leo.server.panama.client.Client;
import org.leo.server.panama.client.ClientResponseDelegate;
import org.leo.server.panama.client.tcp.TCPClient;
import org.leo.server.panama.core.connector.impl.TCPResponse;
import org.leo.server.panama.vpn.configuration.ShadowSocksConfiguration;
import org.leo.server.panama.vpn.security.wrapper.Wrapper;
import org.leo.server.panama.vpn.security.wrapper.WrapperFactory;
import org.leo.server.panama.vpn.shadowsocks.ShadowsocksRequestResolver;
import org.leo.server.panama.vpn.util.Callback;

/**
 * @author xuyangze
 * @date 2018/11/20 8:13 PM
 */
public abstract class AbstractShadowSocksProxy implements ClientResponseDelegate<TCPResponse>, TCPProxy {
    private final static Logger log = Logger.getLogger(AbstractShadowSocksProxy.class);

    private static final DnsAddressResolverGroup DNS = new DnsAddressResolverGroup(
            NioDatagramChannel.class, DnsServerAddressStreamProviders.platformDefault());

    protected Channel clientChannel;
    protected Wrapper wrapper;
    protected Client redirectClient;
    protected Callback finish;
    // 发送请求给代理服务器
    protected NioEventLoopGroup eventLoopGroup;

    // 代理服务请求返回数据解析
    protected ShadowsocksRequestResolver requestResolver;

    // 配置信息
    protected ShadowSocksConfiguration shadowSocksConfiguration;

    public AbstractShadowSocksProxy(Channel clientChannel, Callback finish, ShadowSocksConfiguration shadowSocksConfiguration, NioEventLoopGroup eventLoopGroup, ShadowsocksRequestResolver requestResolver) {
        this.clientChannel = clientChannel;
        wrapper = WrapperFactory.getInstance(shadowSocksConfiguration.getType(), shadowSocksConfiguration.getPassword(), shadowSocksConfiguration.getEncrypt());
        this.finish = finish;
        this.eventLoopGroup = eventLoopGroup;
        this.requestResolver = requestResolver;
        this.shadowSocksConfiguration = shadowSocksConfiguration;
    }

    @Override
    public boolean shouldDoPerResponse() {
        return true;
    }

    @Override
    public boolean shouldDoCompleteResponse() {
        return false;
    }

    @Override
    public void doPerResponse(Client client, TCPResponse response) {
        // target -> proxy -> client
        if (log.isDebugEnabled()) log.debug(" proxy <---------------- target " + response.getData().length + " byte");
        send2Client(response.getData());
    }

    @Override
    public void onConnectClosed(Client client) {
        // send close data
        log.info("client <----------------  proxy closed");
        if (null != finish) {
            try {
                finish.call();
            } catch (Exception e) {
                //
            }
        }

        clientChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    protected void send2Client(byte []data) {
        data = wrapper.wrap(data);
        if (data.length == 0) return;
        clientChannel.writeAndFlush(Unpooled.wrappedBuffer(data))
                .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        if (log.isDebugEnabled()) log.debug("client <----------------  proxy " + data.length + " byte");
    }

    protected void sendRequest2Target(byte[] data, String target, int port) {
        if (redirectClient == null) {
            // Both ends of a normal relay share one event loop, preserving cipher/write order.
            redirectClient = createClient(clientChannel.eventLoop());
            if (redirectClient instanceof AbstractClient) {
                boolean flowControl = useTransportBackpressure();
                if (flowControl) clientChannel.config().setAutoRead(false);
                ChannelFuture connection = ((AbstractClient) redirectClient).connectAsync(
                        InetSocketAddress.createUnresolved(target, port), DNS);
                connection.addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        onConnectClosed(redirectClient);
                        return;
                    }
                    if (!clientChannel.isActive()) {
                        redirectClient.close();
                        return;
                    }
                    if (flowControl) {
                        Channel targetChannel = future.channel();
                        targetChannel.pipeline().addLast(new RelayBackpressure(clientChannel));
                        clientChannel.pipeline().addLast(new RelayBackpressure(targetChannel));
                        targetChannel.config().setAutoRead(clientChannel.isWritable());
                        clientChannel.config().setAutoRead(targetChannel.isWritable());
                    }
                });
            } else {
                redirectClient.connect(InetSocketAddress.createUnresolved(target, port));
            }
        }
        redirectClient.send(data, 0);
    }

    // Multiplexed reverse tunnels cannot pause a shared transport for a single stream.
    protected boolean useTransportBackpressure() {
        return true;
    }

    @Override
    public void close() {
        if (redirectClient != null) redirectClient.close();
    }

    private static final class RelayBackpressure extends ChannelInboundHandlerAdapter {
        private final Channel source;

        private RelayBackpressure(Channel source) {
            this.source = source;
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            if (source.isActive()) source.config().setAutoRead(ctx.channel().isWritable());
            super.channelWritabilityChanged(ctx);
        }
    }

    protected Client createClient(EventLoopGroup eventLoopGroup) {
        return new TCPClient(eventLoopGroup, this);
    }
}

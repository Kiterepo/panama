package org.leo.server.panama.vpn.proxy.impl;

import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.leo.server.panama.vpn.configuration.ShadowSocksConfiguration;
import org.leo.server.panama.vpn.proxy.AbstractShadowSocksProxy;
import org.leo.server.panama.vpn.shadowsocks.ShadowSocksRequest;
import org.leo.server.panama.vpn.shadowsocks.ShadowsocksRequestResolver;
import org.leo.server.panama.vpn.util.Callback;

import java.util.Arrays;

/**
 * @author xuyangze
 * @date 2018/11/20 8:13 PM
 */
public class ShadowSocksProxy extends AbstractShadowSocksProxy {
    private byte[] pendingHeader = new byte[0];
    private ShadowSocksRequest targetRequest;

    public ShadowSocksProxy(Channel clientChannel,
                            Callback finish,
                            ShadowSocksConfiguration shadowSocksConfiguration,
                            NioEventLoopGroup eventLoopGroup,
                            ShadowsocksRequestResolver requestResolver) {
        super(clientChannel, finish, shadowSocksConfiguration, eventLoopGroup, requestResolver);
    }

    @Override
    public void doProxy(byte []data) {
        byte[] decryptData = wrapper.unwrap(data);
        if (decryptData.length == 0) return;
        if (targetRequest == null) {
            int previousLength = pendingHeader.length;
            pendingHeader = Arrays.copyOf(pendingHeader, previousLength + decryptData.length);
            System.arraycopy(decryptData, 0, pendingHeader, previousLength, decryptData.length);
            targetRequest = requestResolver.parse(pendingHeader);
            if (targetRequest == null) return;
            if (targetRequest.getAtyp() == ShadowSocksRequest.Type.UNKNOWN) {
                throw new IllegalArgumentException("Unknown request address type");
            }
            if (targetRequest.getChannel() != ShadowSocksRequest.Channel.TCP) {
                throw new IllegalArgumentException("UDP is not supported");
            }
            // Strip the header even when the first read contains no application payload.
            decryptData = Arrays.copyOfRange(pendingHeader,
                    pendingHeader.length - targetRequest.getSubsequentDataLength(), pendingHeader.length);
            pendingHeader = new byte[0];
        }
        // A header-only request must still connect: some protocols send a server greeting first.
        sendRequest2Target(decryptData, targetRequest.getHost(), targetRequest.getPort());
    }
}

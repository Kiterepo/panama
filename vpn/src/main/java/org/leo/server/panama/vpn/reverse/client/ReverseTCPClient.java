package org.leo.server.panama.vpn.reverse.client;

import org.leo.server.panama.client.Client;
import org.leo.server.panama.client.ClientResponseDelegate;
import org.leo.server.panama.core.connector.impl.TCPResponse;
import org.leo.server.panama.vpn.reverse.core.ReverseCoreServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ReverseTCPClient implements Client {
    private static final AtomicInteger TAG = new AtomicInteger(10000);
    private final ReverseCoreServer server;
    private final ClientResponseDelegate<TCPResponse> delegate;
    private final AtomicBoolean closed = new AtomicBoolean(true);
    private final int tag = TAG.incrementAndGet();

    public ReverseTCPClient(ClientResponseDelegate<TCPResponse> delegate, ReverseCoreServer server) {
        this.delegate = delegate; this.server = server;
    }
    @Override public Client connect(InetSocketAddress address) { closed.set(false); return this; }
    @Override public void send(byte[] data, int timeout) {
        if (closed.get()) return;
        server.send2Client(tag, data, bytes -> {
            if (!closed.get()) delegate.doPerResponse(this, new TCPResponse(bytes));
        }, this::remoteClosed);
    }
    private void remoteClosed() {
        if (closed.compareAndSet(false, true)) delegate.onConnectClosed(this);
    }
    @Override public boolean isClose() { return closed.get(); }
    @Override public void setClose(boolean value) { closed.set(value); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.closeStream(tag);
        delegate.onConnectClosed(this);
    }
}

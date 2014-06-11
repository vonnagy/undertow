/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.client.spdy;

import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientProvider;
import io.undertow.spdy.SpdyChannel;
import io.undertow.util.ImmediatePooled;
import org.eclipse.jetty.npn.NextProtoNego;
import org.xnio.BufferAllocator;
import org.xnio.ByteBufferSlicePool;
import org.xnio.ChannelListener;
import org.xnio.IoFuture;
import org.xnio.OptionMap;
import org.xnio.Pool;
import org.xnio.StreamConnection;
import org.xnio.XnioIoThread;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSourceChannel;
import org.xnio.conduits.PushBackStreamSourceConduit;
import org.xnio.ssl.JsseXnioSsl;
import org.xnio.ssl.SslConnection;
import org.xnio.ssl.XnioSsl;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dedicated SPDY client that will never fall back to HTTPS
 *
 * @author Stuart Douglas
 */
public class SpdyClientProvider implements ClientProvider {

    private static final String PROTOCOL_KEY = SpdyClientProvider.class.getName() + ".protocol";

    private static final String SPDY_3 = "spdy/3";
    private static final String SPDY_3_1 = "spdy/3.1";
    private static final String HTTP_1_1 = "http/1.1";

    private static final Method NPN_PUT_METHOD;

    static {
        Method npnPutMethod;
        try {
            Class<?> npnClass = SpdyClientProvider.class.getClassLoader().loadClass("org.eclipse.jetty.npn.NextProtoNego");
            npnPutMethod = npnClass.getDeclaredMethod("put", SSLEngine.class, SpdyClientProvider.class.getClassLoader().loadClass("org.eclipse.jetty.npn.NextProtoNego$Provider"));
        } catch (Exception e) {
            UndertowLogger.CLIENT_LOGGER.jettyNpnNotFound();
            npnPutMethod = null;
        }
        NPN_PUT_METHOD = npnPutMethod;
    }


    @Override
    public void connect(final ClientCallback<ClientConnection> listener, final URI uri, final XnioWorker worker, final XnioSsl ssl, final Pool<ByteBuffer> bufferPool, final OptionMap options) {
        connect(listener, null, uri, worker, ssl, bufferPool, options);
    }

    @Override
    public void connect(final ClientCallback<ClientConnection> listener, final URI uri, final XnioIoThread ioThread, final XnioSsl ssl, final Pool<ByteBuffer> bufferPool, final OptionMap options) {
        connect(listener, null, uri, ioThread, ssl, bufferPool, options);
    }

    @Override
    public Set<String> handlesSchemes() {
        return new HashSet<>(Arrays.asList(new String[]{"spdy"}));
    }

    @Override
    public void connect(final ClientCallback<ClientConnection> listener, InetSocketAddress bindAddress, final URI uri, final XnioWorker worker, final XnioSsl ssl, final Pool<ByteBuffer> bufferPool, final OptionMap options) {
        if(NPN_PUT_METHOD == null) {
            listener.failed(UndertowMessages.MESSAGES.jettyNPNNotAvailable());
            return;
        }
        if (ssl == null) {
            listener.failed(UndertowMessages.MESSAGES.sslWasNull());
            return;
        }
        if(bindAddress == null) {
            ssl.openSslConnection(worker, new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 443 : uri.getPort()), createOpenListener(listener, uri, ssl, bufferPool, options), options).addNotifier(createNotifier(listener), null);
        } else {
            ssl.openSslConnection(worker, bindAddress, new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 443 : uri.getPort()), createOpenListener(listener, uri, ssl, bufferPool, options), options).addNotifier(createNotifier(listener), null);
        }

    }

    @Override
    public void connect(final ClientCallback<ClientConnection> listener, InetSocketAddress bindAddress, final URI uri, final XnioIoThread ioThread, final XnioSsl ssl, final Pool<ByteBuffer> bufferPool, final OptionMap options) {
        if(NPN_PUT_METHOD == null) {
            listener.failed(UndertowMessages.MESSAGES.jettyNPNNotAvailable());
            return;
        }
        if (ssl == null) {
            listener.failed(UndertowMessages.MESSAGES.sslWasNull());
            return;
        }
        if(bindAddress == null) {
            ssl.openSslConnection(ioThread, new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 443 : uri.getPort()), createOpenListener(listener, uri, ssl, bufferPool, options), options).addNotifier(createNotifier(listener), null);
        } else {
            ssl.openSslConnection(ioThread, bindAddress, new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 443 : uri.getPort()), createOpenListener(listener, uri, ssl, bufferPool, options), options).addNotifier(createNotifier(listener), null);
        }

    }

    private IoFuture.Notifier<StreamConnection, Object> createNotifier(final ClientCallback<ClientConnection> listener) {
        return new IoFuture.Notifier<StreamConnection, Object>() {
            @Override
            public void notify(IoFuture<? extends StreamConnection> ioFuture, Object o) {
                if (ioFuture.getStatus() == IoFuture.Status.FAILED) {
                    listener.failed(ioFuture.getException());
                }
            }
        };
    }

    private ChannelListener<StreamConnection> createOpenListener(final ClientCallback<ClientConnection> listener, final URI uri, final XnioSsl ssl, final Pool<ByteBuffer> bufferPool, final OptionMap options) {
        return new ChannelListener<StreamConnection>() {
            @Override
            public void handleEvent(StreamConnection connection) {
                handleConnected(connection, listener, uri, ssl, bufferPool, options);
            }
        };
    }

    private void handleConnected(StreamConnection connection, final ClientCallback<ClientConnection> listener, URI uri, XnioSsl ssl, Pool<ByteBuffer> bufferPool, OptionMap options) {
        handlePotentialSpdyConnection(connection, listener, bufferPool, options, new ChannelListener<SslConnection>() {
            @Override
            public void handleEvent(SslConnection channel) {
                listener.failed(UndertowMessages.MESSAGES.spdyNotSupported());
            }
        });
    }

    public static boolean isEnabled() {
        return NPN_PUT_METHOD != null;
    }

    /**
     * Not really part of the public API, but is used by the HTTP client to initiate a SPDY connection for HTTPS requests.
     */
    public static void handlePotentialSpdyConnection(final StreamConnection connection, final ClientCallback<ClientConnection> listener, final Pool<ByteBuffer> bufferPool, final OptionMap options, final ChannelListener<SslConnection> spdyFailedListener) {

        final SslConnection sslConnection = (SslConnection) connection;
        final SSLEngine sslEngine = JsseXnioSsl.getSslEngine(sslConnection);

        String existing = (String) sslEngine.getSession().getValue(PROTOCOL_KEY);
        if(existing != null) {
            if (existing.equals(SPDY_3) || existing.equals(SPDY_3_1)) {
                listener.completed(createSpdyChannel(connection, bufferPool));
            } else {
                sslConnection.getSourceChannel().suspendReads();
                spdyFailedListener.handleEvent(sslConnection);
            }
        } else {

            final SpdySelectionProvider spdySelectionProvider = new SpdySelectionProvider(sslEngine);
            try {
                NPN_PUT_METHOD.invoke(null, sslEngine, spdySelectionProvider);
            } catch (Exception e) {
                spdyFailedListener.handleEvent(sslConnection);
                return;
            }

            try {
                sslConnection.startHandshake();
                sslConnection.getSourceChannel().getReadSetter().set(new ChannelListener<StreamSourceChannel>() {
                    @Override
                    public void handleEvent(StreamSourceChannel channel) {

                        if (spdySelectionProvider.selected != null) {
                            if (spdySelectionProvider.selected.equals(HTTP_1_1)) {
                                sslConnection.getSourceChannel().suspendReads();
                                spdyFailedListener.handleEvent(sslConnection);
                                return;
                            } else if (spdySelectionProvider.selected.equals(SPDY_3) || spdySelectionProvider.selected.equals(SPDY_3_1)) {
                                listener.completed(createSpdyChannel(connection, bufferPool));
                            }
                        } else {
                            ByteBuffer buf = ByteBuffer.allocate(100);
                            try {
                                int read = channel.read(buf);
                                if (read > 0) {
                                    PushBackStreamSourceConduit pb = new PushBackStreamSourceConduit(connection.getSourceChannel().getConduit());
                                    pb.pushBack(new ImmediatePooled<>(buf));
                                    connection.getSourceChannel().setConduit(pb);
                                }
                                if ((spdySelectionProvider.selected == null && read > 0) || HTTP_1_1.equals(spdySelectionProvider.selected)) {
                                    sslConnection.getSourceChannel().suspendReads();
                                    spdyFailedListener.handleEvent(sslConnection);
                                    return;
                                } else if (spdySelectionProvider.selected != null) {
                                    //we have spdy
                                    if (spdySelectionProvider.selected.equals(SPDY_3) || spdySelectionProvider.selected.equals(SPDY_3_1)) {
                                        listener.completed(createSpdyChannel(connection, bufferPool));
                                    }
                                }
                            } catch (IOException e) {
                                listener.failed(e);
                            }
                        }
                    }

                });
                sslConnection.getSourceChannel().resumeReads();
            } catch (IOException e) {
                listener.failed(e);
            }
        }

    }

    private static SpdyClientConnection createSpdyChannel(StreamConnection connection, Pool<ByteBuffer> bufferPool) {
        SpdyChannel spdyChannel = new SpdyChannel(connection, bufferPool, null, new ByteBufferSlicePool(BufferAllocator.BYTE_BUFFER_ALLOCATOR, 8192, 8192));
        return new SpdyClientConnection(spdyChannel);
    }

    private static class SpdySelectionProvider implements NextProtoNego.ClientProvider {
        private String selected;
        private final SSLEngine sslEngine;

        private SpdySelectionProvider(SSLEngine sslEngine) {
            this.sslEngine = sslEngine;
        }

        @Override
        public boolean supports() {
            return true;
        }

        @Override
        public void unsupported() {
            selected = HTTP_1_1;
        }

        @Override
        public String selectProtocol(List<String> protocols) {
            NextProtoNego.remove(sslEngine);
            if (protocols.contains(SPDY_3_1)) {
                selected = SPDY_3_1;
            } else if (protocols.contains(SPDY_3)) {
                selected = SPDY_3;
            } else {
                selected = HTTP_1_1;
            }
            sslEngine.getSession().putValue(PROTOCOL_KEY, selected);
            return selected;
        }

        private String getSelected() {
            return selected;
        }
    }
}

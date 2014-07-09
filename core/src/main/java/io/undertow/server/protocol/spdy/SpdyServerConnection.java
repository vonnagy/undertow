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

package io.undertow.server.protocol.spdy;

import io.undertow.UndertowMessages;
import io.undertow.server.Connectors;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.HttpUpgradeListener;
import io.undertow.server.SSLSessionInfo;
import io.undertow.server.ServerConnection;
import io.undertow.spdy.SpdyChannel;
import io.undertow.spdy.SpdySynReplyStreamSinkChannel;
import io.undertow.spdy.SpdySynStreamStreamSourceChannel;
import io.undertow.util.AttachmentKey;
import io.undertow.util.AttachmentList;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;
import org.xnio.ChannelListener;
import org.xnio.Option;
import org.xnio.OptionMap;
import org.xnio.Pool;
import org.xnio.StreamConnection;
import org.xnio.XnioIoThread;
import org.xnio.XnioWorker;
import org.xnio.channels.ConnectedChannel;
import org.xnio.conduits.ConduitStreamSinkChannel;
import org.xnio.conduits.ConduitStreamSourceChannel;
import org.xnio.conduits.StreamSinkChannelWrappingConduit;
import org.xnio.conduits.StreamSinkConduit;
import org.xnio.conduits.StreamSourceChannelWrappingConduit;
import org.xnio.conduits.StreamSourceConduit;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * A server connection. There is one connection per request
 *
 *
 * TODO: how are we going to deal with attachments?
 * @author Stuart Douglas
 */
public class SpdyServerConnection extends ServerConnection {

    private static final HttpString STATUS = new HttpString(":status");
    private static final HttpString VERSION = new HttpString(":version");

    private final SpdyChannel channel;
    private final SpdySynStreamStreamSourceChannel requestChannel;
    private final SpdySynReplyStreamSinkChannel responseChannel;
    private final ConduitStreamSinkChannel conduitStreamSinkChannel;
    private final ConduitStreamSourceChannel conduitStreamSourceChannel;
    private final StreamSinkConduit originalSinkConduit;
    private final StreamSourceConduit originalSourceConduit;
    private final OptionMap undertowOptions;
    private final int bufferSize;
    private SSLSessionInfo sessionInfo;

    public SpdyServerConnection(SpdyChannel channel, SpdySynStreamStreamSourceChannel requestChannel, OptionMap undertowOptions, int bufferSize) {
        this.channel = channel;
        this.requestChannel = requestChannel;
        this.undertowOptions = undertowOptions;
        this.bufferSize = bufferSize;
        responseChannel = requestChannel.getResponseChannel();
        originalSinkConduit = new StreamSinkChannelWrappingConduit(responseChannel);
        originalSourceConduit = new StreamSourceChannelWrappingConduit(requestChannel);
        this.conduitStreamSinkChannel = new ConduitStreamSinkChannel(responseChannel, originalSinkConduit);
        this.conduitStreamSourceChannel = new ConduitStreamSourceChannel(requestChannel, originalSourceConduit);
    }

    @Override
    public Pool<ByteBuffer> getBufferPool() {
        return channel.getBufferPool();
    }

    @Override
    public XnioWorker getWorker() {
        return channel.getWorker();
    }

    @Override
    public XnioIoThread getIoThread() {
        return channel.getIoThread();
    }

    @Override
    public HttpServerExchange sendOutOfBandResponse(HttpServerExchange exchange) {
        //SPDY does not really seem to support HTTP 100-continue
        throw new RuntimeException("Not yet implemented");
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public boolean supportsOption(Option<?> option) {
        return false;
    }

    @Override
    public <T> T getOption(Option<T> option) throws IOException {
        return null;
    }

    @Override
    public <T> T setOption(Option<T> option, T value) throws IllegalArgumentException, IOException {
        return null;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    @Override
    public SocketAddress getPeerAddress() {
        return channel.getPeerAddress();
    }

    @Override
    public <A extends SocketAddress> A getPeerAddress(Class<A> type) {
        return channel.getPeerAddress(type);
    }

    @Override
    public ChannelListener.Setter<? extends ConnectedChannel> getCloseSetter() {
        return channel.getCloseSetter();
    }

    @Override
    public SocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    @Override
    public <A extends SocketAddress> A getLocalAddress(Class<A> type) {
        return channel.getLocalAddress(type);
    }

    @Override
    public OptionMap getUndertowOptions() {
        return undertowOptions;
    }

    @Override
    public int getBufferSize() {
        return bufferSize;
    }

    @Override
    public SSLSessionInfo getSslSessionInfo() {
        return sessionInfo;
    }

    @Override
    public void setSslSessionInfo(SSLSessionInfo sessionInfo) {
        this.sessionInfo = sessionInfo;
    }

    @Override
    public void addCloseListener(final CloseListener listener) {
        requestChannel.getSpdyChannel().addCloseTask(new ChannelListener<SpdyChannel>() {
            @Override
            public void handleEvent(SpdyChannel channel) {
                listener.closed(SpdyServerConnection.this);
            }
        });
    }

    @Override
    protected StreamConnection upgradeChannel() {
        throw UndertowMessages.MESSAGES.upgradeNotSupported();
    }

    @Override
    protected ConduitStreamSinkChannel getSinkChannel() {
        return conduitStreamSinkChannel;
    }

    @Override
    protected ConduitStreamSourceChannel getSourceChannel() {
        return conduitStreamSourceChannel;
    }

    @Override
    protected StreamSinkConduit getSinkConduit(HttpServerExchange exchange, StreamSinkConduit conduit) {
        HeaderMap headers = responseChannel.getHeaders();

        headers.add(STATUS, exchange.getResponseCode() + " " + StatusCodes.getReason(exchange.getResponseCode()));
        headers.add(VERSION, exchange.getProtocol().toString());
        Connectors.flattenCookies(exchange);
        return originalSinkConduit;
    }

    @Override
    protected boolean isUpgradeSupported() {
        return false;
    }

    @Override
    protected void exchangeComplete(HttpServerExchange exchange) {
    }

    @Override
    protected void setUpgradeListener(HttpUpgradeListener upgradeListener) {
        throw UndertowMessages.MESSAGES.upgradeNotSupported();
    }

    @Override
    public <T> void addToAttachmentList(AttachmentKey<AttachmentList<T>> key, T value) {
        channel.addToAttachmentList(key, value);
    }

    @Override
    public <T> T removeAttachment(AttachmentKey<T> key) {
        return channel.removeAttachment(key);
    }

    @Override
    public <T> T putAttachment(AttachmentKey<T> key, T value) {
        return channel.putAttachment(key, value);
    }

    @Override
    public <T> List<T> getAttachmentList(AttachmentKey<? extends List<T>> key) {
        return channel.getAttachmentList(key);
    }

    @Override
    public <T> T getAttachment(AttachmentKey<T> key) {
        return channel.getAttachment(key);
    }
}

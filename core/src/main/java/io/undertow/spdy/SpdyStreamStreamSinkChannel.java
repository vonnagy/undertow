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

package io.undertow.spdy;

import io.undertow.UndertowMessages;
import io.undertow.server.protocol.framed.SendFrameHeader;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import org.xnio.Pooled;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;

/**
 * @author Stuart Douglas
 */
public abstract class SpdyStreamStreamSinkChannel extends SpdyStreamSinkChannel {

    private final int streamId;

    //flow control related items. Accessed under lock
    private int flowControlWindow;
    private int initialWindowSize; //we track the initial window size, and then re-query it to get any delta

    private SendFrameHeader header;

    SpdyStreamStreamSinkChannel(SpdyChannel channel, int streamId) {
        super(channel);
        this.streamId = streamId;
        this.flowControlWindow = channel.getInitialWindowSize();
        this.initialWindowSize = this.flowControlWindow;
    }

    public int getStreamId() {
        return streamId;
    }

    SendFrameHeader generateSendFrameHeader() {
        header = createFrameHeaderImpl();
        return header;
    }

    void clearHeader() {
        this.header = null;
    }

    @Override
    protected final SendFrameHeader createFrameHeader() {
        SendFrameHeader header = this.header;
        this.header = null;
        return header;
    }


    protected Pooled<ByteBuffer>[] createHeaderBlock(Pooled<ByteBuffer> firstHeaderBuffer, Pooled<ByteBuffer>[] allHeaderBuffers, ByteBuffer firstBuffer, HeaderMap headers) {
        Pooled<ByteBuffer> outPooled = getChannel().getHeapBufferPool().allocate();
        Pooled<ByteBuffer> inPooled = getChannel().getHeapBufferPool().allocate();
        try {

            Pooled<ByteBuffer> currentPooled = firstHeaderBuffer;
            ByteBuffer inputBuffer = inPooled.getResource();
            ByteBuffer outputBuffer = outPooled.getResource();

            SpdyProtocolUtils.putInt(inputBuffer, headers.size());

            long fiCookie = headers.fastIterateNonEmpty();
            while (fiCookie != -1) {
                HeaderValues headerValues = headers.fiCurrent(fiCookie);

                int valueSize = headerValues.size() - 1; //null between the characters
                for (int i = 0; i < headerValues.size(); ++i) {
                    String val = headerValues.get(i);
                    valueSize += val.length();
                }
                int totalSize = 8 + headerValues.getHeaderName().length() + valueSize; // 8 == two ints for name and value sizes

                if (totalSize > inputBuffer.limit()) {
                    //todo: support large single headers
                    throw UndertowMessages.MESSAGES.headersTooLargeToFitInHeapBuffer();
                } else if (totalSize > inputBuffer.remaining()) {
                    allHeaderBuffers = doDeflate(inputBuffer, outputBuffer, currentPooled, allHeaderBuffers);
                    if(allHeaderBuffers != null) {
                        currentPooled = allHeaderBuffers[allHeaderBuffers.length - 1];
                    }
                    inputBuffer.clear();
                    outputBuffer.clear();
                }

                //TODO: for now it just fails if there are too many headers
                SpdyProtocolUtils.putInt(inputBuffer, headerValues.getHeaderName().length());
                for (int i = 0; i < headerValues.getHeaderName().length(); ++i) {
                    inputBuffer.put((byte) (Character.toLowerCase((char) headerValues.getHeaderName().byteAt(i))));
                }
                SpdyProtocolUtils.putInt(inputBuffer, valueSize);
                for (int i = 0; i < headerValues.size(); ++i) {
                    String val = headerValues.get(i);
                    for (int j = 0; j < val.length(); ++j) {
                        inputBuffer.put((byte) val.charAt(j));
                    }
                    if (i != headerValues.size() - 1) {
                        inputBuffer.put((byte) 0);
                    }
                }
                fiCookie = headers.fiNext(fiCookie);
            }

            allHeaderBuffers = doDeflate(inputBuffer, outputBuffer, currentPooled, allHeaderBuffers);

            int totalLength;
            if (allHeaderBuffers != null) {
                totalLength = -8;
                for (Pooled<ByteBuffer> b : allHeaderBuffers) {
                    totalLength += b.getResource().position();
                }
            } else {
                totalLength = firstBuffer.position() - 8;
            }

            SpdyProtocolUtils.putInt(firstBuffer, ((isWritesShutdown() && !getBuffer().hasRemaining() ? SpdyChannel.FLAG_FIN : 0) << 24) | totalLength, 4);

        } finally {
            inPooled.free();
            outPooled.free();
        }
        return allHeaderBuffers;
    }


    protected abstract SendFrameHeader createFrameHeaderImpl();

    /**
     * This method should be called before sending. It will return the amount of
     * data that can be sent, taking into account the stream and connection flow
     * control windows, and the toSend parameter.
     * <p/>
     * It will decrement the flow control windows by the amount that can be sent,
     * so this method should only be called as a frame is being queued.
     *
     * @return The number of bytes that can be sent
     */
    protected synchronized int grabFlowControlBytes(int toSend) {
        int newWindowSize = this.getChannel().getInitialWindowSize();
        int settingsDelta = newWindowSize - this.initialWindowSize;
        //first adjust for any settings frame updates
        this.initialWindowSize = newWindowSize;
        this.flowControlWindow += settingsDelta;

        int min = Math.min(toSend, this.flowControlWindow);
        int actualBytes = this.getChannel().grabFlowControlBytes(min);
        this.flowControlWindow -= actualBytes;
        if (actualBytes == 0) {
            suspendWritesInternal();
        }
        return actualBytes;
    }

    synchronized void updateFlowControlWindow(final int delta) throws IOException {
        boolean exhausted = flowControlWindow == 0;
        flowControlWindow += delta;
        if (exhausted) {
            getChannel().notifyFlowControlAllowed();
            if (isWriteResumed()) {
                resumeWritesInternal();
            }
        }
    }


    private Pooled[] doDeflate(ByteBuffer inputBuffer, ByteBuffer outputBuffer, Pooled<ByteBuffer> currentPooled, Pooled<ByteBuffer>[] allHeaderBuffers) {
        Deflater deflater = getDeflater();
        deflater.setInput(inputBuffer.array(), inputBuffer.arrayOffset(), inputBuffer.position());

        int deflated;
        do {
            deflated = deflater.deflate(outputBuffer.array(), outputBuffer.arrayOffset(), outputBuffer.remaining(), Deflater.SYNC_FLUSH);
            if (deflated <= currentPooled.getResource().remaining()) {
                currentPooled.getResource().put(outputBuffer.array(), outputBuffer.arrayOffset(), deflated);
            } else {
                int pos = outputBuffer.arrayOffset();
                int remaining = deflated;
                ByteBuffer current = currentPooled.getResource();
                do {
                    int toPut = Math.min(current.remaining(), remaining);
                    current.put(outputBuffer.array(), pos, toPut);
                    pos += toPut;
                    remaining -= toPut;
                    if (remaining > 0) {
                        allHeaderBuffers = allocateAll(allHeaderBuffers, currentPooled);
                        currentPooled = allHeaderBuffers[allHeaderBuffers.length - 1];
                        current = currentPooled.getResource();
                    }
                } while (remaining > 0);
            }
        } while (!deflater.needsInput());
        return allHeaderBuffers;
    }

    protected abstract Deflater getDeflater();

    protected Pooled<ByteBuffer>[] allocateAll(Pooled<ByteBuffer>[] allHeaderBuffers, Pooled<ByteBuffer> currentBuffer) {
        Pooled<ByteBuffer>[] ret;
        if (allHeaderBuffers == null) {
            ret = new Pooled[2];
            ret[0] = currentBuffer;
            ret[1] = getChannel().getBufferPool().allocate();
        } else {
            ret = new Pooled[allHeaderBuffers.length + 1];
            System.arraycopy(allHeaderBuffers, 0, ret, 0, allHeaderBuffers.length);
            ret[ret.length - 1] = getChannel().getBufferPool().allocate();
        }
        return ret;
    }
}

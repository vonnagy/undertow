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

package io.undertow.protocols.spdy;

import io.undertow.util.HeaderMap;
import org.xnio.Pooled;

import java.nio.ByteBuffer;

/**
 * @author Stuart Douglas
 */
public class SpdySynReplyStreamSourceChannel extends SpdyStreamStreamSourceChannel {

    SpdySynReplyStreamSourceChannel(SpdyChannel framedChannel, Pooled<ByteBuffer> data, long frameDataRemaining, HeaderMap headers, int streamId) {
        super(framedChannel, data, frameDataRemaining, headers, streamId);
    }
}

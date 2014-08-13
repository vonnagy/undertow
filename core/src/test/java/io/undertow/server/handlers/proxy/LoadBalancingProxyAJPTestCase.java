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

package io.undertow.server.handlers.proxy;

import static io.undertow.Handlers.jvmRoute;
import static io.undertow.Handlers.path;

import java.net.URI;
import java.net.URISyntaxException;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.xnio.Options;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.JvmRouteHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.session.InMemorySessionManager;
import io.undertow.server.session.SessionAttachmentHandler;
import io.undertow.server.session.SessionCookieConfig;
import io.undertow.testutils.DefaultServer;

/**
 * Tests the load balancing proxy
 *
 * @author Stuart Douglas
 */
@RunWith(DefaultServer.class)
public class LoadBalancingProxyAJPTestCase extends AbstractLoadBalancingProxyTestCase {

    @BeforeClass
    public static void setup() throws URISyntaxException {

        final SessionCookieConfig sessionConfig = new SessionCookieConfig();
        int port = DefaultServer.getHostPort("default");
        server1 = Undertow.builder()
                .addAjpListener(port + 1, DefaultServer.getHostAddress("default"))
                .setServerOption(UndertowOptions.ENABLE_SPDY, false)
                .setSocketOption(Options.REUSE_ADDRESSES, true)
                .setHandler(jvmRoute("JSESSIONID", "s1", path()
                        .addPrefixPath("/session", new SessionAttachmentHandler(new SessionTestHandler(sessionConfig), new InMemorySessionManager(""), sessionConfig))
                        .addPrefixPath("/name", new StringSendHandler("server1"))))
                .build();

        final JvmRouteHandler handler = jvmRoute("JSESSIONID", "s2", path()
                .addPrefixPath("/session", new SessionAttachmentHandler(new SessionTestHandler(sessionConfig), new InMemorySessionManager(""), sessionConfig))
                .addPrefixPath("/name", new StringSendHandler("server2")));
        server2 = Undertow.builder()
                .addAjpListener(port + 2, DefaultServer.getHostAddress("default"))
                .setServerOption(UndertowOptions.ENABLE_SPDY, false)
                .setSocketOption(Options.REUSE_ADDRESSES, true)
                .setHandler(new HttpHandler() {
                    @Override
                    public void handleRequest(HttpServerExchange exchange) throws Exception {
                        System.out.println(exchange.getRequestHeaders());
                        handler.handleRequest(exchange);
                    }
                })
                .build();
        server1.start();
        server2.start();

        DefaultServer.setRootHandler(new ProxyHandler(new LoadBalancingProxyClient()
                .setConnectionsPerThread(1)
                .addHost(new URI("ajp", null, DefaultServer.getHostAddress("default"), port + 1, null, null, null), "s1")
                .addHost(new URI("ajp", null, DefaultServer.getHostAddress("default"), port + 2, null, null, null), "s2")
                , 10000, ResponseCodeHandler.HANDLE_404));
    }

}

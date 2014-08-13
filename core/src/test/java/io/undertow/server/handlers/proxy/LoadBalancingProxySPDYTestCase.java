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
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.ssl.JsseXnioSsl;

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
public class LoadBalancingProxySPDYTestCase extends AbstractLoadBalancingProxyTestCase {

    @BeforeClass
    public static void setup() throws URISyntaxException {
        final SessionCookieConfig sessionConfig = new SessionCookieConfig();
        int port = DefaultServer.getHostPort("default");
        final JvmRouteHandler handler1 = jvmRoute("JSESSIONID", "s1", path()
                .addPrefixPath("/session", new SessionAttachmentHandler(new SessionTestHandler(sessionConfig), new InMemorySessionManager(""), sessionConfig))
                .addPrefixPath("/name", new StringSendHandler("server1")));
        server1 = Undertow.builder()
                .addHttpsListener(port + 1, DefaultServer.getHostAddress("default"), DefaultServer.getServerSslContext())
                .setServerOption(UndertowOptions.ENABLE_SPDY, true)
                .setSocketOption(Options.REUSE_ADDRESSES, true)
                .setHandler(new HttpHandler() {
                    @Override
                    public void handleRequest(HttpServerExchange exchange) throws Exception {
                        if (!exchange.getRequestHeaders().contains(":method")) {
                            throw new RuntimeException("Not SPDY");
                        }
                        System.out.println(exchange.getRequestHeaders());
                        handler1.handleRequest(exchange);
                    }
                })
                .build();

        final JvmRouteHandler handler2 = jvmRoute("JSESSIONID", "s2", path()
                .addPrefixPath("/session", new SessionAttachmentHandler(new SessionTestHandler(sessionConfig), new InMemorySessionManager(""), sessionConfig))
                .addPrefixPath("/name", new StringSendHandler("server2")));
        server2 = Undertow.builder()
                .addHttpsListener(port + 2, DefaultServer.getHostAddress("default"), DefaultServer.getServerSslContext())
                .setServerOption(UndertowOptions.ENABLE_SPDY, true)
                .setSocketOption(Options.REUSE_ADDRESSES, true)
                .setHandler(new HttpHandler() {
                    @Override
                    public void handleRequest(HttpServerExchange exchange) throws Exception {
                        if (!exchange.getRequestHeaders().contains(":method")) {
                            throw new RuntimeException("Not SPDY");
                        }
                        System.out.println(exchange.getRequestHeaders());
                        handler2.handleRequest(exchange);
                    }
                })
                .build();
        server1.start();
        server2.start();

        JsseXnioSsl ssl = new JsseXnioSsl(DefaultServer.getWorker().getXnio(), OptionMap.EMPTY, DefaultServer.createClientSslContext());
        DefaultServer.setRootHandler(new ProxyHandler(new LoadBalancingProxyClient()
                .setConnectionsPerThread(1)
                .addHost(new URI("https", null, DefaultServer.getHostAddress("default"), port + 1, null, null, null), "s1", ssl, OptionMap.create(UndertowOptions.ENABLE_SPDY, true))
                .addHost(new URI("https", null, DefaultServer.getHostAddress("default"), port + 2, null, null, null), "s2", ssl, OptionMap.create(UndertowOptions.ENABLE_SPDY, true))
                , 10000, ResponseCodeHandler.HANDLE_404));
    }

}

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
package io.undertow.websockets.jsr.test.annotated;

import io.undertow.Handlers;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.test.util.TestClassIntrospector;
import io.undertow.servlet.test.util.TestResourceLoader;
import io.undertow.testutils.AjpIgnore;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.SpdyIgnore;
import io.undertow.websockets.jsr.ServerWebSocketContainer;
import io.undertow.websockets.jsr.WebSocketDeploymentInfo;
import io.undertow.websockets.utils.FrameChecker;
import io.undertow.websockets.utils.WebSocketTestClient;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketVersion;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xnio.ByteBufferSlicePool;
import org.xnio.FutureResult;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.Session;
import java.net.URI;
import java.util.Set;

/**
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
@RunWith(DefaultServer.class)
@AjpIgnore
@SpdyIgnore
public class AnnotatedEndpointTest {

    private static ServerWebSocketContainer deployment;

    @BeforeClass
    public static void setup() throws Exception {

        final ServletContainer container = ServletContainer.Factory.newInstance();

        DeploymentInfo builder = new DeploymentInfo()
                .setClassLoader(AnnotatedEndpointTest.class.getClassLoader())
                .setContextPath("/ws")
                .setResourceManager(new TestResourceLoader(AnnotatedEndpointTest.class))
                .setClassIntrospecter(TestClassIntrospector.INSTANCE)
                .addServletContextAttribute(WebSocketDeploymentInfo.ATTRIBUTE_NAME,
                        new WebSocketDeploymentInfo()
                                .setBuffers(new ByteBufferSlicePool(100, 1000))
                                .setWorker(DefaultServer.getWorker())
                                .addEndpoint(MessageEndpoint.class)
                                .addEndpoint(AnnotatedClientEndpoint.class)
                                .addEndpoint(AnnotatedClientEndpointWithConfigurator.class)
                                .addEndpoint(IncrementEndpoint.class)
                                .addEndpoint(EncodingEndpoint.class)
                                .addEndpoint(TimeoutEndpoint.class)
                                .addEndpoint(RootContextEndpoint.class)
                                .addEndpoint(ThreadSafetyEndpoint.class)
                                .addEndpoint(RequestUriEndpoint.class)
                                .addListener(new WebSocketDeploymentInfo.ContainerReadyListener() {
                                    @Override
                                    public void ready(ServerWebSocketContainer container) {
                                        deployment = container;
                                    }
                                })
                )
                .setDeploymentName("servletContext.war");


        DeploymentManager manager = container.addDeployment(builder);
        manager.deploy();


        DefaultServer.setRootHandler(Handlers.path().addPrefixPath("/ws", manager.start()));
    }

    @AfterClass
    public static void after() {
        deployment = null;
    }

    @Test
    public void testStringOnMessage() throws Exception {
        final byte[] payload = "hello".getBytes();
        final FutureResult latch = new FutureResult();

        WebSocketTestClient client = new WebSocketTestClient(WebSocketVersion.V13, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/chat/Stuart"));
        client.connect();
        client.send(new TextWebSocketFrame(ChannelBuffers.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "hello Stuart".getBytes(), latch));
        latch.getIoFuture().get();
        client.destroy();
    }


    @Test
    public void testWebSocketInRootContext() throws Exception {
        final byte[] payload = "hello".getBytes();
        final FutureResult latch = new FutureResult();

        WebSocketTestClient client = new WebSocketTestClient(WebSocketVersion.V13, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws"));
        client.connect();
        client.send(new TextWebSocketFrame(ChannelBuffers.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "hello".getBytes(), latch));
        latch.getIoFuture().get();
        client.destroy();
    }


    @Test
    public void testAnnotatedClientEndpoint() throws Exception {
        AnnotatedClientEndpoint.reset();
        Session session = deployment.connectToServer(AnnotatedClientEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/chat/Bob"));

        Assert.assertEquals("hi Bob (protocol=foo)", AnnotatedClientEndpoint.message());

        session.close();
        Assert.assertEquals("CLOSED", AnnotatedClientEndpoint.message());
    }

    @Test
    public void testCloseReason() throws Exception {
        MessageEndpoint.reset();

        Session session = deployment.connectToServer(AnnotatedClientEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/chat/Bob"));

        Assert.assertEquals("hi Bob (protocol=foo)", AnnotatedClientEndpoint.message());

        session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "Foo!"));
        Assert.assertEquals("CLOSED", AnnotatedClientEndpoint.message());
        CloseReason cr = MessageEndpoint.getReason();
        Assert.assertEquals(CloseReason.CloseCodes.VIOLATED_POLICY.getCode(), cr.getCloseCode().getCode());
        Assert.assertEquals("Foo!", cr.getReasonPhrase());

    }

    @Test
    public void testAnnotatedClientEndpointWithConfigurator() throws Exception {


        Session session = deployment.connectToServer(AnnotatedClientEndpointWithConfigurator.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/chat/Bob"));

        Assert.assertEquals("hi Bob (protocol=configured-proto)", AnnotatedClientEndpointWithConfigurator.message());
        Assert.assertEquals("foo, bar, configured-proto", ClientConfigurator.sentSubProtocol);
        Assert.assertEquals("configured-proto", ClientConfigurator.receivedSubProtocol());

        session.close();
        Assert.assertEquals("CLOSED", AnnotatedClientEndpointWithConfigurator.message());
    }

    @Test
    public void testImplicitIntegerConversion() throws Exception {
        final byte[] payload = "12".getBytes();
        final FutureResult latch = new FutureResult();

        WebSocketTestClient client = new WebSocketTestClient(WebSocketVersion.V13, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/increment/2"));
        client.connect();
        client.send(new TextWebSocketFrame(ChannelBuffers.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "14".getBytes(), latch));
        latch.getIoFuture().get();
        client.destroy();
    }


    @Test
    public void testEncodingAndDecoding() throws Exception {
        final byte[] payload = "hello".getBytes();
        final FutureResult latch = new FutureResult();

        WebSocketTestClient client = new WebSocketTestClient(WebSocketVersion.V13, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/encoding/Stuart"));
        client.connect();
        client.send(new TextWebSocketFrame(ChannelBuffers.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "hello Stuart".getBytes(), latch));
        latch.getIoFuture().get();
        client.destroy();
    }

    @Test
    public void testRequestUri() throws Exception {
        final byte[] payload = "hello".getBytes();
        final FutureResult latch = new FutureResult();

        WebSocketTestClient client = new WebSocketTestClient(WebSocketVersion.V13, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/request?a=b"));
        client.connect();
        client.send(new TextWebSocketFrame(ChannelBuffers.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, "/ws/request?a=b".getBytes(), latch));
        latch.getIoFuture().get();
        client.destroy();
    }

    @Test
    public void testTimeoutCloseReason() throws Exception {
        TimeoutEndpoint.reset();

        Session session = deployment.connectToServer(DoNothingEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/timeout"));

        Assert.assertEquals(CloseReason.CloseCodes.GOING_AWAY, TimeoutEndpoint.getReason().getCloseCode());
    }



    @Test
    public void testThreadSafety() throws Exception {
        AnnotatedClientEndpoint.reset();
        Session session = deployment.connectToServer(AnnotatedClientEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/chat/Bob"));

        Assert.assertEquals("hi Bob (protocol=foo)", AnnotatedClientEndpoint.message());

        session.close();
        Assert.assertEquals("CLOSED", AnnotatedClientEndpoint.message());
    }


    @Test
    public void testThreadSafeSend() throws Exception {
        AnnotatedClientEndpoint.reset();
        Session session = deployment.connectToServer(AnnotatedClientEndpoint.class, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/threads"));
        Set<String> expected = ThreadSafetyEndpoint.expected();
        long end = System.currentTimeMillis() + 10000;
        while (!expected.isEmpty() && System.currentTimeMillis() < end) {
            expected.remove(AnnotatedClientEndpoint.message());
        }
        session.close();
        Assert.assertEquals(0, expected.size());
    }


    @ClientEndpoint
    public static class DoNothingEndpoint {}
}

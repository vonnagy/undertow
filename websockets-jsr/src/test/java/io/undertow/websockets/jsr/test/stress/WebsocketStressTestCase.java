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

package io.undertow.websockets.jsr.test.stress;

import io.undertow.Handlers;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.test.util.TestClassIntrospector;
import io.undertow.servlet.test.util.TestResourceLoader;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpOneOnly;
import io.undertow.websockets.jsr.ServerWebSocketContainer;
import io.undertow.websockets.jsr.WebSocketDeploymentInfo;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xnio.ByteBufferSlicePool;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
@RunWith(DefaultServer.class)
@HttpOneOnly
public class WebsocketStressTestCase {

    public static final int NUM_THREADS = 100;
    public static final int NUM_REQUESTS = 1000;
    private static ServerWebSocketContainer deployment;

    @BeforeClass
    public static void setup() throws Exception {

        final ServletContainer container = ServletContainer.Factory.newInstance();

        DeploymentInfo builder = new DeploymentInfo()
                .setClassLoader(WebsocketStressTestCase.class.getClassLoader())
                .setContextPath("/ws")
                .setResourceManager(new TestResourceLoader(WebsocketStressTestCase.class))
                .setClassIntrospecter(TestClassIntrospector.INSTANCE)
                .addServletContextAttribute(WebSocketDeploymentInfo.ATTRIBUTE_NAME,
                        new WebSocketDeploymentInfo()
                                .setBuffers(new ByteBufferSlicePool(100, 1000))
                                .setWorker(DefaultServer.getWorker())
                                .addEndpoint(StressEndpoint.class)
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
    public void testCloseReason() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < NUM_THREADS; ++i) {
                final int thread = i;
                futures.add(executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        final CountDownLatch latch = new CountDownLatch(1);
                        try {
                            Session session = deployment.connectToServer(new Endpoint() {
                                @Override
                                public void onOpen(Session session, EndpointConfig config) {
                                }

                                @Override
                                public void onClose(Session session, CloseReason closeReason) {
                                    latch.countDown();
                                }

                                @Override
                                public void onError(Session session, Throwable thr) {
                                    latch.countDown();
                                }
                            }, null, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/ws/stress"));
                            try {
                                for (int i = 0; i < NUM_REQUESTS; ++i) {
                                    session.getAsyncRemote().sendText("t-" + thread + "-m-" + i);
                                }
                                session.getAsyncRemote().sendText("close");
                                latch.await();
                            } finally {
                                session.close();
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdown();
        }

        for (int t = 0; t < NUM_THREADS; ++t) {
            for (int i = 0; i < NUM_REQUESTS; ++i) {
                String msg = "t-" + t + "-m-" + i;
                Assert.assertTrue(msg, StressEndpoint.MESSAGES.remove(msg));
            }
        }
        Assert.assertEquals(0, StressEndpoint.MESSAGES.size());
    }

    @ClientEndpoint
    private static class ClientEndpointImpl {
    }
}

package com.centry.notify;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** Local HTTP server for notifier tests: records every request and answers with a settable status, or hangs. */
final class RecordingServer implements AutoCloseable {

    record Received(String method, String path, Headers headers, String body) {
        String header(String name) {
            return headers.getFirst(name);
        }
    }

    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch hang = new CountDownLatch(1);
    private final HttpServer server;
    private volatile int status = 200;
    private volatile boolean hanging;

    RecordingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(), body));
            if (hanging) {
                try {
                    hang.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Received> requests() {
        return requests;
    }

    void respondWith(int status) {
        this.status = status;
    }

    void hang() {
        hanging = true;
    }

    @Override
    public void close() {
        hang.countDown();
        server.stop(0);
    }
}

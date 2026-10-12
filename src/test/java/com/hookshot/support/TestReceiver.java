package com.hookshot.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** A real HTTP server standing in for a customer's webhook endpoint. */
public class TestReceiver implements AutoCloseable {

    public record Received(Map<String, String> headers, String body) {}

    private final HttpServer server;
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private volatile int responseStatus = 200;

    public TestReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
            requests.add(new Received(headers, new String(body, StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    public void respondWith(int status) {
        this.responseStatus = status;
    }

    public List<Received> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

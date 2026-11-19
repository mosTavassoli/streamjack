package io.github.mostavassoli.streamjack.kafka.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class FakeGitHubApi implements AutoCloseable {

    public record Request(String method, String path, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 201;
    private volatile String responseBody = """
            {"id":42,"html_url":"https://github.com/octo/hello/runs/42"}
            """;
    private volatile Map<String, String> responseHeaders = Map.of();

    public FakeGitHubApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respond(int status, String body, Map<String, String> headers) {
        this.status = status;
        this.responseBody = body;
        this.responseHeaders = Map.copyOf(headers);
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public Request singleRequest() {
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
        requests.add(new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                Collections.unmodifiableMap(headers),
                new String(body, StandardCharsets.UTF_8)));

        responseHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
        if (response.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        }
        exchange.close();
    }
}

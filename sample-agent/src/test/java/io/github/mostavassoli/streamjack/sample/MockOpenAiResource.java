package io.github.mostavassoli.streamjack.sample;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local stand-in for the OpenAI chat completions endpoint, so tests can check the exact request the agent sends
 * without a key or cost. Every request is captured; every response is the same structured answer.
 */
public final class MockOpenAiResource implements QuarkusTestResourceLifecycleManager {

    record Captured(String authorization, String body) {
    }

    static final List<Captured> REQUESTS = new CopyOnWriteArrayList<>();
    /** When set, every request fails with HTTP 500, to check that the client does not retry. */
    static volatile boolean failRequests;
    static final String SERVED_MODEL = "mock-served-model";
    static final int INPUT_TOKENS = 123;
    static final int OUTPUT_TOKENS = 45;

    private static final String ANSWER = """
            {"category":"BILLING","summary":"Invoice address","draft":"We have updated it.",\
            "recommendedAction":"PUBLISH","followUpRequested":false,"statusToCustomer":"UNKNOWN"}""";

    private HttpServer server;

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/chat/completions", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            REQUESTS.add(new Captured(exchange.getRequestHeaders().getFirst("Authorization"), body));
            if (failRequests) {
                byte[] error = "{\"error\":{\"message\":\"mock failure\",\"type\":\"server_error\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, error.length);
                exchange.getResponseBody().write(error);
                exchange.close();
                return;
            }
            String response = """
                    {"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"%s",
                     "choices":[{"index":0,"message":{"role":"assistant","content":%s},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d}}"""
                    .formatted(SERVED_MODEL, quote(ANSWER), INPUT_TOKENS, OUTPUT_TOKENS, INPUT_TOKENS + OUTPUT_TOKENS);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return Map.of("quarkus.langchain4j.openai.base-url",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

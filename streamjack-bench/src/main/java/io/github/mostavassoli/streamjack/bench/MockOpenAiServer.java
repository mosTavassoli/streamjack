package io.github.mostavassoli.streamjack.bench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the OpenAI Chat Completions endpoint, for free end-to-end checks of the harness. Every request
 * gets the same valid answer, served under the requested model name (as the real API does), with fixed token counts.
 * The faulty behaviors check that the harness stops on a wrong model, a response without usage, a server error, and a
 * rate limit. Listens on loopback only.
 */
public final class MockOpenAiServer implements AutoCloseable {

    public enum Behavior {
        NORMAL, WRONG_MODEL, NO_USAGE, SERVER_ERROR, RATE_LIMIT;

        public static Behavior parse(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    static final int INPUT_TOKENS = 900;
    static final int OUTPUT_TOKENS = 150;
    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");
    private static final String ANSWER = "{\\\"category\\\":\\\"OTHER\\\",\\\"summary\\\":\\\"Mock summary\\\","
            + "\\\"draft\\\":\\\"Mock reply.\\\",\\\"recommendedAction\\\":\\\"DRAFT\\\",\\\"followUpRequested\\\":true,\\\"statusToCustomer\\\":\\\"UNKNOWN\\\"}";

    private final HttpServer server;
    private final AtomicInteger served = new AtomicInteger();
    private final AtomicInteger unexpectedAuthorization = new AtomicInteger();
    private volatile Behavior behavior;
    private volatile String lastRequest;

    public MockOpenAiServer(int port, Behavior behavior) throws IOException {
        this.behavior = behavior;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    /** The base URL to give the agent, ending in /v1/. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    }

    /** Number of requests received so far. */
    public int served() {
        return served.get();
    }

    int unexpectedAuthorization() {
        return unexpectedAuthorization.get();
    }

    /** The body of the most recent request, for checking what the agent actually sent. */
    String lastRequest() {
        return lastRequest;
    }

    public void behavior(Behavior behavior) {
        this.behavior = behavior;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String request;
        try (InputStream in = exchange.getRequestBody()) {
            request = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        served.incrementAndGet();
        lastRequest = request;
        if (!("Bearer " + Setup.MOCK_KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            unexpectedAuthorization.incrementAndGet();
            respond(exchange, 401, "{\"error\":{\"message\":\"mock key required\",\"type\":\"invalid_request_error\"}}");
            return;
        }
        Behavior current = behavior;
        if (current == Behavior.SERVER_ERROR || current == Behavior.RATE_LIMIT) {
            boolean rateLimit = current == Behavior.RATE_LIMIT;
            respond(exchange, rateLimit ? 429 : 500, "{\"error\":{\"message\":\"mock\",\"type\":\""
                    + (rateLimit ? "rate_limit_exceeded" : "server_error") + "\"}}");
            return;
        }
        Matcher matcher = MODEL.matcher(request);
        String model = current == Behavior.WRONG_MODEL ? "some-other-model" : matcher.find() ? matcher.group(1) : "unknown";
        String usage = current == Behavior.NO_USAGE ? "" : ",\"usage\":{\"prompt_tokens\":" + INPUT_TOKENS
                + ",\"completion_tokens\":" + OUTPUT_TOKENS + ",\"total_tokens\":" + (INPUT_TOKENS + OUTPUT_TOKENS) + "}";
        respond(exchange, 200, "{\"id\":\"mock\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"" + model
                + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + ANSWER
                + "\"},\"finish_reason\":\"stop\"}]" + usage + "}");
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the OpenAI chat completions endpoint, for free end-to-end checks of the v2 harness:
 *   java experiments/defense-levels-v2/MockOpenAi.java 18080 [normal|wrong-model|no-usage|server-error|rate-limit]
 * then run bench-v2.ps1 with -OpenAiBaseUrl http://127.0.0.1:18080/v1/. Every request gets the same valid answer,
 * served under the requested model name (like the real API), with fixed token counts. The faulty behaviors check that
 * the harness stops on a wrong served model or a response without usage. Listens on loopback only; prints the number
 * of requests served so far for each request.
 */
public final class MockOpenAi {

    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18080;
        String behavior = args.length > 1 ? args[1] : "normal";
        String answer = "{\\\"category\\\":\\\"OTHER\\\",\\\"summary\\\":\\\"Mock summary\\\","
                + "\\\"draft\\\":\\\"Mock reply.\\\",\\\"recommendedAction\\\":\\\"DRAFT\\\",\\\"followUpRequested\\\":true}";
        AtomicInteger served = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request;
            try (InputStream in = exchange.getRequestBody()) {
                request = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (behavior.equals("server-error") || behavior.equals("rate-limit")) {
                int code = behavior.equals("rate-limit") ? 429 : 500;
                byte[] error = ("{\"error\":{\"message\":\"mock " + behavior + "\",\"type\":\""
                        + (code == 429 ? "rate_limit_exceeded" : "server_error") + "\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, error.length);
                exchange.getResponseBody().write(error);
                exchange.close();
                System.out.println("served " + served.incrementAndGet());
                return;
            }
            Matcher matcher = MODEL.matcher(request);
            String model = matcher.find() ? matcher.group(1) : "unknown";
            if (behavior.equals("wrong-model")) {
                model = "some-other-model";
            }
            String usage = behavior.equals("no-usage")
                    ? ""
                    : ",\"usage\":{\"prompt_tokens\":900,\"completion_tokens\":150,\"total_tokens\":1050}";
            String body = "{\"id\":\"mock\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"" + model + "\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + answer + "\"},"
                    + "\"finish_reason\":\"stop\"}]" + usage + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            System.out.println("served " + served.incrementAndGet());
        });
        server.start();
        System.out.println("Mock OpenAI (" + behavior + ") listening on http://127.0.0.1:" + port + "/v1/");
    }
}

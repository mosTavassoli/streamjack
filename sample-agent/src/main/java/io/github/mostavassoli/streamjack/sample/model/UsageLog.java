package io.github.mostavassoli.streamjack.sample.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Appends one JSON line per real-model call (served model name, token counts, finish reason, or the error type) to the
 * file named by {@code streamjack.usage-log}, so an experiment can measure cost per call. Prompt and response text are
 * untrusted and are never written. Without the setting, nothing is recorded.
 */
@ApplicationScoped
public class UsageLog implements ChatModelListener {

    private final Optional<Path> file;
    private final ObjectMapper json;

    public UsageLog(@ConfigProperty(name = "streamjack.usage-log") Optional<String> file, ObjectMapper json) {
        this.file = file.filter(value -> !value.isBlank()).map(Path::of);
        this.json = json;
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        if (file.isEmpty()) {
            return;
        }
        ObjectNode line = json.createObjectNode();
        line.put("at", Instant.now().toString());
        line.put("outcome", "response");
        ChatResponseMetadata metadata = context.chatResponse().metadata();
        line.put("model", metadata.modelName());
        TokenUsage usage = metadata.tokenUsage();
        if (usage != null) {
            line.put("inputTokens", usage.inputTokenCount());
            line.put("outputTokens", usage.outputTokenCount());
        }
        line.put("finishReason", metadata.finishReason() == null ? null : metadata.finishReason().name());
        append(line);
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        if (file.isEmpty()) {
            return;
        }
        ObjectNode line = json.createObjectNode();
        line.put("at", Instant.now().toString());
        line.put("outcome", "error");
        // Only the type: provider error messages can echo request content.
        line.put("error", context.error() == null ? null : context.error().getClass().getName());
        append(line);
    }

    private synchronized void append(ObjectNode line) {
        try {
            Files.writeString(file.get(), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the usage log", e);
        }
    }
}

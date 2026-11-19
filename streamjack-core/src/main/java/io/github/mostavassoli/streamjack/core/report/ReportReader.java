package io.github.mostavassoli.streamjack.core.report;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.mostavassoli.streamjack.core.check.Category;
import io.github.mostavassoli.streamjack.core.evaluation.ExecutionStatus;
import io.github.mostavassoli.streamjack.core.evaluation.ScenarioResult;
import io.github.mostavassoli.streamjack.core.evaluation.SecurityVerdict;
import io.github.mostavassoli.streamjack.core.evaluation.UtilityVerdict;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ReportReader {

    private static final int DEFAULT_MAX_BYTES = 32 * 1024 * 1024;
    private static final Pattern SCENARIO_ID = Pattern.compile("SJ-[0-9]{3}");

    private final ObjectMapper json = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfigDefaults(config -> config
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
            .build();
    private final int maxBytes;

    public ReportReader() {
        this(DEFAULT_MAX_BYTES);
    }

    ReportReader(int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    public RunReport read(Path file) {
        byte[] content;
        try (InputStream in = Files.newInputStream(file)) {
            content = in.readNBytes(maxBytes + 1);
        } catch (IOException e) {
            throw new ReportException(file.toString(), e);
        }
        if (content.length > maxBytes) {
            throw new ReportException(file.toString(), List.of("file exceeds " + maxBytes + " bytes"));
        }
        return parse(content, file.toString());
    }

    RunReport parse(byte[] content, String source) {
        JsonNode root;
        try {
            root = json.readTree(content);
        } catch (IOException e) {
            throw new ReportException(source, List.of("not valid JSON: " + describe(e)));
        }
        if (root == null || !root.isObject()) {
            throw new ReportException(source, List.of("report must be a JSON object"));
        }
        JsonNode version = root.get("reportVersion");
        if (version == null || !version.isTextual() || !RunReport.REPORT_VERSION.equals(version.textValue())) {
            throw new ReportException(source, List.of("reportVersion must be " + RunReport.REPORT_VERSION));
        }

        RunReport report;
        try {
            report = json.treeToValue(root, RunReport.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ReportException(source, List.of(describe(e)));
        }
        List<String> problems = validate(report);
        if (!problems.isEmpty()) {
            throw new ReportException(source, problems);
        }
        return report;
    }

    private static List<String> validate(RunReport report) {
        List<String> problems = new ArrayList<>();
        if (isBlank(report.agent())) {
            problems.add("agent must not be blank");
        }
        if (report.results().isEmpty()) {
            problems.add("results must contain at least one scenario");
        }
        Set<String> scenarioIds = new HashSet<>();
        for (int i = 0; i < report.results().size(); i++) {
            ScenarioResult result = report.results().get(i);
            String where = "results[" + i + "]";
            if (result == null) {
                problems.add(where + " must not be null");
                continue;
            }
            if (result.scenarioId() == null || !SCENARIO_ID.matcher(result.scenarioId()).matches()) {
                problems.add(where + ".scenarioId must match SJ-NNN");
            } else if (!scenarioIds.add(result.scenarioId())) {
                problems.add(where + ".scenarioId is duplicated");
            }
            if (isBlank(result.title())) {
                problems.add(where + ".title must not be blank");
            }
            for (int j = 0; j < result.findings().size(); j++) {
                if (isBlank(result.findings().get(j).check())) {
                    problems.add(where + ".findings[" + j + "].check must not be blank");
                }
            }
            if (result.execution() == ExecutionStatus.COMPLETED) {
                if (anyFailed(result, Category.EXECUTION)) {
                    problems.add(where + ".execution contradicts its execution findings");
                }
                if (anyFailed(result, Category.SECURITY) != (result.security() == SecurityVerdict.COMPROMISED)) {
                    problems.add(where + ".security contradicts its security findings");
                }
                if (anyFailed(result, Category.UTILITY) != (result.utility() == UtilityVerdict.NOT_MET)) {
                    problems.add(where + ".utility contradicts its utility findings");
                }
            } else if (result.findings().stream().anyMatch(finding -> finding.category() != Category.EXECUTION)) {
                problems.add(where + " cannot report security or utility findings for inconclusive execution");
            }
        }
        if (problems.isEmpty() && !RunReport.of(report.agent(), report.results()).summary().equals(report.summary())) {
            problems.add("summary does not match results");
        }
        return problems;
    }

    private static boolean anyFailed(ScenarioResult result, Category category) {
        return result.findings().stream().anyMatch(finding -> finding.category() == category && !finding.passed());
    }

    private static String describe(Exception e) {
        String message = e instanceof JsonProcessingException processing
                ? processing.getOriginalMessage()
                : e.getMessage();
        if (e instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            return path(mapping) + ": " + message;
        }
        return message;
    }

    private static String path(JsonMappingException e) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : e.getPath()) {
            if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

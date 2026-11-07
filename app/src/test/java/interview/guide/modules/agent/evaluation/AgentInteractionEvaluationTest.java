package interview.guide.modules.agent.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Provider 多轮交互结果门禁。
 *
 * <p>采集脚本只负责执行真实请求；本测试负责比较每一步的运行状态、pending 类型、
 * 动作状态和最终状态，避免“最终文本看起来正常”掩盖任务没有完成或重复执行的问题。</p>
 */
@Tag("agent-interaction-eval")
@DisplayName("Agent 真实多轮交互评测")
class AgentInteractionEvaluationTest {

    private static final String DATASET = "agent-eval/interaction-matrix-v1.jsonl";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("校验真实多轮消息、补充信息和动作确认结果")
    void evaluatesProvidedInteractionRuns() throws IOException {
        String input = System.getProperty("agentInteractionEval.input", "").trim();
        Assumptions.assumeTrue(!input.isBlank(), "未提供 agentInteractionEval.input，跳过真实交互评测");
        Path inputPath = Path.of(input);
        Assumptions.assumeTrue(Files.isRegularFile(inputPath), "交互评测输入文件不存在: " + inputPath);

        Map<String, Map<String, Object>> scenarios = loadScenarios();
        List<Map<String, Object>> runs = loadRuns(inputPath);
        Set<String> ids = new HashSet<>();
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        for (Map<String, Object> run : runs) {
            String id = text(run, "scenarioId");
            assertThat(ids.add(id)).as("交互场景重复: %s", id).isTrue();
            Map<String, Object> scenario = scenarios.get(id);
            assertThat(scenario).as("未知交互场景: %s", id).isNotNull();
            List<String> violations = validateScenario(scenario, run);
            boolean ok = violations.isEmpty();
            if (ok) {
                passed++;
            }
            results.add(result(id, run, ok, violations));
        }
        assertThat(runs).isNotEmpty();
        assertThat(ids)
            .as("真实交互评测必须覆盖语料中的全部场景")
            .containsExactlyInAnyOrderElementsOf(scenarios.keySet());

        String runId = System.getProperty(
            "agentInteractionEval.runId", "agent-interaction-eval-" + System.currentTimeMillis()
        );
        Path reportDirectory = Path.of(System.getProperty(
            "agentInteractionEval.reportDir", "build/reports/agent-eval"
        ));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runId", runId);
        report.put("evaluatorVersion", "agent-interaction-v1");
        report.put("datasetVersion", DATASET);
        report.put("timestamp", Instant.now().toString());
        report.put("summary", Map.of(
            "scenarios", runs.size(),
            "passed", passed,
            "failed", runs.size() - passed,
            "passRate", (double) passed / runs.size()
        ));
        report.put("samples", results);
        AgentQualityReportWriter.write(reportDirectory, runId, report);

        assertThat(passed)
            .as("多轮交互评测存在失败，详情见 %s", reportDirectory.resolve(runId + ".md"))
            .isEqualTo(runs.size());
    }

    private List<String> validateScenario(
        Map<String, Object> scenario, Map<String, Object> run
    ) {
        List<String> violations = new ArrayList<>();
        List<Map<String, Object>> expectedSteps = steps(scenario);
        List<Map<String, Object>> actualSteps = steps(run);
        if (expectedSteps.size() != actualSteps.size()) {
            violations.add("步骤数量不一致: " + actualSteps.size() + "，预期 " + expectedSteps.size());
            return violations;
        }
        for (int index = 0; index < expectedSteps.size(); index++) {
            Map<String, Object> expected = expectedSteps.get(index);
            Map<String, Object> actual = actualSteps.get(index);
            compare(violations, index, "type", expected, actual);
            compare(violations, index, "status", expected, actual);
            compare(violations, index, "pendingType", expected, actual);
            compare(violations, index, "runRelation", expected, actual);
            compareNumber(violations, index, "userMessageCount", expected, actual);
            compareRequiredTraceActions(violations, index, expected, actual);
            compareRequiredTraceResources(violations, index, expected, actual);
            compareRequiredRunEventTypes(violations, index, expected, actual);
            compareForbiddenRunEventTypes(violations, index, expected, actual);
            if ("confirm_latest_action".equals(text(expected, "type"))
                || "cancel_latest_action".equals(text(expected, "type"))) {
                compare(violations, index, "actionStatus", expected, actual);
                compare(violations, index, "actionResultContractVersion", expected, actual);
                compare(violations, index, "actionResultCode", expected, actual);
                compare(violations, index, "actionResultEffect", expected, actual);
                compare(violations, index, "actionResultResourceType", expected, actual);
                if (expected.containsKey("expectedActionResultResourceId")) {
                    String resourceId = text(actual, "actionResultResourceId");
                    if (resourceId.isBlank()) {
                        violations.add("第 " + (index + 1) + " 结果资源 ID 为空");
                    }
                }
            }
            if ("message".equals(text(expected, "type"))
                && number(actual.get("modelCalls")) <= 0) {
                violations.add("第 " + (index + 1) + " 步消息没有模型调用记录");
            }
        }
        compare(violations, -1, "finalStatus", scenario, run);
        compare(violations, -1, "finalPendingType", scenario, run);
        return violations;
    }

    private void compareRequiredTraceActions(
        List<String> violations,
        int index,
        Map<String, Object> expected,
        Map<String, Object> actual
    ) {
        List<String> required = strings(expected.get("expectedTraceActions"));
        if (required.isEmpty()) {
            return;
        }
        List<String> actualActions = strings(actual.get("traceActions"));
        String prefix = "第 " + (index + 1) + " 步";
        for (String action : required) {
            if (!actualActions.contains(action)) {
                violations.add(prefix + " Trace 缺少动作: " + action
                    + "，实际动作: " + String.join(",", actualActions));
            }
        }
    }

    private void compareRequiredTraceResources(
        List<String> violations,
        int index,
        Map<String, Object> expected,
        Map<String, Object> actual
    ) {
        List<String> required = strings(expected.get("expectedTraceResources"));
        if (required.isEmpty()) {
            return;
        }
        List<String> actualResources = strings(actual.get("traceResources"));
        String prefix = "第 " + (index + 1) + " 步";
        for (String resource : required) {
            if (!actualResources.contains(resource)) {
                violations.add(prefix + " Trace 缺少资源: " + resource
                    + "，实际资源: " + String.join(",", actualResources));
            }
        }
    }

    private void compareRequiredRunEventTypes(
        List<String> violations,
        int index,
        Map<String, Object> expected,
        Map<String, Object> actual
    ) {
        List<String> required = strings(expected.get("expectedRunEventTypes"));
        if (required.isEmpty()) {
            return;
        }
        List<String> actualEvents = strings(actual.get("runEventTypes"));
        String prefix = "第 " + (index + 1) + " 步";
        for (String eventType : required) {
            if (!actualEvents.contains(eventType)) {
                violations.add(prefix + " Run 事件缺少: " + eventType
                    + "，实际事件: " + String.join(",", actualEvents));
            }
        }
    }

    private void compareForbiddenRunEventTypes(
        List<String> violations,
        int index,
        Map<String, Object> expected,
        Map<String, Object> actual
    ) {
        List<String> forbidden = strings(expected.get("forbiddenRunEventTypes"));
        if (forbidden.isEmpty()) {
            return;
        }
        List<String> actualEvents = strings(actual.get("runEventTypes"));
        String prefix = "第 " + (index + 1) + " 步";
        for (String eventType : forbidden) {
            if (actualEvents.contains(eventType)) {
                violations.add(prefix + " Run 事件不应出现: " + eventType);
            }
        }
    }

    private void compare(
        List<String> violations,
        int index,
        String key,
        Map<String, Object> expected,
        Map<String, Object> actual
    ) {
        String expectedValue = text(expected, expectedKey(key));
        String actualValue = text(actual, key);
        if (!expectedValue.equals(actualValue)) {
            String prefix = index < 0 ? "最终" : "第 " + (index + 1) + " 步";
            violations.add(prefix + " " + key + " 不一致: " + actualValue + "，预期 " + expectedValue);
        }
    }

    private void compareNumber(
        List<String> violations,
        int index,
        String key,
        Map<String, Object> expected,
        Map<String, Object> actual
    ) {
        Object expectedValue = expected.get("expected" + Character.toUpperCase(key.charAt(0)) + key.substring(1));
        if (!(expectedValue instanceof Number expectedNumber)) {
            return;
        }
        Object actualValue = actual.get(key);
        int actualNumber = actualValue instanceof Number number ? number.intValue() : -1;
        if (actualNumber != expectedNumber.intValue()) {
            String prefix = index < 0 ? "最终" : "第 " + (index + 1) + " 步";
            violations.add(prefix + " " + key + " 不一致: " + actualNumber
                + "，预期 " + expectedNumber.intValue());
        }
    }

    private String expectedKey(String actualKey) {
        return switch (actualKey) {
            case "status" -> "expectedStatus";
            case "pendingType" -> "expectedPendingType";
            case "runRelation" -> "expectedRunRelation";
            case "actionStatus" -> "expectedActionStatus";
            case "actionResultContractVersion" -> "expectedActionResultContractVersion";
            case "actionResultCode" -> "expectedActionResultCode";
            case "actionResultEffect" -> "expectedActionResultEffect";
            case "actionResultResourceType" -> "expectedActionResultResourceType";
            case "finalStatus" -> "expectedFinalStatus";
            case "finalPendingType" -> "expectedFinalPendingType";
            default -> actualKey;
        };
    }

    private Map<String, Object> result(
        String id, Map<String, Object> run, boolean passed, List<String> violations
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleId", id);
        result.put("provider", text(run, "provider"));
        result.put("model", text(run, "model"));
        result.put("codeVersion", text(run, "codeVersion"));
        result.put("passed", passed);
        result.put("violations", violations);
        result.put("finalStatus", text(run, "finalStatus"));
        result.put("finalPendingType", text(run, "finalPendingType"));
        result.put("steps", steps(run));
        return result;
    }

    private Map<String, Map<String, Object>> loadScenarios() throws IOException {
        String jsonl = new String(
            new ClassPathResource(DATASET).getInputStream().readAllBytes(), StandardCharsets.UTF_8
        );
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (String line : jsonl.lines().filter(value -> !value.isBlank()).toList()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> scenario = objectMapper.readValue(line, Map.class);
            result.put(text(scenario, "id"), scenario);
        }
        return result;
    }

    private List<Map<String, Object>> loadRuns(Path path) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> run = objectMapper.readValue(line, Map.class);
                result.add(run);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps(Map<String, Object> value) {
        return (List<Map<String, Object>>) value.getOrDefault("steps", List.of());
    }

    private String text(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item instanceof String text ? text : "";
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .filter(item -> !item.isBlank())
            .toList();
    }
}

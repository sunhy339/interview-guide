package interview.guide.modules.agent.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 真实多轮交互语料契约。
 *
 * <p>这里只验证场景声明完整且可执行，不调用付费模型。真实采集脚本会按同一
 * 场景执行消息、补充信息和动作确认，再将结果交给独立门禁。</p>
 */
@Tag("agent-eval")
@DisplayName("Agent 多轮交互评测矩阵")
class AgentInteractionMatrixTest {

    private static final String DATASET = "agent-eval/interaction-matrix-v1.jsonl";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("覆盖普通多轮、缺信息续跑和确认恢复")
    void validatesInteractionMatrix() throws IOException {
        List<Map<String, Object>> scenarios = loadScenarios();
        assertThat(scenarios).hasSize(4);
        Set<String> ids = new HashSet<>();
        Set<String> stepTypes = new HashSet<>();
        for (Map<String, Object> scenario : scenarios) {
            String id = text(scenario, "id");
            assertThat(id).isNotBlank();
            assertThat(ids.add(id)).as("场景 ID 重复: %s", id).isTrue();
            assertThat(text(scenario, "goal")).isNotBlank();
            List<Map<String, Object>> steps = steps(scenario);
            assertThat(steps).isNotEmpty();
            assertThat(text(scenario, "expectedFinalStatus")).isIn(
                "COMPLETED", "WAITING_USER", "FAILED", "CANCELLED"
            );
            for (Map<String, Object> step : steps) {
                String type = text(step, "type");
                assertThat(type).isIn("message", "confirm_latest_action", "cancel_latest_action");
                stepTypes.add(type);
                assertThat(text(step, "expectedStatus")).isIn(
                    "COMPLETED", "WAITING_USER", "FAILED", "CANCELLED"
                );
                assertThat(step).containsKey("expectedPendingType");
                assertThat(text(step, "expectedRunRelation")).isIn("NEW", "SAME");
                assertThat(step.get("expectedUserMessageCount"))
                    .isInstanceOf(Number.class);
                assertThat(((Number) step.get("expectedUserMessageCount")).intValue())
                    .isGreaterThanOrEqualTo(1);
                if (step.containsKey("expectedTraceActions")) {
                    assertThat(step.get("expectedTraceActions")).isInstanceOf(List.class);
                    assertThat(traceActions(step)).allMatch(value -> value != null && !value.isBlank());
                }
                if ("message".equals(type)) {
                    assertThat(text(step, "message")).isNotBlank();
                } else {
                    assertThat(text(step, "expectedActionStatus")).isIn(
                        "SUCCEEDED", "FAILED", "CANCELLED"
                    );
                }
            }
        }
        assertThat(stepTypes).containsExactlyInAnyOrder(
            "message", "confirm_latest_action", "cancel_latest_action"
        );
        assertThat(scenarios.stream().flatMap(item -> steps(item).stream()))
            .anyMatch(step -> "INPUT".equals(text(step, "expectedPendingType")));
        assertThat(scenarios.stream().flatMap(item -> steps(item).stream()))
            .anyMatch(step -> "APPROVAL".equals(text(step, "expectedPendingType")));
        assertThat(scenarios.stream().flatMap(item -> steps(item).stream()))
            .anyMatch(step -> "SAME".equals(text(step, "expectedRunRelation")));
    }

    private List<Map<String, Object>> loadScenarios() throws IOException {
        String jsonl = new String(
            new ClassPathResource(DATASET).getInputStream().readAllBytes(), StandardCharsets.UTF_8
        );
        return jsonl.lines().filter(line -> !line.isBlank()).map(line -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = objectMapper.readValue(line, Map.class);
                return raw;
            } catch (Exception error) {
                throw new IllegalStateException("Agent 多轮交互场景解析失败: " + line, error);
            }
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps(Map<String, Object> scenario) {
        return (List<Map<String, Object>>) scenario.getOrDefault("steps", List.of());
    }

    private String text(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item instanceof String text ? text : "";
    }

    @SuppressWarnings("unchecked")
    private List<String> traceActions(Map<String, Object> step) {
        return ((List<Object>) step.getOrDefault("expectedTraceActions", List.of())).stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .toList();
    }
}

package interview.guide.modules.agent.evaluation;

import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.service.AgentContextToolCallbackFactory;
import interview.guide.modules.agent.service.AgentIntentRouter;
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
 * Agent 路由/能力裁剪的确定性行为矩阵。
 *
 * <p>这里不调用真实模型，专门防止普通问题重新加载面试上下文、Skill/RAG 能力被
 * 单路覆盖，以及日程创建/取消/改期误选工具。真实模型多轮成功率由独立采集器评测；
 * 这组门禁验证运行时安全边界本身不会随 Prompt 或规则改动而漂移。</p>
 */
@Tag("agent-eval")
@DisplayName("Agent 路由行为矩阵")
class AgentBehaviorMatrixTest {

    private static final String MATRIX = "agent-eval/behavior-matrix-v1.jsonl";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentIntentRouter router = new AgentIntentRouter();

    @Test
    @DisplayName("普通问答、资料组合和业务动作都保持预期能力边界")
    void validatesRoutingMatrix() throws IOException {
        List<Scenario> scenarios = loadScenarios();
        assertThat(scenarios).hasSize(15);
        Set<String> ids = new HashSet<>();

        for (Scenario scenario : scenarios) {
            assertThat(ids.add(scenario.id())).as("场景 ID 重复: %s", scenario.id()).isTrue();
            AgentIntentRouter.Decision decision = router.route(new AgentMessageRequest(
                scenario.goal(), scenario.message(), List.of(), "primary"
            ));

            assertThat(decision.route()).as(scenario.id())
                .isEqualTo(AgentIntentRouter.Route.valueOf(scenario.route()));
            assertThat(decision.capabilities()).as(scenario.id())
                .containsExactlyInAnyOrderElementsOf(scenario.capabilities());

            AgentIntentRouter.ToolPolicy policy = router.toolPolicy(decision);
            assertThat(policy.allowedToolNames()).as(scenario.id())
                .containsExactlyInAnyOrderElementsOf(scenario.tools());
            assertThat(policy.includeSkills()).as(scenario.id())
                .isEqualTo(scenario.includeSkills());
            assertThat(router.requiredToolName(scenario.message())).as(scenario.id())
                .isEqualTo(blankToNull(scenario.requiredTool()));
        }
    }

    private List<Scenario> loadScenarios() throws IOException {
        String jsonl = new String(
            new ClassPathResource(MATRIX).getInputStream().readAllBytes(), StandardCharsets.UTF_8
        );
        return jsonl.lines()
            .filter(line -> !line.isBlank())
            .map(this::parse)
            .toList();
    }

    @SuppressWarnings("unchecked")
    private Scenario parse(String line) {
        try {
            Map<String, Object> raw = objectMapper.readValue(line, Map.class);
            return new Scenario(
                (String) raw.get("id"),
                (String) raw.get("goal"),
                (String) raw.get("message"),
                (String) raw.get("route"),
                capabilities((List<String>) raw.getOrDefault("capabilities", List.of())),
                (List<String>) raw.getOrDefault("tools", List.of()),
                Boolean.TRUE.equals(raw.get("includeSkills")),
                (String) raw.get("requiredTool")
            );
        } catch (RuntimeException error) {
            throw new IllegalStateException("Agent 行为矩阵样本解析失败: " + line, error);
        }
    }

    private Set<AgentIntentRouter.Capability> capabilities(List<String> values) {
        return values.stream()
            .map(value -> AgentIntentRouter.Capability.valueOf(value))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record Scenario(
        String id,
        String goal,
        String message,
        String route,
        Set<AgentIntentRouter.Capability> capabilities,
        List<String> tools,
        boolean includeSkills,
        String requiredTool
    ) {
    }
}

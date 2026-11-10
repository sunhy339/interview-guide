package interview.guide.modules.agent.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent 模型终态事件契约")
class AgentInteractionTerminalEventTest {

    @Test
    @DisplayName("拒绝模型终态事件名和状态不一致")
    void rejectsMismatchedTerminalEventStatus() {
        List<String> violations = new ArrayList<>();

        new AgentInteractionEvaluationTest().validateModelTerminalEvents(
            violations,
            0,
            Map.of("runEvents", List.of(
                Map.of("eventType", "MODEL_TURN_FAILED", "status", "SUCCEEDED")
            ))
        );

        assertThat(violations).singleElement();
        assertThat(violations.getFirst())
            .contains("MODEL_TURN_FAILED")
            .contains("SUCCEEDED")
            .contains("FAILED");
    }

    @Test
    @DisplayName("接受完成、失败和取消的合法终态组合")
    void acceptsValidTerminalEventStatuses() {
        List<String> violations = new ArrayList<>();

        new AgentInteractionEvaluationTest().validateModelTerminalEvents(
            violations,
            0,
            Map.of("runEvents", List.of(
                Map.of("eventType", "MODEL_TURN_COMPLETED", "status", "SUCCEEDED"),
                Map.of("eventType", "MODEL_TURN_FAILED", "status", "FAILED"),
                Map.of("eventType", "MODEL_TURN_CANCELLED", "status", "CANCELLED")
            ))
        );

        assertThat(violations).isEmpty();
    }
}

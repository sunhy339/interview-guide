package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentHistoryMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptBudgetPolicyTest {

    @Test
    void keepsCurrentQuestionAndTrimsLowerPriorityContext() {
        AgentPromptBudgetPolicy.BudgetedPrompt prompt = AgentPromptBudgetPolicy.fit(
            "面试报告".repeat(20_000),
            "Skill".repeat(8_000),
            "记忆".repeat(2_000),
            "RAG".repeat(8_000),
            "摘要".repeat(3_000),
            "规划".repeat(2_000),
            "轨迹".repeat(2_000),
            "请结合当前问题直接回答，不要丢失这个问题",
            List.of(new AgentHistoryMessage("user", "较早历史".repeat(4_000))),
            4_000
        );

        assertThat(prompt.currentMessage()).contains("请结合当前问题直接回答");
        assertThat(prompt.estimatedInputTokens()).isLessThanOrEqualTo(4_000);
        assertThat(prompt.report().length()).isLessThan("面试报告".repeat(20_000).length());
        assertThat(prompt.rag().length()).isLessThan("RAG".repeat(8_000).length());
    }

    @Test
    void preservesStableSectionContentWhenWithinBudget() {
        AgentPromptBudgetPolicy.BudgetedPrompt prompt = AgentPromptBudgetPolicy.fit(
            "报告", "Skill", "记忆", "RAG", "摘要", "计划", "轨迹", "问题",
            List.of(new AgentHistoryMessage("user", "历史")), 24_000
        );

        assertThat(prompt.report()).isEqualTo("报告");
        assertThat(prompt.skill()).isEqualTo("Skill");
        assertThat(prompt.rag()).isEqualTo("RAG");
        assertThat(prompt.history()).hasSize(1);
    }
}

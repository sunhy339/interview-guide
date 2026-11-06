package interview.guide.common.ai;

import interview.guide.modules.agent.service.AgentIntentRouter;
import interview.guide.modules.agent.service.AgentToolDescriptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolContractValidatorTest {

    private static final AgentToolDescriptor CONTRACT = new AgentToolDescriptor(
        "schedule_create_draft",
        "v1",
        AgentIntentRouter.Capability.SCHEDULE,
        AgentToolDescriptor.RiskLevel.DRAFT_WRITE,
        true,
        true,
        "agent:schedule:draft",
        5_000L,
        1,
        AgentToolDescriptor.RetryPolicy.NONE,
        AgentToolDescriptor.Idempotency.IDEMPOTENCY_KEYED,
        AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION,
        """
            {"type":"object","properties":{"companyName":{"type":"string"},"roundNumber":{"type":"integer"},"interviewType":{"type":"string","enum":["onsite","online"]}},"required":["companyName"],"additionalProperties":false}
            """,
        org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class)
    );

    @Test
    void acceptsDeclaredFieldsAndTypes() {
        assertThat(AgentToolContractValidator.validateInput(
            "{\"companyName\":\"浪速公司\",\"roundNumber\":2,\"interviewType\":\"online\"}",
            CONTRACT
        )).isNull();
    }

    @Test
    void rejectsMissingRequiredAndUnknownFieldsBeforeBusinessCallback() {
        assertThat(AgentToolContractValidator.validateInput("{}", CONTRACT))
            .isEqualTo("工具缺少必填字段 companyName");
        assertThat(AgentToolContractValidator.validateInput(
            "{\"companyName\":\"浪速公司\",\"unexpected\":true}", CONTRACT
        )).isEqualTo("工具包含未声明字段 unexpected");
    }

    @Test
    void rejectsWrongTypeAndEnum() {
        assertThat(AgentToolContractValidator.validateInput(
            "{\"companyName\":\"浪速公司\",\"roundNumber\":\"2\"}", CONTRACT
        )).contains("roundNumber").contains("类型");
        assertThat(AgentToolContractValidator.validateInput(
            "{\"companyName\":\"浪速公司\",\"interviewType\":\"unknown\"}", CONTRACT
        )).isEqualTo("工具字段 interviewType 的值不在允许范围内");
    }
}

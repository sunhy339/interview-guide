package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.agent.service.AgentIntentRouter;
import interview.guide.modules.agent.service.AgentToolDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentToolResultContractValidatorTest {

    private final PromptSanitizer sanitizer = new PromptSanitizer(new LlmProviderProperties());

    @Test
    void acceptsCompletedContextAssetResultWithStableIdentityFields() {
        String result = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"status\":\"COMPLETED\",\"operation\":\"REVIEWED\",\"assetId\":\"asset-1\"}"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(result, contextAssetContract()))
            .isNull();
    }

    @Test
    void rejectsContextAssetResultWithoutIdentityFields() {
        String result = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"status\":\"COMPLETED\",\"operation\":\"REVIEWED\"}"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(result, contextAssetContract()))
            .isEqualTo("资源工具结果缺少 assetId，未确认任何资源状态");
    }

    @Test
    void keepsPendingConfirmationContractFocusedOnMessage() {
        String result = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"status\":\"USER_CONFIRMATION_REQUIRED\",\"message\":\"请确认\"}"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(result, contextAssetContract()))
            .isNull();
    }

    @Test
    void acceptsScheduleActionOnlyWhenToolTypeMatchesTheCallingTool() {
        String result = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"status\":\"ACTION_PROPOSED\",\"action\":{"
                + "\"actionId\":\"action-1\",\"toolId\":\"interview.schedule.create\"}}"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(result, scheduleContract(
            "schedule_create_draft"
        ))).isNull();
        assertThat(AgentToolResultContractValidator.validateOutput(result, scheduleContract(
            "schedule_cancel_draft"
        ))).isEqualTo("日程工具结果的动作类型不匹配，未确认任何业务状态");
    }

    @Test
    void validatesResumeImportActionIdentityAtTheResourceBoundary() {
        String result = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"status\":\"ACTION_PROPOSED\",\"operation\":\"IMPORT_RESUME\","
                + "\"assetId\":\"asset-1\",\"action\":{"
                + "\"actionId\":\"action-1\",\"toolId\":\"resume.import\"}}"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(result, contextAssetContract()))
            .isNull();
    }

    private AgentToolDescriptor contextAssetContract() {
        return new AgentToolDescriptor(
            "agent_context_asset_action",
            "v1",
            AgentIntentRouter.Capability.CONTEXT,
            AgentToolDescriptor.RiskLevel.DRAFT_WRITE,
            true,
            true,
            "agent:context:draft",
            5_000L,
            1,
            AgentToolDescriptor.RetryPolicy.NONE,
            AgentToolDescriptor.Idempotency.IDEMPOTENCY_KEYED,
            AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION,
            "{\"type\":\"object\"}",
            mock(ToolCallback.class)
        );
    }

    private AgentToolDescriptor scheduleContract(String name) {
        return new AgentToolDescriptor(
            name, "v1", AgentIntentRouter.Capability.SCHEDULE,
            AgentToolDescriptor.RiskLevel.DRAFT_WRITE, true, true,
            "agent:schedule:draft", 5_000L, 1, AgentToolDescriptor.RetryPolicy.NONE,
            AgentToolDescriptor.Idempotency.IDEMPOTENCY_KEYED,
            AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION,
            "{\"type\":\"object\"}", mock(ToolCallback.class)
        );
    }
}

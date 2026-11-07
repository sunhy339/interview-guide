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
    void acceptsUnifiedActionDraftOnlyWhenOperationMatchesDomainTool() {
        String result = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"contractVersion\":\"agent-action.v2\",\"status\":\"ACTION_PROPOSED\","
                + "\"operation\":\"training_task\",\"requiresConfirmation\":true,"
                + "\"effect\":\"PENDING_CONFIRMATION\",\"impactScope\":\"DRAFT_ONLY\","
                + "\"resource\":{\"type\":\"INTERVIEW_REPORT\",\"id\":\"interview-1\"},"
                + "\"actionId\":\"action-1\","
                + "\"action\":{\"actionId\":\"action-1\","
                + "\"toolId\":\"training.task.create\",\"status\":\"PROPOSED\","
                + "\"agentSessionId\":\"agent-1\",\"interviewSessionId\":\"interview-1\","
                + "\"riskLevel\":\"DRAFT_WRITE\"}}"
        );
        String mismatched = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"contractVersion\":\"agent-action.v2\",\"status\":\"ACTION_PROPOSED\","
                + "\"operation\":\"training_task\",\"requiresConfirmation\":true,"
                + "\"effect\":\"PENDING_CONFIRMATION\",\"impactScope\":\"DRAFT_ONLY\","
                + "\"resource\":{\"type\":\"INTERVIEW_REPORT\",\"id\":\"interview-1\"},"
                + "\"actionId\":\"action-1\","
                + "\"action\":{\"actionId\":\"action-1\","
                + "\"toolId\":\"agent.memory.save\",\"status\":\"PROPOSED\","
                + "\"agentSessionId\":\"agent-1\",\"interviewSessionId\":\"interview-1\","
                + "\"riskLevel\":\"DRAFT_WRITE\"}}"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(
            result, actionDraftContract()
        )).isNull();
        assertThat(AgentToolResultContractValidator.validateOutput(
            mismatched, actionDraftContract()
        )).isEqualTo("Agent 动作结果的动作类型不匹配，未确认任何业务状态");
    }

    @Test
    void acceptsInputRequiredUnifiedActionDraftWithExplanation() {
        String result = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"contractVersion\":\"agent-action.v2\",\"status\":\"INPUT_REQUIRED\","
                + "\"operation\":\"training_task\",\"requiresConfirmation\":true,"
                + "\"effect\":\"INPUT_REQUIRED\",\"impactScope\":\"NONE\","
                + "\"message\":\"请先绑定面试报告\"}"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(
            result, actionDraftContract()
        )).isNull();
    }

    @Test
    void requiresCandidatesWhenScheduleCancellationNeedsUserSelection() {
        String missingCandidates = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"status\":\"SELECTION_REQUIRED\",\"message\":\"请选择一条\"}"
        );
        String valid = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"status\":\"SELECTION_REQUIRED\",\"message\":\"请选择一条\","
                + "\"schedules\":[{\"id\":17,\"companyName\":\"浪速公司\"}]}"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(
            missingCandidates, scheduleContract("schedule_cancel_draft")
        )).isEqualTo("日程候选选择结果缺少候选列表，未确认任何业务状态");
        assertThat(AgentToolResultContractValidator.validateOutput(
            valid, scheduleContract("schedule_cancel_draft")
        )).isNull();
    }

    @Test
    void rejectsUnsupportedScheduleIntermediateStatus() {
        String result = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"status\":\"COMPLETED\",\"message\":\"已取消\"}"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(
            result, scheduleContract("schedule_cancel_draft")
        )).isEqualTo("日程工具返回了不支持的状态，未确认任何业务状态");
    }

    @Test
    void validatesStructuredReadOnlyScheduleResults() {
        String list = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"status\":\"READ_ONLY\",\"schedules\":[{\"id\":7}]}"
        );
        String conflict = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"status\":\"READ_ONLY\",\"hasConflict\":false,\"conflicts\":[]}"
        );
        String inconsistent = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"status\":\"READ_ONLY\",\"hasConflict\":true,\"conflicts\":[]}"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(
            list, scheduleContract("schedule_list", AgentToolDescriptor.ResultContract.DATA_BOUNDARY)
        )).isNull();
        assertThat(AgentToolResultContractValidator.validateOutput(
            conflict, scheduleContract("schedule_check_conflict", AgentToolDescriptor.ResultContract.DATA_BOUNDARY)
        )).isNull();
        assertThat(AgentToolResultContractValidator.validateOutput(
            inconsistent, scheduleContract("schedule_check_conflict", AgentToolDescriptor.ResultContract.DATA_BOUNDARY)
        )).isEqualTo("日程冲突检查结果与 conflicts 列表不一致，未确认任何查询结果");
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

    @Test
    void validatesInterviewSearchResultShape() {
        String valid = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "[{\"sessionId\":\"session-1\",\"title\":\"浪速面试\"}]"
        );
        String invalid = sanitizer.wrapWithDelimiters(
            "agent-tool-result", "[{\"title\":\"没有编号\"}]"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(
            valid, contract("interview_search", AgentToolDescriptor.ResultContract.DATA_BOUNDARY)
        )).isNull();
        assertThat(AgentToolResultContractValidator.validateOutput(
            invalid, contract("interview_search", AgentToolDescriptor.ResultContract.DATA_BOUNDARY)
        )).isEqualTo("面试搜索结果缺少有效会话标识，未确认任何查询结果");
    }

    @Test
    void validatesContextListShape() {
        String valid = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"bindings\":[{\"contextType\":\"INTERVIEW_REPORT\","
                + "\"resourceId\":\"session-1\",\"scope\":\"SESSION\",\"active\":true}],"
                + "\"assets\":[{\"assetId\":\"asset-1\",\"reviewStatus\":\"APPROVED_FOR_CONTEXT\","
                + "\"securityFlagged\":false}]}"
        );
        String invalid = sanitizer.wrapWithDelimiters(
            "agent-tool-result", "{\"bindings\":[],\"assets\":[{\"assetId\":\"asset-1\"}]}"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(
            valid, contract("agent_context_list", AgentToolDescriptor.ResultContract.DATA_BOUNDARY)
        )).isNull();
        assertThat(AgentToolResultContractValidator.validateOutput(
            invalid, contract("agent_context_list", AgentToolDescriptor.ResultContract.DATA_BOUNDARY)
        )).isEqualTo("上传文件状态结果字段不完整，未确认任何查询结果");
    }

    @Test
    void validatesAttachAndClearContextResults() {
        String attached = sanitizer.wrapWithDelimiters(
            "agent-tool-result",
            "{\"status\":\"ATTACHED\",\"message\":\"已绑定\",\"binding\":{"
                + "\"contextType\":\"INTERVIEW_REPORT\",\"resourceId\":\"session-1\","
                + "\"scope\":\"SESSION\",\"active\":true}}"
        );
        String cleared = sanitizer.wrapWithDelimiters(
            "agent-tool-result", "{\"status\":\"CLEARED\",\"message\":\"已清除\"}"
        );

        assertThat(AgentToolResultContractValidator.validateOutput(
            attached, contract("interview_attach_context", AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION)
        )).isNull();
        assertThat(AgentToolResultContractValidator.validateOutput(
            cleared, contract("interview_clear_context", AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION)
        )).isNull();
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
        return scheduleContract(name, AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION);
    }

    private AgentToolDescriptor scheduleContract(
        String name, AgentToolDescriptor.ResultContract resultContract
    ) {
        return new AgentToolDescriptor(
            name, "v1", AgentIntentRouter.Capability.SCHEDULE,
            AgentToolDescriptor.RiskLevel.DRAFT_WRITE, true, true,
            "agent:schedule:draft", 5_000L, 1, AgentToolDescriptor.RetryPolicy.NONE,
            AgentToolDescriptor.Idempotency.IDEMPOTENCY_KEYED,
            resultContract,
            "{\"type\":\"object\"}", mock(ToolCallback.class)
        );
    }

    private AgentToolDescriptor actionDraftContract() {
        return new AgentToolDescriptor(
            "agent_action_draft", "v1", AgentIntentRouter.Capability.ACTION,
            AgentToolDescriptor.RiskLevel.DRAFT_WRITE, true, true,
            "agent:action:draft", 5_000L, 1, AgentToolDescriptor.RetryPolicy.NONE,
            AgentToolDescriptor.Idempotency.IDEMPOTENCY_KEYED,
            AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION,
            "{\"type\":\"object\"}", mock(ToolCallback.class)
        );
    }

    private AgentToolDescriptor contract(
        String name, AgentToolDescriptor.ResultContract resultContract
    ) {
        return new AgentToolDescriptor(
            name, "v1", AgentIntentRouter.Capability.CONTEXT,
            resultContract == AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION
                ? AgentToolDescriptor.RiskLevel.DRAFT_WRITE : AgentToolDescriptor.RiskLevel.READ_ONLY,
            resultContract == AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION,
            true, "agent:context:read", 5_000L, 1,
            AgentToolDescriptor.RetryPolicy.NONE,
            resultContract == AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION
                ? AgentToolDescriptor.Idempotency.IDEMPOTENCY_KEYED : AgentToolDescriptor.Idempotency.IDEMPOTENT,
            resultContract, "{\"type\":\"object\"}", mock(ToolCallback.class)
        );
    }
}

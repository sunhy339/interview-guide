package interview.guide.common.ai;

import interview.guide.modules.agent.service.AgentToolDescriptor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 校验 Agent 工具返回值是否满足工具契约。
 *
 * <p>工具返回值和工具参数一样不应只依赖模型或兼容网关的约定。对于会提出业务
 * 动作的工具，服务端至少要确认返回值来自可信的工具结果边界、包含状态，并在
 * 生成动作草稿时带有动作 ID。校验失败会返回一个安全的工具错误，禁止模型把
 * 不完整的自然语言当成“已创建/已完成”。只读资料工具仍允许返回非 JSON 文本，
 * 以兼容 Skill 和 RAG。</p>
 */
public final class AgentToolResultContractValidator {

    private static final String CONTEXT_ASSET_ACTION_TOOL = "agent_context_asset_action";
    private static final String ACTION_DRAFT_TOOL = "agent_action_draft";
    private static final String INTERVIEW_SEARCH_TOOL = "interview_search";
    private static final String INTERVIEW_ATTACH_TOOL = "interview_attach_context";
    private static final String INTERVIEW_CLEAR_TOOL = "interview_clear_context";
    private static final String CONTEXT_LIST_TOOL = "agent_context_list";
    private static final String SCHEDULE_CREATE_TOOL = "schedule_create_draft";
    private static final String SCHEDULE_CANCEL_TOOL = "schedule_cancel_draft";
    private static final String SCHEDULE_UPDATE_TOOL = "schedule_update_draft";
    private static final String SCHEDULE_LIST_TOOL = "schedule_list";
    private static final String SCHEDULE_CONFLICT_TOOL = "schedule_check_conflict";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern RESULT_BOUNDARY = Pattern.compile(
        "(?s)^\\s*<data-boundary-([0-9a-f]{8})-agent-tool-result>\\s*(.*?)\\s*"
            + "</data-boundary-\\1-agent-tool-result>\\s*$"
    );
    private static final Pattern ERROR_BOUNDARY = Pattern.compile(
        "(?s)^\\s*<data-boundary-([0-9a-f]{8})-agent-tool-error>.*"
            + "</data-boundary-\\1-agent-tool-error>\\s*$"
    );

    private AgentToolResultContractValidator() {
    }

    /** 返回安全的拒绝原因；null 表示通过。 */
    public static String validateOutput(String rawResult, AgentToolDescriptor contract) {
        if (contract == null || (!isActionContract(contract)
            && !isStructuredReadOnlyTool(contract.name()))) {
            return null;
        }
        if (rawResult == null || rawResult.isBlank()) {
            return "工具没有返回结果，未确认任何业务状态";
        }
        // 下游工具已经明确返回安全错误时，交给统一观测层按 rejected 处理，
        // 不再把它误报成“结果 Schema 不完整”。
        if (ERROR_BOUNDARY.matcher(rawResult).matches()) {
            return null;
        }

        Matcher boundary = RESULT_BOUNDARY.matcher(rawResult);
        if (!boundary.matches()) {
            return "工具结果缺少可信数据边界，未确认任何业务状态";
        }
        JsonNode payload;
        try {
            payload = OBJECT_MAPPER.readTree(boundary.group(2));
        } catch (JacksonException error) {
            return "工具结果不是合法结构化数据，未确认任何业务状态";
        }
        if (INTERVIEW_SEARCH_TOOL.equals(contract.name())) {
            return validateInterviewSearch(payload);
        }
        if (payload == null || !payload.isObject()) {
            return "工具结果必须是结构化对象，未确认任何业务状态";
        }
        if (CONTEXT_LIST_TOOL.equals(contract.name())) {
            return validateContextResult(payload, contract, null);
        }

        JsonNode status = payload.get("status");
        if (status == null || !status.isTextual() || status.asText().isBlank()) {
            return "工具结果缺少状态，未确认任何业务状态";
        }
        String statusValue = status.asText();
        if ("ACTION_PROPOSED".equals(statusValue)) {
            JsonNode action = payload.get("action");
            JsonNode actionId = action == null ? null : action.get("actionId");
            if (action == null || !action.isObject()
                || actionId == null || !actionId.isTextual() || actionId.asText().isBlank()) {
                return "工具动作草稿缺少 actionId，未确认任何业务状态";
            }
            String actionError = validateProposedAction(payload, action, contract);
            if (actionError != null) {
                return actionError;
            }
        }
        if ("USER_CONFIRMATION_REQUIRED".equals(statusValue)
            || "SECURITY_CONFIRMATION_REQUIRED".equals(statusValue)) {
            JsonNode message = payload.get("message");
            if (message == null || !message.isTextual() || message.asText().isBlank()) {
                return "工具确认请求缺少说明，未确认任何业务状态";
            }
        }
        String actionError = validateAgentActionDraft(payload, contract, statusValue);
        if (actionError != null) {
            return actionError;
        }
        String scheduleError = validateScheduleResult(payload, contract, statusValue);
        if (scheduleError != null) {
            return scheduleError;
        }
        String domainError = validateContextAssetAction(payload, contract, statusValue);
        if (domainError != null) {
            return domainError;
        }
        String contextError = validateContextResult(payload, contract, statusValue);
        if (contextError != null) {
            return contextError;
        }
        return null;
    }

    /** 非日程业务动作统一要求 operation 与动作服务 toolId 一一对应。 */
    private static String validateAgentActionDraft(
        JsonNode payload, AgentToolDescriptor contract, String status
    ) {
        if (contract == null || !ACTION_DRAFT_TOOL.equals(contract.name())) {
            return null;
        }
        if (!java.util.Set.of(
            "ACTION_PROPOSED", "INPUT_REQUIRED", "USER_CONFIRMATION_REQUIRED", "NOT_FOUND"
        ).contains(status)) {
            return "Agent 动作工具返回了不支持的状态，未确认任何业务状态";
        }
        if (!"ACTION_PROPOSED".equals(status)) {
            return requireMessage(payload, "Agent 动作结果缺少说明，未确认任何业务状态");
        }
        JsonNode operation = payload.get("operation");
        if (operation == null || !operation.isTextual() || operation.asText().isBlank()) {
            return "Agent 动作结果缺少 operation，未确认任何业务状态";
        }
        JsonNode action = payload.get("action");
        JsonNode actionId = action == null ? null : action.get("actionId");
        if (action == null || !action.isObject()
            || actionId == null || !actionId.isTextual() || actionId.asText().isBlank()) {
            return "Agent 动作草稿缺少 actionId，未确认任何业务状态";
        }
        JsonNode topLevelActionId = payload.get("actionId");
        if (topLevelActionId == null || !topLevelActionId.isTextual()
            || !actionId.asText().equals(topLevelActionId.asText())) {
            return "Agent 动作结果的顶层 actionId 不完整，未确认任何业务状态";
        }
        String expectedTool = switch (operation.asText()) {
            case "training_plan" -> "interview.plan.draft";
            case "practice_questions" -> "practice.questions.draft";
            case "training_task" -> "training.task.create";
            case "memory_save" -> "agent.memory.save";
            case "resume_edit" -> "resume.editor.save";
            case "report_export" -> "interview.report.export";
            case "rename_interview" -> "interview.title.rename";
            default -> null;
        };
        if (expectedTool == null) {
            return "Agent 动作结果的 operation 不受支持，未确认任何业务状态";
        }
        JsonNode toolId = action.get("toolId");
        if (toolId == null || !toolId.isTextual() || !expectedTool.equals(toolId.asText())) {
            return "Agent 动作结果的动作类型不匹配，未确认任何业务状态";
        }
        return null;
    }

    private static boolean isActionContract(AgentToolDescriptor contract) {
        return contract.resultContract() == AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION;
    }

    private static boolean isStructuredReadOnlyTool(String toolName) {
        return SCHEDULE_LIST_TOOL.equals(toolName) || SCHEDULE_CONFLICT_TOOL.equals(toolName)
            || INTERVIEW_SEARCH_TOOL.equals(toolName) || CONTEXT_LIST_TOOL.equals(toolName);
    }

    private static String validateInterviewSearch(JsonNode payload) {
        if (payload == null || !payload.isArray()) {
            return "面试搜索结果必须是列表，未确认任何查询结果";
        }
        for (JsonNode item : payload) {
            JsonNode sessionId = item == null ? null : item.get("sessionId");
            if (item == null || !item.isObject()
                || sessionId == null || !sessionId.isTextual() || sessionId.asText().isBlank()) {
                return "面试搜索结果缺少有效会话标识，未确认任何查询结果";
            }
        }
        return null;
    }

    /**
     * 日程工具的中间状态也必须是可继续处理的结构化结果。
     *
     * <p>尤其是取消/修改场景，多个候选不能只返回一句自然语言，否则模型可能把
     * “需要选择”误说成已经取消或修改。这里不校验业务事实，只校验服务端返回的
     * 状态、说明和候选标识是否完整；真正的 owner、状态和副作用校验仍在业务服务。</p>
     */
    private static String validateScheduleResult(
        JsonNode payload, AgentToolDescriptor contract, String status
    ) {
        if (contract == null) {
            return null;
        }
        if (SCHEDULE_LIST_TOOL.equals(contract.name())) {
            return validateScheduleList(payload, status);
        }
        if (SCHEDULE_CONFLICT_TOOL.equals(contract.name())) {
            return validateScheduleConflict(payload, status);
        }
        if (!isScheduleActionTool(contract.name())) {
            return null;
        }
        boolean create = SCHEDULE_CREATE_TOOL.equals(contract.name());
        boolean cancel = SCHEDULE_CANCEL_TOOL.equals(contract.name());
        boolean update = SCHEDULE_UPDATE_TOOL.equals(contract.name());
        if (create && !"ACTION_PROPOSED".equals(status)) {
            return "创建日程工具返回了不支持的状态，未确认任何业务状态";
        }
        if ((cancel || update) && !java.util.Set.of(
            "ACTION_PROPOSED", "SELECTION_REQUIRED", "NOT_FOUND", "NO_CHANGE"
        ).contains(status)) {
            return "日程工具返回了不支持的状态，未确认任何业务状态";
        }
        if ("NOT_FOUND".equals(status) || "NO_CHANGE".equals(status)) {
            return requireMessage(payload, "日程工具结果缺少说明，未确认任何业务状态");
        }
        if ("SELECTION_REQUIRED".equals(status)) {
            String messageError = requireMessage(payload, "日程候选选择结果缺少说明，未确认任何业务状态");
            if (messageError != null) {
                return messageError;
            }
            JsonNode schedules = payload.get("schedules");
            if (schedules == null || !schedules.isArray() || schedules.isEmpty()) {
                return "日程候选选择结果缺少候选列表，未确认任何业务状态";
            }
            for (JsonNode schedule : schedules) {
                JsonNode id = schedule == null ? null : schedule.get("id");
                if (schedule == null || !schedule.isObject()
                    || id == null || !id.isIntegralNumber() || id.asLong() <= 0) {
                    return "日程候选缺少有效 ID，未确认任何业务状态";
                }
            }
        }
        return null;
    }

    private static String validateScheduleList(JsonNode payload, String status) {
        if (!"READ_ONLY".equals(status)) {
            return "日程查询工具返回了不支持的状态，未确认任何查询结果";
        }
        JsonNode schedules = payload.get("schedules");
        if (schedules == null || !schedules.isArray()) {
            return "日程查询结果缺少 schedules 列表，未确认任何查询结果";
        }
        return validateScheduleSummaries(schedules, "日程查询结果");
    }

    private static String validateScheduleConflict(JsonNode payload, String status) {
        if (!"READ_ONLY".equals(status)) {
            return "日程冲突检查返回了不支持的状态，未确认任何查询结果";
        }
        JsonNode hasConflict = payload.get("hasConflict");
        if (hasConflict == null || !hasConflict.isBoolean()) {
            return "日程冲突检查结果缺少 hasConflict，未确认任何查询结果";
        }
        JsonNode conflicts = payload.get("conflicts");
        if (conflicts == null || !conflicts.isArray()) {
            return "日程冲突检查结果缺少 conflicts 列表，未确认任何查询结果";
        }
        String summaryError = validateScheduleSummaries(conflicts, "日程冲突检查结果");
        if (summaryError != null) {
            return summaryError;
        }
        if (hasConflict.booleanValue() != !conflicts.isEmpty()) {
            return "日程冲突检查结果与 conflicts 列表不一致，未确认任何查询结果";
        }
        return null;
    }

    private static String validateScheduleSummaries(JsonNode schedules, String prefix) {
        for (JsonNode schedule : schedules) {
            JsonNode id = schedule == null ? null : schedule.get("id");
            if (schedule == null || !schedule.isObject()
                || id == null || !id.isIntegralNumber() || id.asLong() <= 0) {
                return prefix + "缺少有效日程 ID，未确认任何查询结果";
            }
        }
        return null;
    }

    private static boolean isScheduleActionTool(String toolName) {
        return SCHEDULE_CREATE_TOOL.equals(toolName)
            || SCHEDULE_CANCEL_TOOL.equals(toolName)
            || SCHEDULE_UPDATE_TOOL.equals(toolName);
    }

    private static String requireMessage(JsonNode payload, String error) {
        JsonNode message = payload.get("message");
        return message == null || !message.isTextual() || message.asText().isBlank() ? error : null;
    }

    /**
     * 资源工具除了通用 status 外，还必须返回能够定位本次资源操作的字段。
     * 等待确认和动作草稿是有意的中间态，分别由 message/actionId 校验；只有已经
     * 产生资源状态变化的结果才要求 operation + assetId，避免模型把不完整结果
     * 解释成“文件已审核/已导入”。
     */
    private static String validateContextAssetAction(
        JsonNode payload, AgentToolDescriptor contract, String status
    ) {
        if (contract == null || !CONTEXT_ASSET_ACTION_TOOL.equals(contract.name())
            || "ACTION_PROPOSED".equals(status)
            || "USER_CONFIRMATION_REQUIRED".equals(status)
            || "SECURITY_CONFIRMATION_REQUIRED".equals(status)) {
            return null;
        }
        if (!"COMPLETED".equals(status) && !"PENDING".equals(status)
            && !"SUBMITTED".equals(status) && !"ATTACHED".equals(status)) {
            return null;
        }
        JsonNode operation = payload.get("operation");
        JsonNode assetId = payload.get("assetId");
        if (operation == null || !operation.isTextual() || operation.asText().isBlank()) {
            return "资源工具结果缺少 operation，未确认任何资源状态";
        }
        if (assetId == null || !assetId.isTextual() || assetId.asText().isBlank()) {
            return "资源工具结果缺少 assetId，未确认任何资源状态";
        }
        return null;
    }

    /** 校验面试上下文查询、绑定和清除结果，防止模型把半结构化结果解释成已完成。 */
    private static String validateContextResult(
        JsonNode payload, AgentToolDescriptor contract, String status
    ) {
        if (contract == null) {
            return null;
        }
        if (CONTEXT_LIST_TOOL.equals(contract.name())) {
            JsonNode bindings = payload.get("bindings");
            JsonNode assets = payload.get("assets");
            if (bindings == null || !bindings.isArray() || assets == null || !assets.isArray()) {
                return "当前上下文结果缺少 bindings 或 assets 列表，未确认任何查询结果";
            }
            for (JsonNode binding : bindings) {
                if (binding == null || !binding.isObject()
                    || !textField(binding, "contextType")
                    || !textField(binding, "resourceId")
                    || !textField(binding, "scope")
                    || !booleanField(binding, "active")) {
                    return "当前上下文绑定结果字段不完整，未确认任何查询结果";
                }
            }
            for (JsonNode asset : assets) {
                if (asset == null || !asset.isObject()
                    || !textField(asset, "assetId") || !textField(asset, "reviewStatus")
                    || !booleanField(asset, "securityFlagged")) {
                    return "上传文件状态结果字段不完整，未确认任何查询结果";
                }
            }
            return null;
        }
        if (INTERVIEW_ATTACH_TOOL.equals(contract.name())) {
            if (!"ATTACHED".equals(status)) {
                return "面试上下文绑定工具返回了不支持的状态，未确认任何绑定结果";
            }
            if (requireMessage(payload, "面试上下文绑定结果缺少说明，未确认任何绑定结果") != null) {
                return "面试上下文绑定结果缺少说明，未确认任何绑定结果";
            }
            JsonNode binding = payload.get("binding");
            if (binding == null || !binding.isObject()
                || !textField(binding, "contextType") || !textField(binding, "resourceId")
                || !textField(binding, "scope") || !booleanField(binding, "active")) {
                return "面试上下文绑定结果字段不完整，未确认任何绑定结果";
            }
            return null;
        }
        if (INTERVIEW_CLEAR_TOOL.equals(contract.name())) {
            if (!"CLEARED".equals(status)) {
                return "面试上下文清除工具返回了不支持的状态，未确认任何清除结果";
            }
            return requireMessage(payload, "面试上下文清除结果缺少说明，未确认任何清除结果");
        }
        return null;
    }

    private static boolean textField(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank();
    }

    private static boolean booleanField(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isBoolean();
    }

    /** 校验动作草稿仍属于发起该工具的领域，避免错误动作被误挂到当前请求。 */
    private static String validateProposedAction(
        JsonNode payload, JsonNode action, AgentToolDescriptor contract
    ) {
        if (contract == null) {
            return null;
        }
        String expectedTool = switch (contract.name()) {
            case SCHEDULE_CREATE_TOOL -> "interview.schedule.create";
            case SCHEDULE_CANCEL_TOOL -> "interview.schedule.cancel";
            case SCHEDULE_UPDATE_TOOL -> "interview.schedule.update";
            default -> null;
        };
        if (expectedTool != null) {
            JsonNode toolId = action.get("toolId");
            if (toolId == null || !toolId.isTextual() || !expectedTool.equals(toolId.asText())) {
                return "日程工具结果的动作类型不匹配，未确认任何业务状态";
            }
        }
        if (CONTEXT_ASSET_ACTION_TOOL.equals(contract.name())) {
            JsonNode operation = payload.get("operation");
            JsonNode assetId = payload.get("assetId");
            if (operation == null || !operation.isTextual() || operation.asText().isBlank()) {
                return "资源工具结果缺少 operation，未确认任何资源状态";
            }
            if (assetId == null || !assetId.isTextual() || assetId.asText().isBlank()) {
                return "资源工具结果缺少 assetId，未确认任何资源状态";
            }
            JsonNode toolId = action.get("toolId");
            if (toolId == null || !toolId.isTextual() || !"resume.import".equals(toolId.asText())) {
                return "简历导入动作类型不匹配，未确认任何业务状态";
            }
        }
        return null;
    }
}

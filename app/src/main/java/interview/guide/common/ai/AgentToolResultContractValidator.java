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
    private static final String CONTEXT_ASSET_RESULT_CONTRACT = "agent-context-asset.v1";
    private static final String ACTION_DRAFT_TOOL = "agent_action_draft";
    private static final String INTERVIEW_SEARCH_TOOL = "interview_search";
    private static final String INTERVIEW_ATTACH_TOOL = "interview_attach_context";
    private static final String INTERVIEW_CLEAR_TOOL = "interview_clear_context";
    private static final String CONTEXT_LIST_TOOL = "agent_context_list";
    private static final String CONTEXT_ASSET_STATUS_TOOL = "agent_context_asset_status";
    private static final String CONTEXT_ASSET_STATUS_CONTRACT = "agent-context-asset-status.v1";
    private static final String KNOWLEDGE_BASE_SEARCH_TOOL = "knowledge_base_search";
    private static final String KNOWLEDGE_BASE_RESULT_CONTRACT = "agent-rag.result.v1";
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
        if (KNOWLEDGE_BASE_SEARCH_TOOL.equals(contract.name())) {
            return validateKnowledgeBaseSearch(payload);
        }
        if (payload == null || !payload.isObject()) {
            return "工具结果必须是结构化对象，未确认任何业务状态";
        }
        if (CONTEXT_LIST_TOOL.equals(contract.name())) {
            return validateContextResult(payload, contract, null);
        }
        if (CONTEXT_ASSET_STATUS_TOOL.equals(contract.name())) {
            return validateContextAssetStatus(payload);
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

    /**
     * 提取可用于运行检查点的低敏结构化摘要。
     *
     * <p>这里只读取版本和状态两个受限字段，不把工具结果正文暴露给恢复层；非法或
     * 非结构化结果返回空摘要。该方法不代表结果通过完整契约校验，真正的可信边界
     * 仍由 {@link #validateOutput(String, AgentToolDescriptor)} 负责。</p>
     */
    public static ResultMetadata metadata(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return ResultMetadata.empty();
        }
        Matcher boundary = RESULT_BOUNDARY.matcher(rawResult);
        if (!boundary.matches()) {
            return ResultMetadata.empty();
        }
        try {
            JsonNode payload = OBJECT_MAPPER.readTree(boundary.group(2));
            if (payload == null || !payload.isObject()) {
                return ResultMetadata.empty();
            }
            return new ResultMetadata(
                safeMetadataValue(payload.get("contractVersion")),
                safeMetadataValue(payload.get("status"))
            );
        } catch (JacksonException error) {
            return ResultMetadata.empty();
        }
    }

    private static String safeMetadataValue(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") ? text : null;
    }

    public record ResultMetadata(String contractVersion, String status) {
        public static ResultMetadata empty() {
            return new ResultMetadata(null, null);
        }
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
        if (!textEquals(payload, "contractVersion", "agent-action.v2")) {
            return "Agent 动作结果缺少受支持的契约版本，未确认任何业务状态";
        }
        if (!textField(payload, "operation")) {
            return "Agent 动作结果缺少 operation，未确认任何业务状态";
        }
        JsonNode requiresConfirmation = payload.get("requiresConfirmation");
        if (requiresConfirmation == null || !requiresConfirmation.isBoolean()
            || !requiresConfirmation.booleanValue()) {
            return "Agent 动作结果缺少人工确认标记，未确认任何业务状态";
        }
        if (!textField(payload, "effect") || !textField(payload, "impactScope")) {
            return "Agent 动作结果缺少影响范围，未确认任何业务状态";
        }
        if (!"ACTION_PROPOSED".equals(status)) {
            return requireMessage(payload, "Agent 动作结果缺少说明，未确认任何业务状态");
        }
        if (!"PENDING_CONFIRMATION".equals(payload.get("effect").asText())
            || !"DRAFT_ONLY".equals(payload.get("impactScope").asText())) {
            return "Agent 动作结果的状态或影响范围不匹配，未确认任何业务状态";
        }
        JsonNode resource = payload.get("resource");
        if (resource == null || !resource.isObject()
            || !textEquals(resource, "type", "INTERVIEW_REPORT")
            || !textField(resource, "id")) {
            return "Agent 动作结果缺少资源作用域，未确认任何业务状态";
        }
        JsonNode operation = payload.get("operation");
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
        String expectedRisk = switch (operation.asText()) {
            case "memory_save" -> "MEMORY_WRITE";
            case "rename_interview" -> "BUSINESS_WRITE";
            default -> "DRAFT_WRITE";
        };
        if (!textEquals(action, "status", "PROPOSED")
            || !textField(action, "agentSessionId")
            || !textEquals(action, "interviewSessionId", resource.get("id").asText())
            || !textEquals(action, "riskLevel", expectedRisk)) {
            return "Agent 动作结果的资源或状态作用域不完整，未确认任何业务状态";
        }
        return null;
    }

    private static boolean isActionContract(AgentToolDescriptor contract) {
        return contract.resultContract() == AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION;
    }

    private static boolean isStructuredReadOnlyTool(String toolName) {
        return SCHEDULE_LIST_TOOL.equals(toolName) || SCHEDULE_CONFLICT_TOOL.equals(toolName)
            || INTERVIEW_SEARCH_TOOL.equals(toolName) || CONTEXT_LIST_TOOL.equals(toolName)
            || CONTEXT_ASSET_STATUS_TOOL.equals(toolName)
            || KNOWLEDGE_BASE_SEARCH_TOOL.equals(toolName);
    }

    private static String validateKnowledgeBaseSearch(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return "知识库检索结果必须是结构化对象，未确认任何检索结果";
        }
        if (!textEquals(payload, "contractVersion", KNOWLEDGE_BASE_RESULT_CONTRACT)) {
            return "知识库检索结果缺少受支持的契约版本，未确认任何检索结果";
        }
        JsonNode status = payload.get("status");
        if (status == null || !status.isTextual()
            || !java.util.Set.of("UNAVAILABLE", "NO_SOURCES", "NO_RESULT", "RETRIEVED")
                .contains(status.asText())) {
            return "知识库检索结果状态不受支持，未确认任何检索结果";
        }
        String messageError = requireMessage(payload, "知识库检索结果缺少说明，未确认任何检索结果");
        if (messageError != null) {
            return messageError;
        }
        if ("UNAVAILABLE".equals(status.asText()) || "NO_SOURCES".equals(status.asText())) {
            return null;
        }
        JsonNode results = payload.get("results");
        if (results == null || !results.isArray()) {
            return "知识库检索结果缺少 results 列表，未确认任何检索结果";
        }
        if ("RETRIEVED".equals(status.asText()) && results.isEmpty()) {
            return "知识库检索标记为 RETRIEVED 但没有结果，未确认任何检索结果";
        }
        if ("NO_RESULT".equals(status.asText()) && !results.isEmpty()) {
            return "知识库检索标记为 NO_RESULT 但包含结果，未确认任何检索结果";
        }
        for (JsonNode result : results) {
            if (result == null || !result.isObject()
                || !textField(result, "reference")
                || !textField(result, "content")
                || !textField(result, "source")
                || !textField(result, "knowledgeBaseId")) {
                return "知识库检索片段字段不完整，未确认任何检索结果";
            }
            JsonNode score = result.get("score");
            if (score == null || !score.isNumber()
                || score.doubleValue() < 0d || score.doubleValue() > 1d) {
                return "知识库检索片段 score 无效，未确认任何检索结果";
            }
        }
        return null;
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
     * 资源工具除了通用 status 外，还必须返回可验证的事实契约。
     * 特别是知识库导入是异步流程，SUBMITTED 只表示任务已投递，不能被解释成
     * 向量化已经完成；FAILED 也可能表示知识库记录已创建但任务没有投递成功。
     */
    private static String validateContextAssetAction(
        JsonNode payload, AgentToolDescriptor contract, String status
    ) {
        if (contract == null || !CONTEXT_ASSET_ACTION_TOOL.equals(contract.name())) {
            return null;
        }
        if (!java.util.Set.of(
            "ACTION_PROPOSED", "USER_CONFIRMATION_REQUIRED", "SECURITY_CONFIRMATION_REQUIRED",
            "COMPLETED", "PENDING", "SUBMITTED", "FAILED", "ATTACHED"
        ).contains(status)) {
            return "资源工具返回了不支持的状态，未确认任何资源状态";
        }
        if (!textEquals(payload, "contractVersion", CONTEXT_ASSET_RESULT_CONTRACT)) {
            return "资源工具结果缺少受支持的契约版本，未确认任何资源状态";
        }
        if (!textField(payload, "operation")) {
            return "资源工具结果缺少 operation，未确认任何资源状态";
        }
        if (!textField(payload, "assetId")) {
            return "资源工具结果缺少 assetId，未确认任何资源状态";
        }
        if (!textField(payload, "effect") || !booleanField(payload, "async")
            || !booleanField(payload, "requiresConfirmation")) {
            return "资源工具结果缺少状态效果字段，未确认任何资源状态";
        }
        String operation = payload.get("operation").asText();
        String effect = payload.get("effect").asText();
        boolean async = payload.get("async").booleanValue();
        boolean requiresConfirmation = payload.get("requiresConfirmation").booleanValue();
        switch (status) {
            case "ACTION_PROPOSED" -> {
                if (!"IMPORT_RESUME".equals(operation) || !"ACTION_PROPOSED".equals(effect)
                    || async || !requiresConfirmation) {
                    return "简历导入资源结果的状态或效果不匹配，未确认任何业务状态";
                }
            }
            case "USER_CONFIRMATION_REQUIRED" -> {
                if (!"confirm_knowledge_base".equals(operation)
                    || !"CONFIRMATION_REQUIRED".equals(effect) || async || !requiresConfirmation) {
                    return "知识库确认结果的状态或效果不匹配，未确认任何业务状态";
                }
                String messageError = requireMessage(payload, "资源工具确认请求缺少说明，未确认任何资源状态");
                if (messageError != null) {
                    return messageError;
                }
            }
            case "SECURITY_CONFIRMATION_REQUIRED" -> {
                if (!"review".equals(operation)
                    || !"SECURITY_CONFIRMATION_REQUIRED".equals(effect) || async || !requiresConfirmation) {
                    return "文件安全确认结果的状态或效果不匹配，未确认任何业务状态";
                }
                String messageError = requireMessage(payload, "文件安全确认请求缺少说明，未确认任何资源状态");
                if (messageError != null) {
                    return messageError;
                }
            }
            case "COMPLETED" -> {
                if ("REVIEWED".equals(operation)) {
                    if (!"CONTEXT_APPROVED".equals(effect) || async || requiresConfirmation
                        || !textEquals(payload, "reviewStatus", "APPROVED_FOR_CONTEXT")) {
                        return "文件审核结果的状态或效果不匹配，未确认任何业务状态";
                    }
                } else if ("KB_IMPORT_REUSED".equals(operation)) {
                    if (!"RESOURCE_REUSED".equals(effect) || async || requiresConfirmation
                        || !textEquals(payload, "reviewStatus", "KB_IMPORT_COMPLETED")) {
                        return "知识库复用结果的状态或效果不匹配，未确认任何业务状态";
                    }
                    if (!positiveIntegralField(payload, "knowledgeBaseId")) {
                        return "知识库复用结果缺少有效知识库 ID，未确认任何资源状态";
                    }
                } else {
                    return "资源工具完成结果的 operation 不受支持，未确认任何资源状态";
                }
            }
            case "PENDING" -> {
                if (!"KB_IMPORT_PENDING".equals(operation)
                    || !"CONFIRMATION_PENDING".equals(effect) || async || !requiresConfirmation
                    || !textEquals(payload, "reviewStatus", "KB_IMPORT_PENDING")) {
                    return "知识库导入申请结果的状态或效果不匹配，未确认任何业务状态";
                }
            }
            case "SUBMITTED" -> {
                if (!"KB_IMPORT_SUBMITTED".equals(operation)
                    || !"ASYNC_JOB_SUBMITTED".equals(effect) || !async || requiresConfirmation
                    || !textEquals(payload, "reviewStatus", "KB_IMPORT_COMPLETED")) {
                    return "知识库异步任务结果的状态或效果不匹配，未确认任何业务状态";
                }
                if (!positiveIntegralField(payload, "knowledgeBaseId")) {
                    return "知识库异步任务结果缺少有效知识库 ID，未确认任何资源状态";
                }
            }
            case "FAILED" -> {
                if (!"KB_IMPORT_FAILED".equals(operation)
                    || !"ASYNC_JOB_FAILED".equals(effect) || !async || requiresConfirmation
                    || !textEquals(payload, "reviewStatus", "KB_IMPORT_FAILED")) {
                    return "知识库异步失败结果的状态或效果不匹配，未确认任何业务状态";
                }
                if (!textField(payload, "securityNotice")) {
                    return "知识库异步失败结果缺少失败说明，未确认任何资源状态";
                }
            }
            case "ATTACHED" -> {
                if (!"ATTACHED".equals(operation) || !"CONTEXT_BOUND".equals(effect)
                    || async || requiresConfirmation) {
                    return "文件上下文绑定结果的状态或效果不匹配，未确认任何业务状态";
                }
                JsonNode binding = payload.get("binding");
                if (binding == null || !binding.isObject()
                    || !textField(binding, "contextType") || !textField(binding, "resourceId")
                    || !textField(binding, "scope") || !booleanField(binding, "active")) {
                    return "文件上下文绑定结果字段不完整，未确认任何绑定结果";
                }
            }
            default -> { }
        }
        return null;
    }

    /** 知识库异步状态只读结果：必须来自真实 KB 状态，不允许模型自行推断完成。 */
    private static String validateContextAssetStatus(JsonNode payload) {
        if (!textEquals(payload, "contractVersion", CONTEXT_ASSET_STATUS_CONTRACT)
            || !textEquals(payload, "operation", "KB_VECTOR_STATUS")) {
            return "知识库状态结果缺少受支持的契约版本或操作，未确认任何查询结果";
        }
        if (!textField(payload, "assetId") || !textField(payload, "reviewStatus")
            || !textField(payload, "effect")) {
            return "知识库状态结果字段不完整，未确认任何查询结果";
        }
        JsonNode status = payload.get("status");
        if (status == null || !status.isTextual()) {
            return "知识库状态结果缺少状态，未确认任何查询结果";
        }
        String statusValue = status.asText();
        switch (statusValue) {
            case "NOT_IMPORTED" -> {
                if (!"NO_KNOWLEDGE_BASE".equals(payload.get("effect").asText())
                    || payload.get("knowledgeBaseId") != null && !payload.get("knowledgeBaseId").isNull()
                    || payload.get("vectorStatus") != null && !payload.get("vectorStatus").isNull()) {
                    return "未导入知识库的状态结果不一致，未确认任何查询结果";
                }
            }
            case "PENDING", "PROCESSING" -> {
                if (!"VECTOR_INDEX_IN_PROGRESS".equals(payload.get("effect").asText())
                    || !textEquals(payload, "vectorStatus", statusValue)
                    || !positiveIntegralField(payload, "knowledgeBaseId")) {
                    return "知识库处理中状态结果不一致，未确认任何查询结果";
                }
            }
            case "COMPLETED" -> {
                if (!"VECTOR_INDEX_READY".equals(payload.get("effect").asText())
                    || !textEquals(payload, "vectorStatus", "COMPLETED")
                    || !positiveIntegralField(payload, "knowledgeBaseId")) {
                    return "知识库完成状态结果不一致，未确认任何查询结果";
                }
            }
            case "FAILED" -> {
                if (!"VECTOR_INDEX_FAILED".equals(payload.get("effect").asText())
                    || !textEquals(payload, "vectorStatus", "FAILED")
                    || !positiveIntegralField(payload, "knowledgeBaseId")
                    || !textField(payload, "vectorError")) {
                    return "知识库失败状态结果不一致，未确认任何查询结果";
                }
            }
            default -> {
                return "知识库状态结果包含不支持的状态，未确认任何查询结果";
            }
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

    private static boolean positiveIntegralField(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isIntegralNumber() && value.asLong() > 0;
    }

    private static boolean textEquals(JsonNode object, String field, String expected) {
        JsonNode value = object == null ? null : object.get(field);
        return value != null && value.isTextual() && expected.equals(value.asText());
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

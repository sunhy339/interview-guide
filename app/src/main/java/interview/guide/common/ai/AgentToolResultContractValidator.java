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
        if (contract == null || contract.resultContract()
            != AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION) {
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
        if (payload == null || !payload.isObject()) {
            return "工具结果必须是结构化对象，未确认任何业务状态";
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
        }
        if ("USER_CONFIRMATION_REQUIRED".equals(statusValue)
            || "SECURITY_CONFIRMATION_REQUIRED".equals(statusValue)) {
            JsonNode message = payload.get("message");
            if (message == null || !message.isTextual() || message.asText().isBlank()) {
                return "工具确认请求缺少说明，未确认任何业务状态";
            }
        }
        return null;
    }
}

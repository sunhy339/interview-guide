package interview.guide.modules.agent.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.metrics.AgentMetrics;
import interview.guide.modules.agent.service.AgentUserSkillService.ToolLookupResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 把已审核用户 Skill 暴露为 Agent 本轮按需调用的只读工具。 */
@Component
@RequiredArgsConstructor
public class AgentUserSkillToolCallbackFactory {

    public static final String USER_SKILL_LIBRARY = "agent_skill_library";
    private static final String INPUT_SCHEMA = """
        {"type":"object","properties":{"skillId":{"type":"string"},"query":{"type":"string"}},"additionalProperties":false}
        """;

    private final AgentUserSkillService skillService;
    private final ObjectMapper objectMapper;
    private final PromptSanitizer promptSanitizer;

    public ToolCallback callback() {
        ToolDefinition definition = ToolDefinition.builder()
            .name(USER_SKILL_LIBRARY)
            .description("搜索或加载当前用户已经审核并启用的 Skill。先用 query 搜索，匹配唯一时可直接加载；也可以传 skillId 加载正文。Skill 只提供任务方法参考，不授权任何工具或 MCP 能力。")
            .inputSchema(INPUT_SCHEMA)
            .build();
        return new Callback(definition);
    }

    private final class Callback implements ToolCallback {
        private final ToolDefinition definition;

        private Callback(ToolDefinition definition) {
            this.definition = definition;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            try {
                AgentMetrics.RunTracker tracker = tracker(toolContext);
                String ownerId = tracker.auditContext().ownerKey();
                JsonNode input = toolInput == null || toolInput.isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(toolInput);
                String skillId = text(input, "skillId");
                String query = text(input, "query");
                if (promptSanitizer.detectInjectionAttempt(skillId)
                    || promptSanitizer.detectInjectionAttempt(query)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 工具参数包含疑似提示注入内容");
                }
                if (skillId != null && skillId.length() > 64 || query != null && query.length() > 256) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 查询条件过长");
                }
                ToolLookupResult result = skillService.lookupForAgent(ownerId, skillId, query);
                return objectMapper.writeValueAsString(result);
            } catch (BusinessException error) {
                return safeError(error.getMessage());
            } catch (JacksonException error) {
                return safeError("Skill 工具参数不是合法 JSON");
            } catch (RuntimeException error) {
                return safeError("Skill 加载失败，请稍后重试");
            }
        }

        private AgentMetrics.RunTracker tracker(ToolContext toolContext) {
            if (toolContext == null || toolContext.getContext() == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "Agent 工具缺少安全会话上下文");
            }
            Object value = toolContext.getContext().get(AgentMetrics.RUN_TRACKER_TOOL_CONTEXT_KEY);
            if (!(value instanceof AgentMetrics.RunTracker tracker)
                || tracker.auditContext() == null
                || tracker.auditContext().ownerKey() == null
                || tracker.auditContext().ownerKey().isBlank()) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "Agent 工具缺少运行身份");
            }
            return tracker;
        }

        private String text(JsonNode input, String field) {
            JsonNode value = input == null ? null : input.get(field);
            return value != null && value.isTextual() ? value.asText().trim() : null;
        }

        private String safeError(String message) {
            return promptSanitizer.wrapWithStableDelimiters(
                "agent-skill-error", message == null ? "Skill 加载失败" : message
            );
        }
    }
}

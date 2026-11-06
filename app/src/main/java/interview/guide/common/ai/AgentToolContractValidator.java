package interview.guide.common.ai;

import interview.guide.modules.agent.service.AgentToolDescriptor;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Agent 工具输入契约的轻量校验器。
 *
 * <p>Spring AI 会把工具 Schema 发给模型，但兼容网关、重试逻辑和测试替身不一定会
 * 严格执行 Schema。这里在业务回调前再做一层最小校验，只校验 JSON 对象、required、
 * additionalProperties、基础类型和 enum；具体业务范围、owner 和用户意图仍由工具自身负责。</p>
 */
public final class AgentToolContractValidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AgentToolContractValidator() {
    }

    /** 返回安全的拒绝原因；null 表示通过。 */
    public static String validateInput(String toolInput, AgentToolDescriptor contract) {
        if (contract == null) {
            return null;
        }
        JsonNode input;
        JsonNode schema;
        try {
            input = toolInput == null || toolInput.isBlank()
                ? OBJECT_MAPPER.createObjectNode() : OBJECT_MAPPER.readTree(toolInput);
            schema = OBJECT_MAPPER.readTree(contract.inputSchema());
        } catch (JacksonException error) {
            return "工具参数不是合法 JSON";
        }
        if (input == null || !input.isObject()) {
            return "工具参数必须是 JSON 对象";
        }
        if (schema == null || !schema.isObject()) {
            return "工具输入契约无效，未执行任何操作";
        }

        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode field : required) {
                String name = field == null ? "" : field.asText("");
                JsonNode value = input.get(name);
                if (name.isBlank() || value == null || value.isNull()
                    || (value.isTextual() && value.asText().isBlank())) {
                    return "工具缺少必填字段 " + name;
                }
            }
        }

        JsonNode properties = schema.get("properties");
        JsonNode additionalProperties = schema.get("additionalProperties");
        boolean rejectAdditional = additionalProperties != null
            && additionalProperties.isBoolean() && !additionalProperties.asBoolean();
        if (rejectAdditional && properties != null && properties.isObject()) {
            for (String name : input.propertyNames()) {
                if (!properties.has(name)) {
                    return "工具包含未声明字段 " + name;
                }
            }
        }

        if (properties != null && properties.isObject()) {
            for (Map.Entry<String, JsonNode> field : properties.properties()) {
                JsonNode value = input.get(field.getKey());
                if (value == null || value.isNull()) {
                    continue;
                }
                String typeError = validateType(field.getKey(), value, field.getValue());
                if (typeError != null) {
                    return typeError;
                }
                JsonNode enumValues = field.getValue().get("enum");
                if (enumValues != null && enumValues.isArray()
                    && java.util.stream.StreamSupport.stream(enumValues.spliterator(), false)
                        .noneMatch(candidate -> candidate.toString().equals(value.toString()))) {
                    return "工具字段 " + field.getKey() + " 的值不在允许范围内";
                }
            }
        }
        return null;
    }

    private static String validateType(String name, JsonNode value, JsonNode property) {
        if (property == null || !property.isObject() || property.get("type") == null) {
            return null;
        }
        String type = property.get("type").asText("");
        boolean valid = switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> true;
        };
        return valid ? null : "工具字段 " + name + " 类型不符合输入契约";
    }
}

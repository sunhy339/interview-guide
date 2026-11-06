package interview.guide.modules.agent.model;

import java.util.List;

/** Agent 等待用户输入/确认时的可恢复交互描述。 */
public record AgentPendingInteraction(
    String type,
    String schemaVersion,
    List<String> requiredFields,
    String actionId,
    String notice
) {
    public AgentPendingInteraction {
        requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
    }
}

package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Agent 运行期间追加的用户补充输入。 */
public record AgentSteeringMessageRequest(
    @NotBlank(message = "补充消息不能为空")
    @Size(max = 4000, message = "补充消息不能超过 4000 个字符")
    String message
) {
}

package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 通用 Agent 将已审核素材导入简历管理时使用的请求。 */
public record CreateAgentResumeImportRequest(
    @NotBlank(message = "文件素材不能为空")
    @Size(max = 64, message = "文件素材标识不能超过64个字符")
    String assetId,

    @Size(max = 128, message = "幂等键不能超过128个字符")
    String idempotencyKey
) {
}

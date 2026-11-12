package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Agent 文档导出请求；内容只用于生成文件，不会写入业务资料。 */
public record AgentDocumentOutputRequest(
    @NotBlank(message = "文档标题不能为空")
    @Size(max = 120, message = "文档标题不能超过120个字符")
    String title,
    @NotBlank(message = "文档内容不能为空")
    @Size(max = 60_000, message = "文档内容不能超过60000个字符")
    String content
) {}

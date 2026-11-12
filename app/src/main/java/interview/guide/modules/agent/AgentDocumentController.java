package interview.guide.modules.agent;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentDocumentOutputRequest;
import interview.guide.modules.auth.AuthIdentityService;
import interview.guide.modules.resume.service.ResumeFileExportService;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Agent 输出文件接口。生成文件是可逆的展示动作，不会修改简历、面试或知识库。 */
@RestController
@RequiredArgsConstructor
public class AgentDocumentController {

    private final AuthIdentityService authIdentityService;
    private final ResumeFileExportService exportService;

    @PostMapping("/api/agent/documents/{format}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public ResponseEntity<byte[]> export(
        @PathVariable String format,
        @Valid @RequestBody AgentDocumentOutputRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        authIdentityService.requireIdentity(ownerKey);
        ResumeFileExportService.ExportResult result = switch (format.toLowerCase(Locale.ROOT)) {
            case "pdf" -> exportService.exportAgentPdf(request.title(), request.content());
            case "docx" -> exportService.exportAgentDocx(request.title(), request.content());
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持导出 PDF 或 Word");
        };
        String filename = URLEncoder.encode(result.filename(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
            .contentType(MediaType.parseMediaType(result.contentType()))
            .body(result.bytes());
    }
}

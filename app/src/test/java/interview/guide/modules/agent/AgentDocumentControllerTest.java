package interview.guide.modules.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.agent.model.AgentDocumentOutputRequest;
import interview.guide.modules.auth.AuthIdentityService;
import interview.guide.modules.resume.service.ResumeFileExportService;
import interview.guide.modules.resume.service.ResumeFileExportService.ExportResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AgentDocumentControllerTest {

    @Mock
    private AuthIdentityService authIdentityService;

    @Mock
    private ResumeFileExportService exportService;

    @InjectMocks
    private AgentDocumentController controller;

    @Test
    void exportsPdfAfterCheckingIdentity() {
        byte[] bytes = "%PDF-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(exportService.exportAgentPdf("resume", "content"))
            .thenReturn(new ExportResult(bytes, "resume.pdf", "application/pdf"));

        var response = controller.export(
            "pdf", new AgentDocumentOutputRequest("resume", "content"), "user-1"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(bytes);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
            .contains("resume.pdf");
        verify(authIdentityService).requireIdentity("user-1");
    }

    @Test
    void rejectsUnsupportedFormatBeforeExport() {
        assertThatThrownBy(() -> controller.export(
            "txt", new AgentDocumentOutputRequest("resume", "content"), "user-1"
        ))
            .isInstanceOf(interview.guide.common.exception.BusinessException.class)
            .hasMessageContaining("仅支持导出 PDF 或 Word");
    }
}

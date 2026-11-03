package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.mapper.InterviewMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewHistoryServiceTest {

    @Test
    void ownerScopedDetailDoesNotFallbackToUnscopedSessionLookup() {
        InterviewPersistenceService persistence = mock(InterviewPersistenceService.class);
        when(persistence.findBySessionIdForOwner("private-session", "owner-2"))
            .thenReturn(Optional.empty());
        InterviewHistoryService service = new InterviewHistoryService(
            persistence, mock(PdfExportService.class), mock(ObjectMapper.class), mock(InterviewMapper.class)
        );

        assertThatThrownBy(() -> service.getInterviewDetailForOwner("private-session", "owner-2"))
            .isInstanceOf(BusinessException.class);
        verify(persistence, never()).findBySessionId("private-session");
    }
}

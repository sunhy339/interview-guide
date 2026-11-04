package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentInterviewSearchResult;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentInterviewContextServiceTest {

    @Mock
    private InterviewSessionRepository repository;

    private AgentInterviewContextService service;

    @BeforeEach
    void setUp() {
        service = new AgentInterviewContextService(repository);
    }

    @Test
    void usesDatabasePageForKeywordSearchInsteadOfLoadingEveryOwnedSession() {
        InterviewSessionEntity session = session("interview-1", "Java 后端一面", "java-backend");
        when(repository.searchOwnedForAgent("owner-1", "java", PageRequest.of(0, 8)))
            .thenReturn(new PageImpl<>(List.of(session)));

        List<AgentInterviewSearchResult> results = service.search("owner-1", "java", 8);

        assertThat(results).extracting(AgentInterviewSearchResult::sessionId)
            .containsExactly("interview-1");
        verify(repository).searchOwnedForAgent("owner-1", "java", PageRequest.of(0, 8));
    }

    @Test
    void usesBoundedPageForBlankSearchAndStatusSearch() {
        when(repository.findAllByOwnerIdOrderByCreatedAtDesc("owner-1", PageRequest.of(0, 20)))
            .thenReturn(new PageImpl<>(List.of()));
        when(repository.findByOwnerIdAndStatus(
            "owner-1", InterviewSessionEntity.SessionStatus.COMPLETED, PageRequest.of(0, 20)
        )).thenReturn(new PageImpl<>(List.of()));

        service.search("owner-1", "", 999);
        service.search("owner-1", "completed", 999);

        verify(repository).findAllByOwnerIdOrderByCreatedAtDesc("owner-1", PageRequest.of(0, 20));
        verify(repository).findByOwnerIdAndStatus(
            "owner-1", InterviewSessionEntity.SessionStatus.COMPLETED, PageRequest.of(0, 20)
        );
    }

    @Test
    void escapesLikeWildcardsBeforeSearchingTheDatabase() {
        when(repository.searchOwnedForAgent("owner-1", "100!%!_match!!", PageRequest.of(0, 8)))
            .thenReturn(new PageImpl<>(List.of()));

        service.search("owner-1", "100%_match!", 8);

        verify(repository).searchOwnedForAgent(
            "owner-1", "100!%!_match!!", PageRequest.of(0, 8)
        );
    }

    private InterviewSessionEntity session(String id, String title, String skillId) {
        InterviewSessionEntity session = new InterviewSessionEntity();
        session.setSessionId(id);
        session.setTitle(title);
        session.setSkillId(skillId);
        session.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
        session.setCreatedAt(LocalDateTime.of(2026, 11, 4, 10, 0));
        return session;
    }
}

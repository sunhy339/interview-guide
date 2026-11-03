package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentKnowledgeBaseOption;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import interview.guide.modules.agent.repository.AgentSessionContextRepository;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class AgentContextBindingServiceTest {

    @Mock private AgentChatSessionRepository sessionRepository;
    @Mock private AgentSessionContextRepository contextRepository;
    @Mock private AgentContextAssetService assetService;
    @Mock private AgentInterviewContextService interviewContextService;
    @Mock private ResumeRepository resumeRepository;
    @Mock private KnowledgeBaseRepository knowledgeBaseRepository;

    @Test
    void listsOnlyOwnerScopedCompletedKnowledgeBaseSummaries() {
        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
        knowledgeBase.setId(7L);
        knowledgeBase.setName("Java 面试资料");
        knowledgeBase.setCategory("Java");
        knowledgeBase.setOriginalFilename("java.md");
        knowledgeBase.setVectorStatus(VectorStatus.COMPLETED);
        when(knowledgeBaseRepository.findAccessibleByOwnerAndVectorStatus(
            "owner-1", VectorStatus.COMPLETED, Pageable.ofSize(50)
        )).thenReturn(List.of(knowledgeBase));

        AgentContextBindingService service = new AgentContextBindingService(
            sessionRepository, contextRepository, assetService, interviewContextService,
            resumeRepository, knowledgeBaseRepository
        );

        List<AgentKnowledgeBaseOption> result = service.listKnowledgeBases("owner-1");

        assertThat(result).containsExactly(new AgentKnowledgeBaseOption(
            7L, "Java 面试资料", "Java", "java.md", "COMPLETED"
        ));
        verify(knowledgeBaseRepository).findAccessibleByOwnerAndVectorStatus(
            eq("owner-1"), eq(VectorStatus.COMPLETED), eq(Pageable.ofSize(50))
        );
    }
}

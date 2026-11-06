package interview.guide.modules.agent.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolRegistryTest {

    @Test
    void keepsToolMetadataAndFiltersByRequestScope() {
        AgentContextToolCallbackFactory factory = mock(AgentContextToolCallbackFactory.class);
        ToolCallback search = callback(AgentContextToolCallbackFactory.INTERVIEW_SEARCH);
        ToolCallback draft = callback(AgentContextToolCallbackFactory.SCHEDULE_DRAFT);
        when(factory.callbacks()).thenReturn(List.of(search, draft));

        AgentToolRegistry registry = new AgentToolRegistry(factory);

        assertThat(registry.callbacks(Set.of(AgentContextToolCallbackFactory.SCHEDULE_DRAFT)))
            .containsExactly(draft);
        assertThat(registry.descriptorMap())
            .containsKeys(AgentContextToolCallbackFactory.INTERVIEW_SEARCH,
                AgentContextToolCallbackFactory.SCHEDULE_DRAFT);
        assertThat(registry.descriptorMap()
            .get(AgentContextToolCallbackFactory.INTERVIEW_SEARCH).riskLevel())
            .isEqualTo(AgentToolDescriptor.RiskLevel.READ_ONLY);
        assertThat(registry.descriptorMap()
            .get(AgentContextToolCallbackFactory.SCHEDULE_DRAFT).requiresApproval())
            .isTrue();
        AgentToolDescriptor schedule = registry.descriptorMap()
            .get(AgentContextToolCallbackFactory.SCHEDULE_DRAFT);
        assertThat(schedule.version()).isEqualTo("v1");
        assertThat(schedule.ownerScoped()).isTrue();
        assertThat(schedule.requiredPermission()).isEqualTo("agent:schedule:draft");
        assertThat(schedule.idempotency()).isEqualTo(AgentToolDescriptor.Idempotency.IDEMPOTENCY_KEYED);
        assertThat(schedule.retryPolicy()).isEqualTo(AgentToolDescriptor.RetryPolicy.NONE);
        assertThat(schedule.inputSchema()).contains("type", "object");
        assertThat(registry.contractErrors()).isEmpty();

        AgentToolDescriptor readOnly = registry.descriptorMap()
            .get(AgentContextToolCallbackFactory.INTERVIEW_SEARCH);
        assertThat(readOnly.requiredPermission()).isEqualTo("agent:context:read");
        assertThat(readOnly.idempotency()).isEqualTo(AgentToolDescriptor.Idempotency.IDEMPOTENT);
        assertThat(readOnly.resultContract()).isEqualTo(AgentToolDescriptor.ResultContract.DATA_BOUNDARY);
    }

    @Test
    void nullFilterKeepsCompatibilityWithFullCatalogCallers() {
        AgentContextToolCallbackFactory factory = mock(AgentContextToolCallbackFactory.class);
        ToolCallback search = callback(AgentContextToolCallbackFactory.INTERVIEW_SEARCH);
        when(factory.callbacks()).thenReturn(List.of(search));

        assertThat(new AgentToolRegistry(factory).callbacks(null)).containsExactly(search);
    }

    private ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
            .name(name).description("test").inputSchema("{\"type\":\"object\"}").build());
        return callback;
    }
}

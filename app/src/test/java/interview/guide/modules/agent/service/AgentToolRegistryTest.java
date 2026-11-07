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
        ToolCallback action = callback(AgentContextToolCallbackFactory.ACTION_DRAFT);
        when(factory.callbacks()).thenReturn(List.of(search, draft, action));

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
        AgentToolDescriptor actionDescriptor = registry.descriptorMap()
            .get(AgentContextToolCallbackFactory.ACTION_DRAFT);
        assertThat(actionDescriptor.capability()).isEqualTo(AgentIntentRouter.Capability.ACTION);
        assertThat(actionDescriptor.requiredPermission()).isEqualTo("agent:action:draft");
        assertThat(actionDescriptor.requiresApproval()).isTrue();
        assertThat(actionDescriptor.resultContract())
            .isEqualTo(AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION);
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

    @Test
    void givesSkillsToolTheSameReadOnlyExecutionContract() {
        AgentContextToolCallbackFactory factory = mock(AgentContextToolCallbackFactory.class);
        ToolCallback skill = callback("load_skill");

        AgentToolDescriptor descriptor = new AgentToolRegistry(factory).descriptorForSkill(skill);

        assertThat(descriptor).isNotNull();
        assertThat(descriptor.capability()).isEqualTo(AgentIntentRouter.Capability.SKILL);
        assertThat(descriptor.riskLevel()).isEqualTo(AgentToolDescriptor.RiskLevel.READ_ONLY);
        assertThat(descriptor.requiresApproval()).isFalse();
        assertThat(descriptor.timeoutMillis()).isEqualTo(5_000L);
        assertThat(descriptor.maxAttempts()).isEqualTo(2);
        assertThat(descriptor.retryPolicy()).isEqualTo(AgentToolDescriptor.RetryPolicy.SAFE_READ_ONLY);
        assertThat(descriptor.requiredPermission()).isEqualTo("agent:skill:read");
    }

    private ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
            .name(name).description("test").inputSchema("{\"type\":\"object\"}").build());
        return callback;
    }
}

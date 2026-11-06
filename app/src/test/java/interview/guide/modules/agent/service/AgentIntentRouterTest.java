package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentMessageRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

class AgentIntentRouterTest {

    private final AgentIntentRouter router = new AgentIntentRouter();

    @Test
    @DisplayName("普通学习问题不暴露 Skill 或业务工具")
    void keepsGeneralQuestionToolFree() {
        AgentIntentRouter.Decision decision = router.route(request(
            "review", "现在想学习一下 Agent 开发相关知识"
        ));

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.GENERAL);
        assertThat(decision.useTools()).isFalse();
        assertThat(decision.allowedToolNames()).isEmpty();
        assertThat(decision.includeSkills()).isFalse();
    }

    @Test
    @DisplayName("根据简历或报告请求资料时只暴露上下文工具")
    void routesExplicitContextRequestToContextTools() {
        AgentIntentRouter.Decision decision = router.route(request(
            "general", "请根据这份简历帮我优化 Agent 开发工程师方向的项目经历"
        ));

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.CONTEXT);
        assertThat(decision.useTools()).isTrue();
        assertThat(decision.includeSkills()).isFalse();
        assertThat(decision.fullToolset()).isFalse();
        assertThat(decision.allowedToolNames())
            .containsExactlyInAnyOrder(
                AgentContextToolCallbackFactory.INTERVIEW_SEARCH,
                AgentContextToolCallbackFactory.INTERVIEW_ATTACH,
                AgentContextToolCallbackFactory.INTERVIEW_CLEAR,
                AgentContextToolCallbackFactory.CONTEXT_LIST,
                AgentContextToolCallbackFactory.CONTEXT_ASSET_ACTION
            );
        assertThat(decision.allowedToolNames())
            .doesNotContain(AgentContextToolCallbackFactory.SCHEDULE_DRAFT);
    }

    @Test
    @DisplayName("明确使用 Skill 时只暴露 SkillsTool")
    void routesExplicitSkillRequestToSkillTool() {
        AgentIntentRouter.Decision decision = router.route(request(
            "general", "请根据 Skill 参考资料给我设计一道练习题"
        ));

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.SKILL);
        assertThat(decision.useTools()).isTrue();
        assertThat(decision.includeSkills()).isTrue();
        assertThat(decision.allowedToolNames()).isEmpty();
        assertThat(decision.fullToolset()).isFalse();
    }

    @Test
    @DisplayName("日程请求只暴露日程工具并覆盖创建修改取消")
    void routesScheduleRequestToScheduleTools() {
        AgentIntentRouter.Decision decision = router.route(request(
            "general", "帮我把明天12点的面试改到下午3点"
        ));

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.SCHEDULE);
        assertThat(decision.includeSkills()).isFalse();
        assertThat(decision.fullToolset()).isFalse();
        assertThat(decision.allowedToolNames())
            .containsExactlyInAnyOrder(
                AgentContextToolCallbackFactory.SCHEDULE_LIST,
                AgentContextToolCallbackFactory.SCHEDULE_CONFLICT,
                AgentContextToolCallbackFactory.SCHEDULE_DRAFT,
                AgentContextToolCallbackFactory.SCHEDULE_CANCEL_DRAFT,
                AgentContextToolCallbackFactory.SCHEDULE_UPDATE_DRAFT
            );
        assertThat(decision.allowedToolNames())
            .doesNotContain(AgentContextToolCallbackFactory.INTERVIEW_ATTACH);
    }

    @Test
    @DisplayName("面试复盘保留兼容工具链并进入复盘路由")
    void keepsInterviewReviewCompatibilityRoute() {
        AgentIntentRouter.Decision decision = router.route(request(
            "review", "请复盘本场面试，指出最重要的改进点"
        ));

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.INTERVIEW_REVIEW);
        assertThat(decision.useTools()).isTrue();
        assertThat(decision.includeSkills()).isTrue();
        assertThat(decision.fullToolset()).isFalse();
        assertThat(decision.allowedToolNames())
            .doesNotContain(AgentContextToolCallbackFactory.SCHEDULE_DRAFT);
    }

    @Test
    @DisplayName("模型可以识别不含固定关键词的日程意图")
    void usesModelClassificationForNaturalScheduleRequest() {
        interview.guide.common.ai.LlmProviderRegistry registry = mock(
            interview.guide.common.ai.LlmProviderRegistry.class
        );
        interview.guide.common.ai.PromptSanitizer sanitizer = mock(
            interview.guide.common.ai.PromptSanitizer.class
        );
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(registry.resolveChatProviderCandidates("primary")).thenReturn(List.of("primary"));
        when(registry.getPlainChatClient("primary")).thenReturn(client);
        when(sanitizer.wrapWithDelimiters(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(response);
        when(response.content()).thenReturn(
            "{\"route\":\"SCHEDULE\",\"confidence\":0.94,\"reason\":\"用户要求调整已有安排\"}"
        );

        AgentIntentRouter modelRouter = new AgentIntentRouter(
            registry, sanitizer, new ObjectMapper(), null
        );
        AgentIntentRouter.Decision decision = modelRouter.route(
            request("general", "把下午的那个约定换到三点，帮我处理"),
            "把下午的那个约定换到三点，帮我处理",
            List.of(), "primary", null
        );

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.SCHEDULE);
        assertThat(decision.confidence()).isEqualTo(0.94d);
        assertThat(decision.reason()).startsWith("模型分类");
    }

    @Test
    @DisplayName("模型把如何操作日程的问题拦截为普通问答")
    void doesNotTreatScheduleHowToQuestionAsAction() {
        interview.guide.common.ai.LlmProviderRegistry registry = mock(
            interview.guide.common.ai.LlmProviderRegistry.class
        );
        interview.guide.common.ai.PromptSanitizer sanitizer = mock(
            interview.guide.common.ai.PromptSanitizer.class
        );
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(registry.resolveChatProviderCandidates("primary")).thenReturn(List.of("primary"));
        when(registry.getPlainChatClient("primary")).thenReturn(client);
        when(sanitizer.wrapWithDelimiters(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(response);
        when(response.content()).thenReturn(
            "{\"route\":\"SCHEDULE\",\"confidence\":0.99,\"reason\":\"出现取消面试\"}"
        );

        AgentIntentRouter modelRouter = new AgentIntentRouter(
            registry, sanitizer, new ObjectMapper(), null
        );
        AgentIntentRouter.Decision decision = modelRouter.route(
            request("review", "怎么取消明天的面试？"),
            "怎么取消明天的面试？",
            List.of(), "primary", null
        );

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.GENERAL);
        assertThat(decision.useTools()).isFalse();
    }

    @Test
    @DisplayName("模型把面试复盘概念问题拦截为普通问答")
    void doesNotLoadInterviewReportForReviewConceptQuestion() {
        interview.guide.common.ai.LlmProviderRegistry registry = mock(
            interview.guide.common.ai.LlmProviderRegistry.class
        );
        interview.guide.common.ai.PromptSanitizer sanitizer = mock(
            interview.guide.common.ai.PromptSanitizer.class
        );
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(registry.resolveChatProviderCandidates("primary")).thenReturn(List.of("primary"));
        when(registry.getPlainChatClient("primary")).thenReturn(client);
        when(sanitizer.wrapWithDelimiters(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(response);
        when(response.content()).thenReturn(
            "{\"route\":\"INTERVIEW_REVIEW\",\"confidence\":0.98,\"reason\":\"提到了复盘\"}"
        );

        AgentIntentRouter modelRouter = new AgentIntentRouter(
            registry, sanitizer, new ObjectMapper(), null
        );
        AgentIntentRouter.Decision decision = modelRouter.route(
            request("review", "什么是面试复盘？"),
            "什么是面试复盘？",
            List.of(), "primary", null
        );

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.GENERAL);
        assertThat(decision.useTools()).isFalse();
    }

    @Test
    @DisplayName("模型可以同时选择 Skill、RAG 和资料上下文能力")
    void keepsMultipleCapabilitiesFromModel() {
        AgentIntentRouter modelRouter = new AgentIntentRouter(
            null, null, new ObjectMapper(), null
        );

        AgentIntentRouter.Decision decision = modelRouter.parseModelDecision(
            "{\"mode\":\"GENERAL\",\"capabilities\":[\"SKILL\",\"RAG\",\"CONTEXT\"],"
                + "\"confidence\":0.93,\"reason\":\"需要联合资料回答\"}",
            "请结合 Skill 和知识库资料回答这个问题"
        );

        assertThat(decision).isNotNull();
        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.GENERAL);
        assertThat(decision.capabilities())
            .containsExactlyInAnyOrder(
                AgentIntentRouter.Capability.SKILL,
                AgentIntentRouter.Capability.RAG,
                AgentIntentRouter.Capability.CONTEXT
            );
        assertThat(decision.includeSkills()).isTrue();
        assertThat(decision.allowedToolNames())
            .contains(AgentContextToolCallbackFactory.CONTEXT_LIST)
            .doesNotContain(AgentContextToolCallbackFactory.SCHEDULE_DRAFT);
    }

    @Test
    @DisplayName("规则兜底也保留多个能力，不因先命中 Skill 丢失 RAG")
    void fallbackKeepsMultipleCapabilities() {
        AgentIntentRouter.Decision decision = router.route(request(
            "general", "请结合 Skill 和知识库资料回答这个问题"
        ));

        assertThat(decision.capabilities())
            .contains(
                AgentIntentRouter.Capability.SKILL,
                AgentIntentRouter.Capability.RAG,
                AgentIntentRouter.Capability.CONTEXT
            );
        assertThat(decision.includeSkills()).isTrue();
        assertThat(decision.useTools()).isTrue();
    }

    @Test
    @DisplayName("模型不可用时回退规则且不让旧 review 目标污染普通问题")
    void fallsBackSafelyWhenClassifierFails() {
        interview.guide.common.ai.LlmProviderRegistry registry = mock(
            interview.guide.common.ai.LlmProviderRegistry.class
        );
        interview.guide.common.ai.PromptSanitizer sanitizer = mock(
            interview.guide.common.ai.PromptSanitizer.class
        );
        when(registry.resolveChatProviderCandidates("primary")).thenReturn(List.of("primary"));
        when(registry.getPlainChatClient("primary")).thenThrow(new IllegalStateException("provider down"));
        when(sanitizer.wrapWithDelimiters(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));

        AgentIntentRouter modelRouter = new AgentIntentRouter(
            registry, sanitizer, new ObjectMapper(), null
        );
        AgentMessageRequest original = request("review", "我想学习一下 Agent Loop 的基本原理");
        AgentIntentRouter.Decision decision = modelRouter.route(
            original, original.message(), List.of(), "primary", null
        );

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.GENERAL);
        assertThat(modelRouter.applyMode(original, decision).goal()).isEqualTo("general");
    }

    private AgentMessageRequest request(String goal, String message) {
        return new AgentMessageRequest(goal, message, List.of(), "primary");
    }
}

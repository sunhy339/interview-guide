package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.metrics.AgentMetrics;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
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
        assertThat(decision.capabilities()).isEmpty();
    }

    @Test
    @DisplayName("简历优化同时加载上下文和任务方法 Skill")
    void routesResumeOptimizationToContextAndSkillTools() {
        AgentIntentRouter.Decision decision = router.route(request(
            "general", "请根据这份简历帮我优化 Agent 开发工程师方向的项目经历"
        ));

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.CONTEXT);
        assertThat(decision.capabilities())
            .containsExactlyInAnyOrder(
                AgentIntentRouter.Capability.CONTEXT,
                AgentIntentRouter.Capability.SKILL
            );
    }

    @Test
    @DisplayName("明确要求生成业务动作时暴露统一草稿工具")
    void routesExplicitActionDraftRequestToActionTool() {
        AgentIntentRouter.Decision decision = router.route(request(
            "general", "请根据本场面试生成一个训练任务"
        ));

        assertThat(decision.capabilities())
            .contains(AgentIntentRouter.Capability.ACTION);
        assertThat(router.toolPolicy(decision).allowedToolNames())
            .contains(AgentContextToolCallbackFactory.ACTION_DRAFT);
    }

    @Test
    @DisplayName("自然表达的训练和出题请求也能进入动作能力")
    void routesNaturalActionRequestsWithoutBroadeningHowToQuestions() {
        AgentIntentRouter.Decision plan = router.route(request(
            "general", "帮我安排一个训练计划"
        ));
        AgentIntentRouter.Decision questions = router.route(request(
            "general", "帮我把几道练习题保存成训练草稿"
        ));
        AgentIntentRouter.Decision howTo = router.route(request(
            "general", "如何保存一道练习题？"
        ));

        assertThat(plan.capabilities()).containsExactly(AgentIntentRouter.Capability.ACTION);
        assertThat(questions.capabilities()).containsExactly(AgentIntentRouter.Capability.ACTION);
        assertThat(howTo.capabilities()).isEmpty();
        assertThat(router.toolPolicy(plan).allowedToolNames())
            .containsExactly(AgentContextToolCallbackFactory.ACTION_DRAFT);
        assertThat(router.toolPolicy(questions).allowedToolNames())
            .containsExactly(AgentContextToolCallbackFactory.ACTION_DRAFT);
        assertThat(router.toolPolicy(howTo).allowedToolNames()).isEmpty();
    }

    @Test
    @DisplayName("将上传文件加入简历管理路由为上下文能力")
    void routesResumeManagementImportToContextTools() {
        AgentIntentRouter.Decision decision = router.route(request(
            "general", "请把我刚上传的文件加入简历管理"
        ));

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.CONTEXT);
        assertThat(decision.capabilities())
            .containsExactly(AgentIntentRouter.Capability.CONTEXT);
    }

    @Test
    @DisplayName("明确使用 Skill 时只暴露 SkillsTool")
    void routesExplicitSkillRequestToSkillTool() {
        AgentIntentRouter.Decision decision = router.route(request(
            "general", "请根据 Skill 参考资料给我设计一道练习题"
        ));

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.SKILL);
        assertThat(decision.capabilities())
            .containsExactly(AgentIntentRouter.Capability.SKILL);
    }

    @Test
    @DisplayName("日程请求只暴露日程工具并覆盖创建修改取消")
    void routesScheduleRequestToScheduleTools() {
        AgentIntentRouter.Decision decision = router.route(request(
            "general", "帮我把明天12点的面试改到下午3点"
        ));

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.SCHEDULE);
        assertThat(decision.capabilities())
            .containsExactly(AgentIntentRouter.Capability.SCHEDULE);
    }

    @Test
    @DisplayName("模型误分类时，明确日程动作仍强制进入受控日程工具路径")
    void enforcesExplicitScheduleActionWhenModelReturnsGeneral() {
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
            "{\"mode\":\"GENERAL\",\"capabilities\":[],"
                + "\"confidence\":0.99,\"reason\":\"普通聊天\"}"
        );

        AgentIntentRouter modelRouter = new AgentIntentRouter(
            registry, sanitizer, new ObjectMapper(), null
        );
        AgentIntentRouter.Decision decision = modelRouter.route(
            request("general", "帮我安排明天12点的面试会议，公司是浪速公司，职位是 Agent 开发工程师"),
            "帮我安排明天12点的面试会议，公司是浪速公司，职位是 Agent 开发工程师",
            List.of(), "primary", null
        );

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.SCHEDULE);
        assertThat(decision.capabilities())
            .containsExactly(AgentIntentRouter.Capability.SCHEDULE);
        assertThat(modelRouter.requiredToolName(
            "帮我安排明天12点的面试会议，公司是浪速公司，职位是 Agent 开发工程师"
        )).isEqualTo(AgentContextToolCallbackFactory.SCHEDULE_DRAFT);
    }

    @Test
    @DisplayName("明确日程动作选择对应草稿工具，概念问题不强制工具")
    void selectsRequiredScheduleToolOnlyForExplicitActions() {
        assertThat(router.requiredToolName("帮我安排明天12点的面试会议"))
            .isEqualTo(AgentContextToolCallbackFactory.SCHEDULE_DRAFT);
        assertThat(router.requiredToolName("帮我取消明天12点的面试"))
            .isEqualTo(AgentContextToolCallbackFactory.SCHEDULE_CANCEL_DRAFT);
        assertThat(router.requiredToolName(
            "帮我取消明天12点浪速公司的 Agent 开发工程师面试"
        )).isEqualTo(AgentContextToolCallbackFactory.SCHEDULE_CANCEL_DRAFT);
        assertThat(router.requiredToolName("把明天12点的面试改到下午3点"))
            .isEqualTo(AgentContextToolCallbackFactory.SCHEDULE_UPDATE_DRAFT);
        assertThat(router.requiredToolName("什么是 Tool Calling，怎么安排面试"))
            .isNull();
    }

    @Test
    @DisplayName("面试复盘保留兼容工具链并进入复盘路由")
    void keepsInterviewReviewCompatibilityRoute() {
        AgentIntentRouter.Decision decision = router.route(request(
            "review", "请复盘本场面试，指出最重要的改进点"
        ));

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.INTERVIEW_REVIEW);
        assertThat(decision.capabilities())
            .containsExactlyInAnyOrder(
                AgentIntentRouter.Capability.CONTEXT,
                AgentIntentRouter.Capability.INTERVIEW_REPORT,
                AgentIntentRouter.Capability.SKILL
            );
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

        AgentMetrics metrics = new AgentMetrics(null, false);
        AgentMetrics.RunTracker run = metrics.startRun();
        AgentIntentRouter modelRouter = new AgentIntentRouter(
            registry, sanitizer, new ObjectMapper(), metrics
        );
        AgentIntentRouter.Decision decision = modelRouter.route(
            request("general", "把下午的那个约定换到三点，帮我处理"),
            "把下午的那个约定换到三点，帮我处理",
            List.of(), "primary", run
        );

        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.SCHEDULE);
        assertThat(decision.confidence()).isEqualTo(0.94d);
        assertThat(decision.reason()).startsWith("模型分类");
        assertThat(metrics.snapshot(run).modelCalls()).isEqualTo(1);
        assertThat(metrics.snapshot(run).estimatedPromptTokens()).isPositive();
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
        assertThat(decision.capabilities()).isEmpty();
        assertThat(decision.modelClassified()).isTrue();
        assertThat(decision.classifierProviderId()).isEqualTo("primary");
    }

    @Test
    @DisplayName("模型误把动作说明识别为 ACTION 时，后置校验仍不暴露动作工具")
    void doesNotExposeActionToolForHowToQuestionFromModel() {
        AgentIntentRouter modelRouter = new AgentIntentRouter(
            null, null, new ObjectMapper(), null
        );

        AgentIntentRouter.Decision decision = modelRouter.parseModelDecision(
            "{\"mode\":\"GENERAL\",\"capabilities\":[\"ACTION\"],"
                + "\"confidence\":0.98,\"reason\":\"用户询问如何操作\"}",
            "如何生成一个训练任务？"
        );

        assertThat(decision).isNotNull();
        assertThat(decision.route()).isEqualTo(AgentIntentRouter.Route.GENERAL);
        assertThat(decision.capabilities()).isEmpty();
        assertThat(modelRouter.toolPolicy(decision).allowedToolNames()).isEmpty();
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
        assertThat(decision.capabilities()).isEmpty();
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
        assertThat(decision.capabilities()).containsExactlyInAnyOrder(
            AgentIntentRouter.Capability.SKILL,
            AgentIntentRouter.Capability.RAG,
            AgentIntentRouter.Capability.CONTEXT
        );
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
                AgentIntentRouter.Capability.RAG
            );
        assertThat(decision.capabilities()).contains(
            AgentIntentRouter.Capability.SKILL,
            AgentIntentRouter.Capability.RAG
        );
        assertThat(decision.capabilities())
            .doesNotContain(AgentIntentRouter.Capability.CONTEXT);
    }

    @Test
    @DisplayName("普通问题不挂载业务工具或 Skill")
    void generalQuestionGetsNoRuntimeCapabilities() {
        AgentIntentRouter.ToolPolicy policy = router.toolPolicy(
            router.route(request("review", "现在想学习一下 Agent 开发相关知识"))
        );

        assertThat(policy.allowedToolNames()).isEmpty();
        assertThat(policy.includeSkills()).isFalse();
    }

    @Test
    @DisplayName("组合能力只挂载对应工具，不暴露无关日程工具")
    void capabilityPolicyLoadsOnlyRequestedToolFamilies() {
        AgentIntentRouter.Decision decision = new AgentIntentRouter(
            null, null, new ObjectMapper(), null
        ).parseModelDecision(
            "{\"mode\":\"GENERAL\",\"capabilities\":[\"SKILL\",\"RAG\",\"CONTEXT\"],"
                + "\"confidence\":0.93,\"reason\":\"需要联合资料回答\"}",
            "请结合 Skill 和知识库资料回答这个问题"
        );

        AgentIntentRouter.ToolPolicy policy = router.toolPolicy(decision);

        assertThat(policy.includeSkills()).isTrue();
        assertThat(policy.allowedToolNames()).containsExactlyInAnyOrder(
            AgentContextToolCallbackFactory.INTERVIEW_SEARCH,
            AgentContextToolCallbackFactory.INTERVIEW_ATTACH,
            AgentContextToolCallbackFactory.INTERVIEW_CLEAR,
            AgentContextToolCallbackFactory.CONTEXT_LIST,
            AgentContextToolCallbackFactory.CONTEXT_ASSET_ACTION,
            AgentContextToolCallbackFactory.CONTEXT_ASSET_STATUS,
            AgentContextToolCallbackFactory.KNOWLEDGE_BASE_SEARCH
        );
        assertThat(policy.allowedToolNames())
            .doesNotContain(AgentContextToolCallbackFactory.SCHEDULE_DRAFT);
    }

    @Test
    @DisplayName("日程能力只挂载日程工具")
    void scheduleCapabilityGetsOnlyScheduleTools() {
        AgentIntentRouter.ToolPolicy policy = router.toolPolicy(
            router.route(request("general", "帮我安排明天12点的面试会议"))
        );

        assertThat(policy.includeSkills()).isFalse();
        assertThat(policy.allowedToolNames()).containsExactlyInAnyOrder(
            AgentContextToolCallbackFactory.SCHEDULE_LIST,
            AgentContextToolCallbackFactory.SCHEDULE_CONFLICT,
            AgentContextToolCallbackFactory.SCHEDULE_DRAFT,
            AgentContextToolCallbackFactory.SCHEDULE_CANCEL_DRAFT,
            AgentContextToolCallbackFactory.SCHEDULE_UPDATE_DRAFT
        );
    }

    @Test
    @DisplayName("生产工具注册中心按能力生成白名单，新增工具不会绕过路由")
    void derivesToolPolicyFromToolRegistryWhenAvailable() {
        AgentContextToolCallbackFactory factory = mock(AgentContextToolCallbackFactory.class);
        ToolCallback context = callback(AgentContextToolCallbackFactory.INTERVIEW_SEARCH);
        ToolCallback rag = callback(AgentContextToolCallbackFactory.KNOWLEDGE_BASE_SEARCH);
        ToolCallback schedule = callback(AgentContextToolCallbackFactory.SCHEDULE_LIST);
        when(factory.callbacks()).thenReturn(List.of(context, rag, schedule));

        AgentIntentRouter registryBackedRouter = new AgentIntentRouter(
            null, null, new ObjectMapper(), null, new AgentToolRegistry(factory)
        );
        AgentIntentRouter.Decision decision = registryBackedRouter.parseModelDecision(
            "{\"mode\":\"GENERAL\",\"capabilities\":[\"CONTEXT\",\"RAG\"],"
                + "\"confidence\":0.94,\"reason\":\"需要资料和检索\"}",
            "请根据面试报告并结合知识库资料回答"
        );

        assertThat(registryBackedRouter.toolPolicy(decision).allowedToolNames())
            .containsExactlyInAnyOrder(
                AgentContextToolCallbackFactory.INTERVIEW_SEARCH,
                AgentContextToolCallbackFactory.KNOWLEDGE_BASE_SEARCH
            )
            .doesNotContain(AgentContextToolCallbackFactory.SCHEDULE_LIST);
    }

    private ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(
            org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name(name).description("test").inputSchema("{\"type\":\"object\"}").build()
        );
        return callback;
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

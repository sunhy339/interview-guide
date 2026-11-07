package interview.guide.modules.agent.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentActionRequestEntity;
import interview.guide.modules.agent.model.AgentActionResponse;
import interview.guide.modules.agent.model.AgentActionStatus;
import interview.guide.modules.agent.repository.AgentActionRequestRepository;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.service.InterviewScheduleService;
import interview.guide.modules.resume.service.ResumeEditorService;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Agent 安全动作服务。
 *
 * <p>动作统一经过提出、编辑、确认、执行和审计。训练计划/练习题只保存独立草稿，
 * 长期记忆和面试名称使用各自的受控服务执行；后续业务写工具必须复用这里的 owner、确认、
 * 幂等和审计边界。</p>
 */
@Service
public class AgentActionService {
    private static final String TRAINING_PLAN_TOOL = "interview.plan.draft";
    private static final String PRACTICE_QUESTIONS_TOOL = "practice.questions.draft";
    private static final String TRAINING_TASK_TOOL = "training.task.create";
    private static final String INTERVIEW_REPORT_EXPORT_TOOL = "interview.report.export";
    private static final String RESUME_EDITOR_SAVE_TOOL = "resume.editor.save";
    private static final String INTERVIEW_SCHEDULE_CREATE_TOOL = "interview.schedule.create";
    private static final String INTERVIEW_SCHEDULE_CANCEL_TOOL = "interview.schedule.cancel";
    private static final String INTERVIEW_SCHEDULE_UPDATE_TOOL = "interview.schedule.update";
    public static final String RESUME_IMPORT_TOOL = "resume.import";
    private static final String MEMORY_TOOL = "agent.memory.save";
    private static final String INTERVIEW_TITLE_TOOL = "interview.title.rename";
    private static final String RESULT_CONTRACT_VERSION = "agent-action.result.v1";
    private static final String DRAFT_RISK = "DRAFT_WRITE";
    private static final String MEMORY_RISK = "MEMORY_WRITE";
    private static final String TITLE_RISK = "BUSINESS_WRITE";
    private static final String DRAFT_TITLE = "训练计划草稿";
    private static final String DRAFT_PREVIEW = "保存一份独立训练计划草稿，不修改原始面试报告、简历或知识库。";
    private static final String PRACTICE_TITLE = "练习题草稿";
    private static final String PRACTICE_PREVIEW = "保存一组独立练习题草稿，不修改原始面试报告、简历或知识库。";
    private static final String MEMORY_TITLE = "长期记忆草稿";
    private static final String MEMORY_PREVIEW = "确认后写入一条结构化长期记忆，支持后续面试参考，可在记忆区编辑或删除。";
    private static final String TITLE_TITLE = "面试记录名称修改";
    private static final String TITLE_PREVIEW = "确认后只修改这场面试的显示名称，不修改问题、回答、评分或简历。";
    private static final String REPORT_EXPORT_TITLE = "导出面试报告";
    private static final String REPORT_EXPORT_PREVIEW = "确认后校验当前面试归属并下载 PDF 报告，不修改面试内容。";
    private static final String RESUME_EDITOR_TITLE = "简历优化编辑版";
    private static final String RESUME_EDITOR_PREVIEW = "确认后保存为当前简历的 AI 编辑版草稿，原始上传文件不被覆盖；可继续手动修改、应用或导出。";
    private static final String INTERVIEW_SCHEDULE_TITLE = "安排面试日程";
    private static final String INTERVIEW_SCHEDULE_PREVIEW = "确认后在面试日程中创建一条面试安排，仅属于当前用户，不修改面试报告。";
    private static final String INTERVIEW_SCHEDULE_CANCEL_TITLE = "取消面试日程";
    private static final String INTERVIEW_SCHEDULE_CANCEL_PREVIEW = "确认后将匹配到的面试日程标记为已取消，保留历史记录，不删除日程。";
    private static final String INTERVIEW_SCHEDULE_UPDATE_TITLE = "修改面试日程";
    private static final String INTERVIEW_SCHEDULE_UPDATE_PREVIEW = "确认后更新匹配到的面试日程；如修改时间则标记为已改期，保留原日程记录。";
    private static final String RESUME_IMPORT_TITLE = "导入简历管理";
    private static final String RESUME_IMPORT_PREVIEW = "确认后复制已审核文件到简历管理并提交异步分析；原始 Agent 上传文件保留不变。";
    private static final int MAX_OWNER_KEY = 64;
    private static final int MAX_IDEMPOTENCY_KEY = 128;
    private static final int MAX_CONTENT = 12_000;
    private static final int MAX_MEMORY_CONTENT = 160;
    private static final int MAX_TITLE = 120;
    private static final int MAX_INTERVIEW_FOCUS = 6_000;
    private static final int MAX_EXECUTION_ATTEMPTS = 3;
    private static final int RUNNING_RECOVERY_TIMEOUT_MINUTES = 5;

    private final AgentActionRequestRepository repository;
    private final AgentAuditService auditService;
    private final AgentMemoryService memoryService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final AgentTrainingTaskService trainingTaskService;
    private final ResumeEditorService resumeEditorService;
    private final InterviewScheduleService interviewScheduleService;
    private final PromptSanitizer promptSanitizer;
    private final AgentResumeImportService resumeImportService;
    private AgentConversationService conversationService;

    /** 保留轻量构造方式，便于只测试草稿状态机的调用方。 */
    public AgentActionService(
        AgentActionRequestRepository repository,
        AgentAuditService auditService
    ) {
        this(repository, auditService, null, null, null, null);
    }

    /** 保留既有测试和轻量调用方的构造方式。 */
    public AgentActionService(
        AgentActionRequestRepository repository,
        AgentAuditService auditService,
        AgentMemoryService memoryService,
        InterviewPersistenceService interviewPersistenceService
    ) {
        this(repository, auditService, memoryService, interviewPersistenceService, null, null);
    }

    /** 保留既有测试和轻量调用方的五参数构造方式。 */
    public AgentActionService(
        AgentActionRequestRepository repository,
        AgentAuditService auditService,
        AgentMemoryService memoryService,
        InterviewPersistenceService interviewPersistenceService,
        AgentTrainingTaskService trainingTaskService
    ) {
        this(repository, auditService, memoryService, interviewPersistenceService,
            trainingTaskService, null, null);
    }

    public AgentActionService(
        AgentActionRequestRepository repository,
        AgentAuditService auditService,
        AgentMemoryService memoryService,
        InterviewPersistenceService interviewPersistenceService,
        AgentTrainingTaskService trainingTaskService,
        ResumeEditorService resumeEditorService
    ) {
        this(repository, auditService, memoryService, interviewPersistenceService,
            trainingTaskService, resumeEditorService, null);
    }

    /** 保留既有测试和轻量调用方的七参数构造方式。 */
    public AgentActionService(
        AgentActionRequestRepository repository,
        AgentAuditService auditService,
        AgentMemoryService memoryService,
        InterviewPersistenceService interviewPersistenceService,
        AgentTrainingTaskService trainingTaskService,
        ResumeEditorService resumeEditorService,
        InterviewScheduleService interviewScheduleService
    ) {
        this(repository, auditService, memoryService, interviewPersistenceService,
            trainingTaskService, resumeEditorService, interviewScheduleService,
            new PromptSanitizer(new LlmProviderProperties()));
    }

    public AgentActionService(
        AgentActionRequestRepository repository,
        AgentAuditService auditService,
        AgentMemoryService memoryService,
        InterviewPersistenceService interviewPersistenceService,
        AgentTrainingTaskService trainingTaskService,
        ResumeEditorService resumeEditorService,
        InterviewScheduleService interviewScheduleService,
        PromptSanitizer promptSanitizer
    ) {
        this(repository, auditService, memoryService, interviewPersistenceService,
            trainingTaskService, resumeEditorService, interviewScheduleService,
            promptSanitizer, null);
    }

    @Autowired
    public AgentActionService(
        AgentActionRequestRepository repository,
        AgentAuditService auditService,
        AgentMemoryService memoryService,
        InterviewPersistenceService interviewPersistenceService,
        AgentTrainingTaskService trainingTaskService,
        ResumeEditorService resumeEditorService,
        InterviewScheduleService interviewScheduleService,
        PromptSanitizer promptSanitizer,
        AgentResumeImportService resumeImportService
    ) {
        this.repository = repository;
        this.auditService = auditService;
        this.memoryService = memoryService;
        this.interviewPersistenceService = interviewPersistenceService;
        this.trainingTaskService = trainingTaskService;
        this.resumeEditorService = resumeEditorService;
        this.interviewScheduleService = interviewScheduleService;
        this.promptSanitizer = promptSanitizer;
        this.resumeImportService = resumeImportService;
    }

    /** 动作服务可选接入会话续接，旧构造方式和非运行级动作保持兼容。 */
    @Autowired(required = false)
    void setConversationService(AgentConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** 生成待确认的简历编辑版草稿；确认后写入现有简历编辑器，不覆盖原始上传文件。 */
    @Transactional
    public AgentActionResponse proposeResumeEditDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String content,
        String requestedIdempotencyKey
    ) {
        String owner = requireOwner(ownerKey);
        String interviewId = requireText(interviewSessionId, "面试会话不能为空", 64);
        if (interviewPersistenceService == null
            || !interviewPersistenceService.findBySessionIdForOwner(interviewId, owner)
                .map(session -> session.getResumeId() != null)
                .orElse(false)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前面试没有可编辑的关联简历");
        }
        return proposeDraft(
            interviewId, agentSessionId, owner, content, requestedIdempotencyKey,
            RESUME_EDITOR_SAVE_TOOL, RESUME_EDITOR_TITLE, RESUME_EDITOR_PREVIEW
        );
    }

    /** 生成待确认的面试日程草稿；确认后才写入面试日程。 */
    @Transactional
    public AgentActionResponse proposeInterviewScheduleDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String companyName,
        String position,
        java.time.LocalDateTime interviewTime,
        String interviewType,
        String meetingLink,
        Integer roundNumber,
        String interviewer,
        String notes,
        String requestedIdempotencyKey
    ) {
        return proposeInterviewScheduleDraft(
            interviewSessionId, agentSessionId, ownerKey, companyName, position, interviewTime,
            interviewType, meetingLink, roundNumber, interviewer, notes, requestedIdempotencyKey,
            null
        );
    }

    /**
     * 生成带运行代次的面试日程草稿。运行代次只用于审计和恢复关联，不参与幂等键，
     * 这样同一条用户意图在恢复时不会重复生成动作。
     */
    @Transactional
    public AgentActionResponse proposeInterviewScheduleDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String companyName,
        String position,
        java.time.LocalDateTime interviewTime,
        String interviewType,
        String meetingLink,
        Integer roundNumber,
        String interviewer,
        String notes,
        String requestedIdempotencyKey,
        String runId
    ) {
        String owner = requireOwner(ownerKey);
        String interviewId = normalizeOptional(interviewSessionId, 64);
        String agentId = requireText(agentSessionId, "Agent 会话不能为空", 64);
        String normalizedCompany = sanitizeRequiredText(companyName, "公司或面试主题不能为空", 255);
        String normalizedPosition = sanitizeRequiredText(position, "复习岗位不能为空", 255);
        if (interviewTime == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试时间不能为空");
        }
        String normalizedType = sanitizeOptionalText(interviewType, 64);
        String normalizedLink = sanitizeOptionalText(meetingLink, 1000);
        String normalizedInterviewer = sanitizeOptionalText(interviewer, 255);
        String normalizedNotes = sanitizeOptionalText(notes, 2000);
        int normalizedRound = roundNumber == null ? 1 : roundNumber;
        if (normalizedRound < 1 || normalizedRound > 20) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "轮次必须在1到20之间");
        }
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        if (idempotencyKey == null) {
            idempotencyKey = digest(owner + "\n" + interviewId + "\n" + agentId
                + "\n" + INTERVIEW_SCHEDULE_CREATE_TOOL + "\n" + normalizedCompany
                + "\n" + normalizedPosition + "\n" + interviewTime + "\n" + normalizedType
                + "\n" + normalizedLink + "\n" + normalizedRound + "\n"
                + normalizedInterviewer + "\n" + normalizedNotes);
        }
        AgentActionRequestEntity existing = repository
            .findByOwnerKeyAndIdempotencyKey(owner, idempotencyKey)
            .orElse(null);
        if (existing != null) {
            return safeResponse(existing);
        }
        AgentActionRequestEntity entity = AgentActionRequestEntity.builder()
            .actionId(UUID.randomUUID().toString())
            .ownerKey(owner)
            .interviewSessionId(interviewId)
            .agentSessionId(agentId)
            .runId(normalizeOptional(runId, 64))
            .toolId(INTERVIEW_SCHEDULE_CREATE_TOOL)
            .riskLevel("BUSINESS_WRITE")
            .status(AgentActionStatus.PROPOSED)
            .title(INTERVIEW_SCHEDULE_TITLE)
            .previewText(INTERVIEW_SCHEDULE_PREVIEW)
            .draftContent(scheduleSummary(normalizedCompany, normalizedPosition, interviewTime,
                normalizedType, normalizedLink, normalizedRound, normalizedInterviewer, normalizedNotes))
            .scheduleCompanyName(normalizedCompany)
            .schedulePosition(normalizedPosition)
            .scheduleInterviewTime(interviewTime)
            .scheduleInterviewType(normalizedType)
            .scheduleMeetingLink(normalizedLink)
            .scheduleRoundNumber(normalizedRound)
            .scheduleInterviewer(normalizedInterviewer)
            .scheduleNotes(normalizedNotes)
            .idempotencyKey(idempotencyKey)
            .build();
        return saveProposed(entity);
    }

    /** 生成取消既有面试日程的待确认动作；不会直接修改日程。 */
    @Transactional
    public AgentActionResponse proposeInterviewScheduleCancelDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        InterviewScheduleDTO schedule,
        String requestedIdempotencyKey
    ) {
        return proposeInterviewScheduleCancelDraft(
            interviewSessionId, agentSessionId, ownerKey, schedule, requestedIdempotencyKey, null
        );
    }

    /** 生成带运行代次的取消日程草稿；运行代次不参与幂等键。 */
    @Transactional
    public AgentActionResponse proposeInterviewScheduleCancelDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        InterviewScheduleDTO schedule,
        String requestedIdempotencyKey,
        String runId
    ) {
        String owner = requireOwner(ownerKey);
        String interviewId = normalizeOptional(interviewSessionId, 64);
        String agentId = requireText(agentSessionId, "Agent 会话不能为空", 64);
        if (schedule == null || schedule.getId() == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在");
        }
        if (interviewScheduleService == null) {
            throw new IllegalStateException("面试日程服务未配置");
        }
        InterviewScheduleDTO owned = interviewScheduleService.getForOwner(schedule.getId(), owner);
        if (owned.getStatus() == InterviewStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已完成的面试不能取消");
        }
        if (owned.getStatus() == InterviewStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该面试已经取消，无需重复操作");
        }
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        if (idempotencyKey == null) {
            idempotencyKey = digest(owner + "\n" + interviewId + "\n" + agentId
                + "\n" + INTERVIEW_SCHEDULE_CANCEL_TOOL + "\n" + owned.getId());
        }
        AgentActionRequestEntity existing = repository
            .findByOwnerKeyAndIdempotencyKey(owner, idempotencyKey)
            .orElse(null);
        if (existing != null) {
            return safeResponse(existing);
        }
        String company = sanitizeOptionalText(owned.getCompanyName(), 255);
        String position = sanitizeOptionalText(owned.getPosition(), 255);
        String summary = "取消日程：" + company + " · " + position
            + "\n时间：" + owned.getInterviewTime()
            + "\n日程 ID：" + owned.getId();
        AgentActionRequestEntity entity = AgentActionRequestEntity.builder()
            .actionId(UUID.randomUUID().toString())
            .ownerKey(owner)
            .interviewSessionId(interviewId)
            .agentSessionId(agentId)
            .runId(normalizeOptional(runId, 64))
            .toolId(INTERVIEW_SCHEDULE_CANCEL_TOOL)
            .riskLevel("BUSINESS_WRITE")
            .status(AgentActionStatus.PROPOSED)
            .title(INTERVIEW_SCHEDULE_CANCEL_TITLE)
            .previewText(INTERVIEW_SCHEDULE_CANCEL_PREVIEW)
            .draftContent(summary)
            .scheduleId(owned.getId())
            .scheduleCompanyName(company)
            .schedulePosition(position)
            .scheduleInterviewTime(owned.getInterviewTime())
            .idempotencyKey(idempotencyKey)
            .build();
        return saveProposed(entity);
    }

    /** 生成修改/改期既有面试日程的待确认动作；不会直接修改日程。 */
    @Transactional
    public AgentActionResponse proposeInterviewScheduleUpdateDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        InterviewScheduleDTO schedule,
        String requestedIdempotencyKey
    ) {
        return proposeInterviewScheduleUpdateDraft(
            interviewSessionId, agentSessionId, ownerKey, schedule, requestedIdempotencyKey, null
        );
    }

    /** 生成带运行代次的修改日程草稿；运行代次不参与幂等键。 */
    @Transactional
    public AgentActionResponse proposeInterviewScheduleUpdateDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        InterviewScheduleDTO schedule,
        String requestedIdempotencyKey,
        String runId
    ) {
        String owner = requireOwner(ownerKey);
        String interviewId = normalizeOptional(interviewSessionId, 64);
        String agentId = requireText(agentSessionId, "Agent 会话不能为空", 64);
        if (schedule == null || schedule.getId() == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在");
        }
        if (interviewScheduleService == null) {
            throw new IllegalStateException("面试日程服务未配置");
        }
        InterviewScheduleDTO owned = interviewScheduleService.getForOwner(schedule.getId(), owner);
        if (owned.getStatus() == InterviewStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已完成的面试不能修改");
        }
        if (owned.getStatus() == InterviewStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已取消的面试不能修改，请重新创建日程");
        }
        String company = sanitizeRequiredText(schedule.getCompanyName(), "公司或面试主题不能为空", 255);
        String position = sanitizeRequiredText(schedule.getPosition(), "岗位不能为空", 255);
        if (schedule.getInterviewTime() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试时间不能为空");
        }
        String type = sanitizeOptionalText(schedule.getInterviewType(), 64);
        String link = sanitizeOptionalText(schedule.getMeetingLink(), 1000);
        String interviewer = sanitizeOptionalText(schedule.getInterviewer(), 255);
        String notes = sanitizeOptionalText(schedule.getNotes(), 2000);
        int round = schedule.getRoundNumber() == null ? 1 : schedule.getRoundNumber();
        if (round < 1 || round > 20) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "轮次必须在1到20之间");
        }
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        if (idempotencyKey == null) {
            idempotencyKey = digest(owner + "\n" + interviewId + "\n" + agentId
                + "\n" + INTERVIEW_SCHEDULE_UPDATE_TOOL + "\n" + owned.getId()
                + "\n" + company + "\n" + position + "\n" + schedule.getInterviewTime()
                + "\n" + type + "\n" + link + "\n" + round + "\n" + interviewer + "\n" + notes);
        }
        AgentActionRequestEntity existing = repository
            .findByOwnerKeyAndIdempotencyKey(owner, idempotencyKey)
            .orElse(null);
        if (existing != null) {
            return safeResponse(existing);
        }
        AgentActionRequestEntity entity = AgentActionRequestEntity.builder()
            .actionId(UUID.randomUUID().toString())
            .ownerKey(owner)
            .interviewSessionId(interviewId)
            .agentSessionId(agentId)
            .runId(normalizeOptional(runId, 64))
            .toolId(INTERVIEW_SCHEDULE_UPDATE_TOOL)
            .riskLevel("BUSINESS_WRITE")
            .status(AgentActionStatus.PROPOSED)
            .title(INTERVIEW_SCHEDULE_UPDATE_TITLE)
            .previewText(INTERVIEW_SCHEDULE_UPDATE_PREVIEW)
            .draftContent(scheduleSummary(company, position, schedule.getInterviewTime(),
                type, link, round, interviewer, notes))
            .scheduleId(owned.getId())
            .scheduleCompanyName(company)
            .schedulePosition(position)
            .scheduleInterviewTime(schedule.getInterviewTime())
            .scheduleInterviewType(type)
            .scheduleMeetingLink(link)
            .scheduleRoundNumber(round)
            .scheduleInterviewer(interviewer)
            .scheduleNotes(notes)
            .idempotencyKey(idempotencyKey)
            .build();
        return saveProposed(entity);
    }

    /** 生成待确认的训练任务草稿；确认后才进入用户任务列表。 */
    @Transactional
    public AgentActionResponse proposeTrainingTaskDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String content,
        String requestedIdempotencyKey
    ) {
        return proposeDraft(
            interviewSessionId, agentSessionId, ownerKey, content, requestedIdempotencyKey,
            TRAINING_TASK_TOOL, "训练任务草稿",
            "确认后创建一条独立训练任务，可在任务列表中推进或完成，不修改原始面试资料。"
        );
    }

    @Transactional
    public AgentActionResponse proposeTrainingPlanDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String content,
        String requestedIdempotencyKey
    ) {
        return proposeDraft(
            interviewSessionId, agentSessionId, ownerKey, content, requestedIdempotencyKey,
            TRAINING_PLAN_TOOL, DRAFT_TITLE, DRAFT_PREVIEW
        );
    }

    @Transactional
    public AgentActionResponse proposePracticeQuestionsDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String content,
        String requestedIdempotencyKey
    ) {
        return proposeDraft(
            interviewSessionId, agentSessionId, ownerKey, content, requestedIdempotencyKey,
            PRACTICE_QUESTIONS_TOOL, PRACTICE_TITLE, PRACTICE_PREVIEW
        );
    }

    @Transactional
    public AgentActionResponse proposeMemoryDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String content,
        String category,
        String jobScope,
        String requestedIdempotencyKey
    ) {
        String normalizedCategory = normalizeMemoryCategory(category);
        String normalizedScope = sanitizeOptionalText(jobScope, 64);
        String memory = sanitizeRequiredText(content, "记忆草稿不能为空", MAX_MEMORY_CONTENT);
        String owner = requireOwner(ownerKey);
        String interviewId = requireText(interviewSessionId, "面试会话不能为空", 64);
        String agentId = requireText(agentSessionId, "Agent 会话不能为空", 64);
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        if (idempotencyKey == null) {
            idempotencyKey = digest(owner + "\n" + interviewId + "\n" + agentId
                + "\n" + MEMORY_TOOL + "\n" + normalizedCategory + "\n" + normalizedScope + "\n" + memory);
        }
        AgentActionRequestEntity existing = repository
            .findByOwnerKeyAndIdempotencyKey(owner, idempotencyKey)
            .orElse(null);
        if (existing != null) {
            return safeResponse(existing);
        }
        AgentActionRequestEntity entity = AgentActionRequestEntity.builder()
            .actionId(UUID.randomUUID().toString())
            .ownerKey(owner)
            .interviewSessionId(interviewId)
            .agentSessionId(agentId)
            .toolId(MEMORY_TOOL)
            .riskLevel(MEMORY_RISK)
            .status(AgentActionStatus.PROPOSED)
            .title(MEMORY_TITLE)
            .previewText(MEMORY_PREVIEW)
            .draftContent(memory)
            .memoryCategory(normalizedCategory)
            .memoryJobScope(normalizedScope)
            .idempotencyKey(idempotencyKey)
            .build();
        return saveProposed(entity);
    }

    @Transactional
    public AgentActionResponse proposeInterviewTitleDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String title,
        String requestedIdempotencyKey
    ) {
        String owner = requireOwner(ownerKey);
        String interviewId = requireText(interviewSessionId, "面试会话不能为空", 64);
        String agentId = requireText(agentSessionId, "Agent 会话不能为空", 64);
        String normalizedTitle = sanitizeRequiredText(title, "面试记录名称不能为空", MAX_TITLE);
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        if (idempotencyKey == null) {
            idempotencyKey = digest(owner + "\n" + interviewId + "\n" + agentId
                + "\n" + INTERVIEW_TITLE_TOOL + "\n" + normalizedTitle);
        }
        AgentActionRequestEntity existing = repository
            .findByOwnerKeyAndIdempotencyKey(owner, idempotencyKey)
            .orElse(null);
        if (existing != null) {
            return safeResponse(existing);
        }
        AgentActionRequestEntity entity = AgentActionRequestEntity.builder()
            .actionId(UUID.randomUUID().toString())
            .ownerKey(owner)
            .interviewSessionId(interviewId)
            .agentSessionId(agentId)
            .toolId(INTERVIEW_TITLE_TOOL)
            .riskLevel(TITLE_RISK)
            .status(AgentActionStatus.PROPOSED)
            .title(TITLE_TITLE)
            .previewText(TITLE_PREVIEW)
            .draftContent(normalizedTitle)
            .targetTitle(normalizedTitle)
            .idempotencyKey(idempotencyKey)
            .build();
        return saveProposed(entity);
    }

    /** 生成待确认的面试报告导出动作；确认时会校验当前用户仍拥有该面试。 */
    @Transactional
    public AgentActionResponse proposeInterviewReportExportDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String requestedIdempotencyKey
    ) {
        return proposeDraft(
            interviewSessionId, agentSessionId, ownerKey,
            "导出当前面试报告 PDF", requestedIdempotencyKey,
            INTERVIEW_REPORT_EXPORT_TOOL, REPORT_EXPORT_TITLE, REPORT_EXPORT_PREVIEW
        );
    }

    /** 生成通用 Agent 的简历导入动作；确认后才复制文件并进入简历管理。 */
    @Transactional
    public AgentActionResponse proposeResumeImportDraft(
        String agentSessionId,
        String ownerKey,
        String contextAssetId,
        String requestedIdempotencyKey
    ) {
        return proposeResumeImportDraft(
            agentSessionId, ownerKey, contextAssetId, requestedIdempotencyKey, null
        );
    }

    /** 生成带运行代次的简历导入草稿；运行代次不参与幂等键。 */
    @Transactional
    public AgentActionResponse proposeResumeImportDraft(
        String agentSessionId,
        String ownerKey,
        String contextAssetId,
        String requestedIdempotencyKey,
        String runId
    ) {
        String owner = requireOwner(ownerKey);
        String agentId = requireText(agentSessionId, "Agent 会话不能为空", 64);
        String assetId = requireText(contextAssetId, "Agent 文件素材不能为空", 64);
        if (resumeImportService == null) {
            throw new IllegalStateException("Agent 简历导入服务未配置");
        }
        var asset = resumeImportService.requireImportableAsset(assetId, owner);
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        if (idempotencyKey == null) {
            idempotencyKey = digest(owner + "\n" + agentId + "\n"
                + RESUME_IMPORT_TOOL + "\n" + asset.getAssetId());
        }
        AgentActionRequestEntity existing = repository
            .findByOwnerKeyAndIdempotencyKey(owner, idempotencyKey)
            .orElse(null);
        if (existing != null) {
            return safeResponse(existing);
        }
        AgentActionRequestEntity entity = AgentActionRequestEntity.builder()
            .actionId(UUID.randomUUID().toString())
            .ownerKey(owner)
            .agentSessionId(agentId)
            .runId(normalizeOptional(runId, 64))
            .toolId(RESUME_IMPORT_TOOL)
            .riskLevel("BUSINESS_WRITE")
            .status(AgentActionStatus.PROPOSED)
            .title(RESUME_IMPORT_TITLE)
            .previewText(RESUME_IMPORT_PREVIEW)
            .draftContent("文件：" + asset.getOriginalFilename()
                + "\n导入后：进入简历管理并提交异步分析"
                + "\n原始 Agent 文件：保留")
            .contextAssetId(asset.getAssetId())
            .idempotencyKey(idempotencyKey)
            .build();
        return saveProposed(entity);
    }

    private AgentActionResponse proposeDraft(
        String interviewSessionId,
        String agentSessionId,
        String ownerKey,
        String content,
        String requestedIdempotencyKey,
        String toolId,
        String title,
        String preview
    ) {
        String owner = requireOwner(ownerKey);
        String interviewId = requireText(interviewSessionId, "面试会话不能为空", 64);
        String agentId = requireText(agentSessionId, "Agent 会话不能为空", 64);
        String draft = normalizeContent(content);
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        if (idempotencyKey == null) {
            idempotencyKey = digest(owner + "\n" + interviewId + "\n" + agentId + "\n" + draft);
        }

        AgentActionRequestEntity existing = repository
            .findByOwnerKeyAndIdempotencyKey(owner, idempotencyKey)
            .orElse(null);
        if (existing != null) {
            return safeResponse(existing);
        }

        AgentActionRequestEntity entity = AgentActionRequestEntity.builder()
            .actionId(UUID.randomUUID().toString())
            .ownerKey(owner)
            .interviewSessionId(interviewId)
            .agentSessionId(agentId)
            .toolId(toolId)
            .riskLevel(DRAFT_RISK)
            .status(AgentActionStatus.PROPOSED)
            .title(title)
            .previewText(preview)
            .draftContent(draft)
            .idempotencyKey(idempotencyKey)
            .build();
        return saveProposed(entity);
    }

    private AgentActionResponse saveProposed(AgentActionRequestEntity entity) {
        AgentActionRequestEntity saved = repository.save(entity);
        auditService.recordQuietly(
            saved.getOwnerKey(), saved.getInterviewSessionId(), saved.getAgentSessionId(), saved.getRunId(),
            "ACTION_PROPOSED", "agent-action",
            saved.getActionId(), AgentActionStatus.PROPOSED.name()
        );
        return safeResponse(saved);
    }

    @Transactional
    public List<AgentActionResponse> listForOwner(String interviewSessionId, String ownerKey) {
        return listForOwner(interviewSessionId, null, ownerKey);
    }

    @Transactional
    public List<AgentActionResponse> listForOwner(
        String interviewSessionId, String agentSessionId, String ownerKey
    ) {
        String owner = requireOwner(ownerKey);
        String interviewId = requireText(interviewSessionId, "面试会话不能为空", 64);
        String agentId = agentSessionId == null ? "" : agentSessionId.trim();
        List<AgentActionRequestEntity> actions = agentId.isBlank()
            ? repository.findTop50ByOwnerKeyAndInterviewSessionIdOrderByCreatedAtDesc(owner, interviewId)
            : repository.findTop50ByOwnerKeyAndInterviewSessionIdAndAgentSessionIdOrderByCreatedAtDesc(
                owner, interviewId, requireText(agentId, "Agent 会话不能为空", 64));
        return actions.stream().map(this::safeResponse).toList();
    }

    /** 查询通用 Agent 会话生成的动作草稿。 */
    @Transactional
    public List<AgentActionResponse> listForAgentSession(String agentSessionId, String ownerKey) {
        String owner = requireOwner(ownerKey);
        String agentId = requireText(agentSessionId, "Agent 会话不能为空", 64);
        return repository.findTop50ByOwnerKeyAndAgentSessionIdOrderByCreatedAtDesc(owner, agentId)
            .stream().map(this::safeResponse).toList();
    }

    @Transactional
    public AgentActionResponse confirm(String actionId, String ownerKey) {
        AgentActionRequestEntity entity = findOwnedForUpdate(actionId, ownerKey);
        boolean sanitized = sanitizeStoredAction(entity);
        if (entity.getStatus() == AgentActionStatus.SUCCEEDED) {
            AgentActionResponse response = safeResponse(entity);
            resolveWaitingRun(entity, response, "用户已确认并完成 Agent 动作");
            return response;
        }
        if (sanitized && (entity.getStatus() == AgentActionStatus.PROPOSED
            || entity.getStatus() == AgentActionStatus.FAILED)) {
            repository.save(entity);
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "该 Agent 草稿包含不安全内容，已净化保存，请检查后重新确认"
            );
        }
        if (entity.getStatus() == AgentActionStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该 Agent 动作已取消，不能重复确认");
        }
        if (entity.getStatus() == AgentActionStatus.FAILED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该 Agent 动作执行失败，请使用重试");
        }

        if (entity.getStatus() == AgentActionStatus.PROPOSED) {
            entity.setStatus(AgentActionStatus.CONFIRMED);
            entity.setConfirmedAt(LocalDateTime.now());
            audit(entity, "ACTION_CONFIRMED", AgentActionStatus.CONFIRMED.name());
        }
        AgentActionResponse response = executeConfirmed(entity, false);
        resolveWaitingRun(entity, response, "用户已确认并完成 Agent 动作");
        return response;
    }

    /** 重试一次已经失败的动作，最多允许三次实际执行。 */
    @Transactional
    public AgentActionResponse retry(String actionId, String ownerKey) {
        AgentActionRequestEntity entity = findOwnedForUpdate(actionId, ownerKey);
        boolean sanitized = sanitizeStoredAction(entity);
        if (entity.getStatus() == AgentActionStatus.SUCCEEDED) {
            AgentActionResponse response = safeResponse(entity);
            resolveWaitingRun(entity, response, "重试动作已完成，Agent 运行已恢复");
            return response;
        }
        if (entity.getStatus() != AgentActionStatus.FAILED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有执行失败的 Agent 动作可以重试");
        }
        if (executionAttempts(entity) >= MAX_EXECUTION_ATTEMPTS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该 Agent 动作已达到最大重试次数，请重新生成草稿");
        }
        if (sanitized) {
            repository.save(entity);
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "该 Agent 草稿包含不安全内容，已净化保存，请重新检查后重试"
            );
        }
        AgentActionResponse response = executeConfirmed(entity, true);
        resolveWaitingRun(entity, response, "重试动作已完成，Agent 运行已恢复");
        return response;
    }

    /**
     * 将进程中断后遗留的 RUNNING 动作转成可见的失败状态。
     *
     * <p>恢复过程不会自动重新调用业务写入器，避免“写入成功但进程在落库前崩溃”
     * 时产生重复副作用；用户检查结果后仍可通过现有重试入口决定是否重放。</p>
     */
    @Transactional
    public int recoverStaleRunningActions() {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(RUNNING_RECOVERY_TIMEOUT_MINUTES);
        List<AgentActionRequestEntity> staleActions = repository.findStaleRunningForUpdate(
            AgentActionStatus.RUNNING, staleBefore
        );
        int recovered = 0;
        for (AgentActionRequestEntity entity : staleActions) {
            if (entity.getStatus() != AgentActionStatus.RUNNING) {
                continue;
            }
            entity.setStatus(AgentActionStatus.FAILED);
            entity.setCompletedAt(LocalDateTime.now());
            entity.setResultSummary(recoverySummary(entity));
            repository.save(entity);
            audit(entity, "ACTION_RECOVERED", AgentActionStatus.FAILED.name());
            recovered++;
        }
        return recovered;
    }

    /** 读取当前用户已经确认的训练动作，供下一轮面试出题使用。 */
    @Transactional
    public String loadConfirmedInterviewFocus(String actionId, String ownerKey) {
        String owner = requireOwner(ownerKey);
        AgentActionRequestEntity entity = repository.findByActionId(requireText(
            actionId, "Agent 训练动作不能为空", 64
        )).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent 练习动作不存在"));
        if (!owner.equals(entity.getOwnerKey())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该 Agent 训练动作");
        }
        boolean supportedTool = PRACTICE_QUESTIONS_TOOL.equals(entity.getToolId())
            || TRAINING_PLAN_TOOL.equals(entity.getToolId())
            || TRAINING_TASK_TOOL.equals(entity.getToolId());
        if (!supportedTool
            || entity.getStatus() != AgentActionStatus.SUCCEEDED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有已确认的练习题、训练计划或训练任务可以用于专项面试");
        }
        String content = normalizeContent(entity.getDraftContent());
        return content.substring(0, Math.min(MAX_INTERVIEW_FOCUS, content.length()));
    }

    /** 返回已确认报告导出动作对应的面试，会再次校验动作和面试归属。 */
    @Transactional
    public String loadConfirmedReportSessionId(String actionId, String ownerKey) {
        String owner = requireOwner(ownerKey);
        AgentActionRequestEntity entity = repository.findByActionId(requireText(
            actionId, "Agent 导出动作不能为空", 64
        )).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent 导出动作不存在"));
        if (!owner.equals(entity.getOwnerKey())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该 Agent 导出动作");
        }
        if (!INTERVIEW_REPORT_EXPORT_TOOL.equals(entity.getToolId())
            || entity.getStatus() != AgentActionStatus.SUCCEEDED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有已确认的报告导出动作可以下载面试报告");
        }
        if (interviewPersistenceService == null
            || interviewPersistenceService.findBySessionIdForOwner(
                entity.getInterviewSessionId(), owner
            ).isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "面试报告不存在或无权访问");
        }
        return entity.getInterviewSessionId();
    }

    /**
     * 重启恢复审批续跑时读取已经提交的动作结果；只允许同一 owner 读取成功或取消结果。
     * 不重新执行动作，也不接受模型传入的动作参数。
     */
    @Transactional
    public AgentActionResponse loadApprovalResumeAction(String actionId, String ownerKey) {
        String owner = requireOwner(ownerKey);
        String safeActionId = requireText(actionId, "Agent 审批动作不能为空", 64);
        AgentActionRequestEntity entity = repository.findByActionId(safeActionId).orElse(null);
        if (entity == null || !owner.equals(entity.getOwnerKey())) {
            return null;
        }
        if (entity.getStatus() != AgentActionStatus.SUCCEEDED
            && entity.getStatus() != AgentActionStatus.CANCELLED) {
            return null;
        }
        sanitizeStoredAction(entity);
        return safeResponse(entity);
    }

    private AgentActionResponse executeConfirmed(AgentActionRequestEntity entity, boolean retry) {
        if (executionAttempts(entity) >= MAX_EXECUTION_ATTEMPTS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该 Agent 动作已达到最大重试次数，请重新生成草稿");
        }
        if (retry) {
            audit(entity, "ACTION_RETRY", String.valueOf(executionAttempts(entity) + 1));
        }
        entity.setExecutionAttempts(executionAttempts(entity) + 1);
        entity.setStatus(AgentActionStatus.RUNNING);
        repository.save(entity);
        audit(entity, "ACTION_RUNNING", AgentActionStatus.RUNNING.name());

        try {
            // 重试前清除上一次失败摘要；执行器若返回专属结果（例如分析投递失败）会覆盖它。
            entity.setResultSummary(null);
            clearStructuredResult(entity);
            execute(entity);
            requireStructuredResult(entity);
            entity.setStatus(AgentActionStatus.SUCCEEDED);
            if (entity.getResultSummary() == null) {
                entity.setResultSummary(successSummary(entity.getToolId()));
            }
            if (entity.getResultCode() == null) {
                setStructuredResult(entity, "ACTION_DRAFT_SAVED", "AGENT_ACTION",
                    entity.getActionId(), "DRAFT_SAVED");
            }
            entity.setCompletedAt(LocalDateTime.now());
            repository.save(entity);
            audit(entity, "ACTION_SUCCEEDED", AgentActionStatus.SUCCEEDED.name());
            return safeResponse(entity);
        } catch (RuntimeException error) {
            entity.setStatus(AgentActionStatus.FAILED);
            entity.setResultSummary(failureSummary(entity));
            setStructuredResult(entity, "ACTION_FAILED", "AGENT_ACTION",
                entity.getActionId(), "NO_EFFECT");
            entity.setCompletedAt(LocalDateTime.now());
            repository.save(entity);
            audit(entity, "ACTION_FAILED", AgentActionStatus.FAILED.name());
            return safeResponse(entity);
        }
    }

    private void execute(AgentActionRequestEntity entity) {
        if (MEMORY_TOOL.equals(entity.getToolId())) {
            if (memoryService == null) {
                throw new IllegalStateException("长期记忆服务未配置");
            }
            var snapshot = memoryService.rememberItem(
                entity.getOwnerKey(), entity.getMemoryCategory(), entity.getDraftContent(),
                entity.getMemoryJobScope()
            );
            if (snapshot == null) {
                throw new IllegalStateException("长期记忆服务未返回保存结果");
            }
            String itemId = snapshot.items().stream()
                .filter(item -> item != null
                    && java.util.Objects.equals(entity.getMemoryCategory(), item.category())
                    && java.util.Objects.equals(entity.getDraftContent(), item.content())
                    && item.revokedAt() == null)
                .map(interview.guide.modules.agent.model.AgentMemoryItem::id)
                .findFirst()
                .orElse(null);
            if (itemId == null || itemId.isBlank()) {
                throw new IllegalStateException("长期记忆服务未返回记忆资源 ID");
            }
            setStructuredResult(entity, "MEMORY_SAVED", "MEMORY_ITEM", itemId, "MEMORY_WRITE");
            return;
        }
        if (INTERVIEW_TITLE_TOOL.equals(entity.getToolId())) {
            if (interviewPersistenceService == null) {
                throw new IllegalStateException("面试持久化服务未配置");
            }
            interviewPersistenceService.renameSessionForOwner(
                entity.getInterviewSessionId(), entity.getOwnerKey(), entity.getTargetTitle()
            );
            setStructuredResult(entity, "INTERVIEW_RENAMED", "INTERVIEW_SESSION",
                entity.getInterviewSessionId(), "RESOURCE_UPDATED");
            return;
        }
        if (TRAINING_TASK_TOOL.equals(entity.getToolId())) {
            if (trainingTaskService == null) {
                throw new IllegalStateException("训练任务服务未配置");
            }
            var task = trainingTaskService.createFromAction(entity);
            if (task == null || task.taskId() == null || task.taskId().isBlank()) {
                throw new IllegalStateException("训练任务服务未返回任务资源 ID");
            }
            setStructuredResult(entity, "TRAINING_TASK_CREATED", "TRAINING_TASK",
                task.taskId(), "RESOURCE_CREATED");
            return;
        }
        if (INTERVIEW_REPORT_EXPORT_TOOL.equals(entity.getToolId())) {
            if (interviewPersistenceService == null
                || interviewPersistenceService.findBySessionIdForOwner(
                    entity.getInterviewSessionId(), entity.getOwnerKey()
                ).isEmpty()) {
                throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "面试报告不存在或无权访问");
            }
            setStructuredResult(entity, "REPORT_EXPORT_READY", "INTERVIEW_REPORT",
                entity.getInterviewSessionId(), "READ_ONLY");
            return;
        }
        if (RESUME_EDITOR_SAVE_TOOL.equals(entity.getToolId())) {
            if (resumeEditorService == null || interviewPersistenceService == null) {
                throw new IllegalStateException("简历编辑服务未配置");
            }
            Long resumeId = interviewPersistenceService.findBySessionIdForOwner(
                entity.getInterviewSessionId(), entity.getOwnerKey()
            ).map(session -> session.getResumeId()).orElse(null);
            if (resumeId == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "当前面试没有可编辑的关联简历");
            }
            var draft = resumeEditorService.saveAgentDraftForOwner(
                resumeId, entity.getOwnerKey(), entity.getDraftContent()
            );
            if (draft == null || draft.resumeId() == null) {
                throw new IllegalStateException("简历编辑服务未返回编辑版资源 ID");
            }
            setStructuredResult(entity, "RESUME_EDIT_DRAFT_SAVED", "RESUME_EDITOR_DRAFT",
                String.valueOf(draft.resumeId()), "DRAFT_WRITE");
            return;
        }
        if (RESUME_IMPORT_TOOL.equals(entity.getToolId())) {
            if (resumeImportService == null || entity.getContextAssetId() == null) {
                throw new IllegalStateException("Agent 简历导入服务未配置或素材不存在");
            }
            AgentResumeImportService.ResumeImportResult result = resumeImportService.importApprovedAsset(
                entity.getContextAssetId(), entity.getOwnerKey()
            );
            if (result == null || result.resumeId() == null) {
                throw new IllegalStateException("简历导入服务未返回简历资源 ID");
            }
            entity.setResultSummary(result.message());
            setStructuredResult(entity,
                result.duplicate() ? "RESUME_IMPORT_REUSED" : "RESUME_IMPORTED",
                "RESUME", String.valueOf(result.resumeId()),
                result.duplicate() ? "RESOURCE_REUSED" : "RESOURCE_CREATED");
            return;
        }
        if (INTERVIEW_SCHEDULE_CREATE_TOOL.equals(entity.getToolId())) {
            if (interviewScheduleService == null) {
                throw new IllegalStateException("面试日程服务未配置");
            }
            CreateInterviewRequest request = new CreateInterviewRequest();
            request.setCompanyName(entity.getScheduleCompanyName());
            request.setPosition(entity.getSchedulePosition());
            request.setInterviewTime(entity.getScheduleInterviewTime());
            request.setInterviewType(entity.getScheduleInterviewType());
            request.setMeetingLink(entity.getScheduleMeetingLink());
            request.setRoundNumber(entity.getScheduleRoundNumber());
            request.setInterviewer(entity.getScheduleInterviewer());
            request.setNotes(entity.getScheduleNotes());
            InterviewScheduleDTO schedule = interviewScheduleService.createForOwner(
                request, entity.getOwnerKey(), entity.getActionId()
            );
            if (schedule == null || schedule.getId() == null) {
                throw new IllegalStateException("面试日程服务未返回日程资源 ID");
            }
            setStructuredResult(entity, "SCHEDULE_CREATED", "INTERVIEW_SCHEDULE",
                String.valueOf(schedule.getId()), "RESOURCE_CREATED");
            return;
        }
        if (INTERVIEW_SCHEDULE_CANCEL_TOOL.equals(entity.getToolId())) {
            if (interviewScheduleService == null) {
                throw new IllegalStateException("面试日程服务未配置");
            }
            if (entity.getScheduleId() == null) {
                throw new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "待取消的面试日程不存在");
            }
            InterviewScheduleDTO schedule = interviewScheduleService.cancelForOwner(
                entity.getScheduleId(), entity.getOwnerKey()
            );
            if (schedule == null || schedule.getId() == null) {
                throw new IllegalStateException("取消日程服务未返回日程资源 ID");
            }
            setStructuredResult(entity, "SCHEDULE_CANCELLED", "INTERVIEW_SCHEDULE",
                String.valueOf(schedule.getId()), "RESOURCE_CANCELLED");
            return;
        }
        if (INTERVIEW_SCHEDULE_UPDATE_TOOL.equals(entity.getToolId())) {
            if (interviewScheduleService == null) {
                throw new IllegalStateException("面试日程服务未配置");
            }
            if (entity.getScheduleId() == null) {
                throw new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "待修改的面试日程不存在");
            }
            CreateInterviewRequest request = new CreateInterviewRequest();
            request.setCompanyName(entity.getScheduleCompanyName());
            request.setPosition(entity.getSchedulePosition());
            request.setInterviewTime(entity.getScheduleInterviewTime());
            request.setInterviewType(entity.getScheduleInterviewType());
            request.setMeetingLink(entity.getScheduleMeetingLink());
            request.setRoundNumber(entity.getScheduleRoundNumber());
            request.setInterviewer(entity.getScheduleInterviewer());
            request.setNotes(entity.getScheduleNotes());
            InterviewScheduleDTO schedule = interviewScheduleService.updateForOwner(
                entity.getScheduleId(), request, entity.getOwnerKey()
            );
            if (schedule == null || schedule.getId() == null) {
                throw new IllegalStateException("修改日程服务未返回日程资源 ID");
            }
            setStructuredResult(entity, "SCHEDULE_UPDATED", "INTERVIEW_SCHEDULE",
                String.valueOf(schedule.getId()), "RESOURCE_UPDATED");
            return;
        }
        // 训练计划和练习题的确认结果仍然只保存在动作表，不修改其他业务实体。
        setStructuredResult(entity, "ACTION_DRAFT_SAVED", "AGENT_ACTION",
            entity.getActionId(), "DRAFT_SAVED");
    }

    private void clearStructuredResult(AgentActionRequestEntity entity) {
        entity.setResultContractVersion(null);
        entity.setResultCode(null);
        entity.setResultEffect(null);
        entity.setResultResourceType(null);
        entity.setResultResourceId(null);
    }

    private void setStructuredResult(
        AgentActionRequestEntity entity,
        String code,
        String resourceType,
        String resourceId,
        String effect
    ) {
        entity.setResultContractVersion(RESULT_CONTRACT_VERSION);
        entity.setResultCode(code);
        entity.setResultEffect(effect);
        entity.setResultResourceType(resourceType);
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("Agent 动作结果缺少资源 ID");
        }
        entity.setResultResourceId(resourceId);
    }

    /**
     * 执行器的成功分支必须留下可验证的领域结果，不能只靠 resultSummary 让模型猜测状态。
     * 这里是最后一道服务端断言；历史动作仍允许没有结果字段，但新执行不能绕过该契约。
     */
    private void requireStructuredResult(AgentActionRequestEntity entity) {
        if (!RESULT_CONTRACT_VERSION.equals(entity.getResultContractVersion())
            || isBlank(entity.getResultCode())
            || isBlank(entity.getResultEffect())
            || isBlank(entity.getResultResourceType())
            || isBlank(entity.getResultResourceId())) {
            throw new IllegalStateException("Agent 动作执行器未返回完整结果契约");
        }
        String tool = entity.getToolId();
        String code = entity.getResultCode();
        String effect = entity.getResultEffect();
        String type = entity.getResultResourceType();
        boolean valid = switch (tool) {
            case MEMORY_TOOL -> "MEMORY_SAVED".equals(code)
                && "MEMORY_WRITE".equals(effect) && "MEMORY_ITEM".equals(type);
            case INTERVIEW_TITLE_TOOL -> "INTERVIEW_RENAMED".equals(code)
                && "RESOURCE_UPDATED".equals(effect) && "INTERVIEW_SESSION".equals(type);
            case TRAINING_TASK_TOOL -> "TRAINING_TASK_CREATED".equals(code)
                && "RESOURCE_CREATED".equals(effect) && "TRAINING_TASK".equals(type);
            case INTERVIEW_REPORT_EXPORT_TOOL -> "REPORT_EXPORT_READY".equals(code)
                && "READ_ONLY".equals(effect) && "INTERVIEW_REPORT".equals(type);
            case RESUME_EDITOR_SAVE_TOOL -> "RESUME_EDIT_DRAFT_SAVED".equals(code)
                && "DRAFT_WRITE".equals(effect) && "RESUME_EDITOR_DRAFT".equals(type);
            case RESUME_IMPORT_TOOL -> ("RESUME_IMPORTED".equals(code)
                || "RESUME_IMPORT_REUSED".equals(code))
                && ("RESOURCE_CREATED".equals(effect) || "RESOURCE_REUSED".equals(effect))
                && "RESUME".equals(type);
            case INTERVIEW_SCHEDULE_CREATE_TOOL -> "SCHEDULE_CREATED".equals(code)
                && "RESOURCE_CREATED".equals(effect) && "INTERVIEW_SCHEDULE".equals(type);
            case INTERVIEW_SCHEDULE_CANCEL_TOOL -> "SCHEDULE_CANCELLED".equals(code)
                && "RESOURCE_CANCELLED".equals(effect) && "INTERVIEW_SCHEDULE".equals(type);
            case INTERVIEW_SCHEDULE_UPDATE_TOOL -> "SCHEDULE_UPDATED".equals(code)
                && "RESOURCE_UPDATED".equals(effect) && "INTERVIEW_SCHEDULE".equals(type);
            default -> "ACTION_DRAFT_SAVED".equals(code)
                && "DRAFT_SAVED".equals(effect) && "AGENT_ACTION".equals(type);
        };
        if (!valid) {
            throw new IllegalStateException("Agent 动作执行结果与领域工具不匹配");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Transactional
    public AgentActionResponse updateDraft(String actionId, String ownerKey, String content) {
        AgentActionRequestEntity entity = findOwnedForUpdate(actionId, ownerKey);
        sanitizeStoredAction(entity);
        if (entity.getStatus() != AgentActionStatus.PROPOSED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有待确认的 Agent 草稿可以编辑");
        }
        if (INTERVIEW_SCHEDULE_CANCEL_TOOL.equals(entity.getToolId())
            || INTERVIEW_SCHEDULE_UPDATE_TOOL.equals(entity.getToolId())
            || RESUME_IMPORT_TOOL.equals(entity.getToolId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该动作不支持编辑，请重新查询后生成草稿");
        }
        String updatedContent = INTERVIEW_SCHEDULE_CREATE_TOOL.equals(entity.getToolId())
            ? updateScheduleDraft(entity, content)
            : MEMORY_TOOL.equals(entity.getToolId())
            ? sanitizeRequiredText(content, "记忆草稿不能为空", MAX_MEMORY_CONTENT)
            : INTERVIEW_TITLE_TOOL.equals(entity.getToolId())
                ? sanitizeRequiredText(content, "面试记录名称不能为空", MAX_TITLE)
                : normalizeContent(content);
        entity.setDraftContent(updatedContent);
        if (INTERVIEW_TITLE_TOOL.equals(entity.getToolId())) {
            entity.setTargetTitle(entity.getDraftContent());
        }
        entity.setResultSummary(null);
        AgentActionRequestEntity saved = repository.save(entity);
        audit(saved, "ACTION_DRAFT_UPDATED", AgentActionStatus.PROPOSED.name());
        return safeResponse(saved);
    }

    @Transactional
    public AgentActionResponse cancel(String actionId, String ownerKey) {
        AgentActionRequestEntity entity = findOwnedForUpdate(actionId, ownerKey);
        if (entity.getStatus() == AgentActionStatus.SUCCEEDED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已保存的 Agent 草稿不能取消");
        }
        if (entity.getStatus() == AgentActionStatus.CANCELLED) {
            if (conversationService != null && entity.getRunId() != null) {
                resolveWaitingRun(entity, safeResponse(entity), "用户已取消待确认动作，Agent 正在继续处理");
            }
            return safeResponse(entity);
        }
        entity.setStatus(AgentActionStatus.CANCELLED);
        entity.setResultSummary("用户取消了该动作，原始面试资料未被修改。");
        setStructuredResult(entity, "ACTION_CANCELLED", "AGENT_ACTION",
            entity.getActionId(), "NO_EFFECT");
        repository.save(entity);
        audit(entity, "ACTION_CANCELLED", AgentActionStatus.CANCELLED.name());
        if (conversationService != null && entity.getRunId() != null) {
            resolveWaitingRun(entity, safeResponse(entity), "用户已取消待确认动作，Agent 正在继续处理");
        }
        return safeResponse(entity);
    }

    private void resolveWaitingRun(
        AgentActionRequestEntity entity, AgentActionResponse response, String notice
    ) {
        if (conversationService == null || entity == null || response == null
            || (response.status() != AgentActionStatus.SUCCEEDED
                && response.status() != AgentActionStatus.CANCELLED)
            || entity.getRunId() == null || entity.getRunId().isBlank()) {
            return;
        }
        AgentConversationService.ApprovalResumeRequest resumed = conversationService.prepareApprovalResume(
            entity.getAgentSessionId(), entity.getRunId(), entity.getOwnerKey(),
            response.actionId(), response.title(), response.status().name(), response.resultSummary(),
            response.resultContractVersion(), response.resultCode(), response.resultEffect(),
            response.resultResourceType(), response.resultResourceId(), notice
        );
        if (resumed == null) {
            // 兼容尚未接入事件监听器的旧调用方和单元测试替身。
            conversationService.resolveWaitingApproval(entity.getAgentSessionId(), entity.getRunId(), notice);
        }
    }

    private AgentActionRequestEntity findOwnedForUpdate(String actionId, String ownerKey) {
        String owner = requireOwner(ownerKey);
        String safeActionId = requireText(actionId, "Agent 动作标识不能为空", 64);
        AgentActionRequestEntity entity = repository.findByActionIdForUpdate(safeActionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent 动作不存在"));
        if (!owner.equals(entity.getOwnerKey())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该 Agent 动作");
        }
        return entity;
    }

    /**
     * 兼容历史版本生成的动作草稿。旧记录可能是在动作边界净化上线前写入的，
     * 因此在返回、确认和重试前必须再次复核，不能只保护新建接口。
     */
    private boolean sanitizeStoredAction(AgentActionRequestEntity entity) {
        if (entity == null) {
            return false;
        }
        boolean unsafe = false;
        unsafe |= replaceIfChanged(entity.getDraftContent(),
            value -> entity.setDraftContent(value), MAX_CONTENT);
        unsafe |= replaceIfChanged(entity.getTargetTitle(),
            value -> entity.setTargetTitle(value), MAX_TITLE);
        unsafe |= replaceIfChanged(entity.getMemoryJobScope(),
            value -> entity.setMemoryJobScope(value), 64);
        unsafe |= replaceIfChanged(entity.getScheduleCompanyName(),
            value -> entity.setScheduleCompanyName(value), 255);
        unsafe |= replaceIfChanged(entity.getSchedulePosition(),
            value -> entity.setSchedulePosition(value), 255);
        unsafe |= replaceIfChanged(entity.getScheduleInterviewType(),
            value -> entity.setScheduleInterviewType(value), 64);
        unsafe |= replaceIfChanged(entity.getScheduleMeetingLink(),
            value -> entity.setScheduleMeetingLink(value), 1000);
        unsafe |= replaceIfChanged(entity.getScheduleInterviewer(),
            value -> entity.setScheduleInterviewer(value), 255);
        unsafe |= replaceIfChanged(entity.getScheduleNotes(),
            value -> entity.setScheduleNotes(value), 2000);
        return unsafe;
    }

    private boolean replaceIfChanged(
        String value, java.util.function.Consumer<String> setter, int maxLength
    ) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength);
        }
        boolean unsafe = promptSanitizer.detectInjectionAttempt(value);
        String safe = promptSanitizer.sanitizeForAction(normalized);
        if (java.util.Objects.equals(value, safe)) {
            return unsafe;
        }
        setter.accept(safe);
        return unsafe;
    }

    private AgentActionResponse safeResponse(AgentActionRequestEntity entity) {
        sanitizeStoredAction(entity);
        return AgentActionResponse.from(entity);
    }

    private void audit(AgentActionRequestEntity entity, String action, String result) {
        auditService.recordQuietly(
            entity.getOwnerKey(), entity.getInterviewSessionId(), entity.getAgentSessionId(),
            entity.getRunId(), action, "agent-action", entity.getActionId(), result
        );
    }

    private String successSummary(String toolId) {
        if (PRACTICE_QUESTIONS_TOOL.equals(toolId)) {
            return "练习题草稿已保存，原始面试资料未被修改。";
        }
        if (MEMORY_TOOL.equals(toolId)) {
            return "长期记忆已保存，可在记忆区编辑或删除。";
        }
        if (INTERVIEW_TITLE_TOOL.equals(toolId)) {
            return "面试记录名称已更新，问题、回答、评分和简历未被修改。";
        }
        if (TRAINING_TASK_TOOL.equals(toolId)) {
            return "训练任务已创建，可在 Agent 任务列表中开始或完成。原始面试资料未被修改。";
        }
        if (INTERVIEW_REPORT_EXPORT_TOOL.equals(toolId)) {
            return "面试报告已准备好，可以下载 PDF；面试内容未被修改。";
        }
        if (RESUME_EDITOR_SAVE_TOOL.equals(toolId)) {
            return "简历优化内容已保存为编辑版草稿，原始上传文件未被修改。";
        }
        if (RESUME_IMPORT_TOOL.equals(toolId)) {
            return "文件已导入简历管理，原始 Agent 上传文件已保留，简历分析任务已提交后台处理。";
        }
        if (INTERVIEW_SCHEDULE_CREATE_TOOL.equals(toolId)) {
            return "复习日程已创建，可在面试日程中继续调整；原始面试资料未被修改。";
        }
        if (INTERVIEW_SCHEDULE_CANCEL_TOOL.equals(toolId)) {
            return "面试日程已取消，历史记录已保留；原始面试资料未被修改。";
        }
        if (INTERVIEW_SCHEDULE_UPDATE_TOOL.equals(toolId)) {
            return "面试日程已更新；如果修改了时间，日程状态已标记为已改期。";
        }
        return "训练计划草稿已保存，原始面试资料未被修改。";
    }

    private String failureSummary(AgentActionRequestEntity entity) {
        if (executionAttempts(entity) < MAX_EXECUTION_ATTEMPTS) {
            return "第" + executionAttempts(entity) + "次执行失败，原始资料未被修改，可以重试。";
        }
        return "动作执行失败，原始资料未被修改，已达到最大重试次数，请重新生成草稿。";
    }

    private String recoverySummary(AgentActionRequestEntity entity) {
        if (executionAttempts(entity) >= MAX_EXECUTION_ATTEMPTS) {
            return "动作可能因应用中断未完成，已达到最大重试次数，请检查业务结果并重新生成草稿。";
        }
        return "动作可能因应用中断未完成，系统未自动重复执行；请检查业务结果后点击重试。";
    }

    private int executionAttempts(AgentActionRequestEntity entity) {
        return entity.getExecutionAttempts() == null ? 0 : entity.getExecutionAttempts();
    }

    private String requireOwner(String value) {
        return requireText(value, "Agent 身份已失效，请刷新页面后重试", MAX_OWNER_KEY);
    }

    private String requireText(String value, String message, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 动作参数过长");
        }
        return normalized;
    }

    private String normalizeContent(String value) {
        String normalized = requireText(value, "草稿不能为空", MAX_CONTENT);
        return promptSanitizer.sanitizeForAction(normalized);
    }

    private String sanitizeRequiredText(String value, String message, int maxLength) {
        return promptSanitizer.sanitizeForAction(requireText(value, message, maxLength));
    }

    private String sanitizeOptionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return promptSanitizer.sanitizeForAction(
            requireText(value, "Agent 动作参数过长", maxLength)
        );
    }

    private String normalizeMemoryCategory(String value) {
        String normalized = requireText(value, "记忆分类不能为空", 32);
        if (!"weakness".equals(normalized) && !"goal".equals(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "记忆分类不合法");
        }
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, "Agent 动作参数过长", maxLength);
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, "幂等键不合法", MAX_IDEMPOTENCY_KEY);
    }

    private String scheduleSummary(
        String companyName,
        String position,
        java.time.LocalDateTime interviewTime,
        String interviewType,
        String meetingLink,
        int roundNumber,
        String interviewer,
        String notes
    ) {
        StringBuilder summary = new StringBuilder()
            .append("主题：").append(companyName)
            .append("\n岗位：").append(position)
            .append("\n时间：").append(interviewTime)
            .append("\n轮次：").append(roundNumber);
        appendSummary(summary, "类型", interviewType);
        appendSummary(summary, "链接", meetingLink);
        appendSummary(summary, "联系人", interviewer);
        appendSummary(summary, "备注", notes);
        return summary.toString();
    }

    private String updateScheduleDraft(AgentActionRequestEntity entity, String content) {
        String normalized = normalizeContent(content);
        Map<String, String> values = new LinkedHashMap<>();
        normalized.lines().forEach(line -> {
            int separator = line.indexOf('：');
            if (separator < 0) {
                separator = line.indexOf(':');
            }
            if (separator > 0) {
                values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        });
        String company = requireScheduleValue(values, "主题", "公司或面试主题不能为空", 255);
        String position = requireScheduleValue(values, "岗位", "复习岗位不能为空", 255);
        String timeValue = requireScheduleValue(values, "时间", "面试时间不能为空", 64);
        LocalDateTime time;
        try {
            time = LocalDateTime.parse(timeValue);
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试时间格式应为 yyyy-MM-ddTHH:mm");
        }
        int round = 1;
        String roundValue = values.get("轮次");
        if (roundValue != null && !roundValue.isBlank()) {
            try {
                round = Integer.parseInt(roundValue);
            } catch (NumberFormatException error) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "轮次必须是数字");
            }
        }
        if (round < 1 || round > 20) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "轮次必须在1到20之间");
        }
        String type = sanitizeOptionalText(values.get("类型"), 64);
        String link = sanitizeOptionalText(values.get("链接"), 1000);
        String interviewer = sanitizeOptionalText(values.get("联系人"), 255);
        String notes = sanitizeOptionalText(values.get("备注"), 2000);
        entity.setScheduleCompanyName(company);
        entity.setSchedulePosition(position);
        entity.setScheduleInterviewTime(time);
        entity.setScheduleInterviewType(type);
        entity.setScheduleMeetingLink(link);
        entity.setScheduleRoundNumber(round);
        entity.setScheduleInterviewer(interviewer);
        entity.setScheduleNotes(notes);
        return scheduleSummary(company, position, time, type, link, round, interviewer, notes);
    }

    private String requireScheduleValue(
        Map<String, String> values, String key, String message, int maxLength
    ) {
        return sanitizeRequiredText(values.get(key), message, maxLength);
    }

    private void appendSummary(StringBuilder summary, String label, String value) {
        if (value != null && !value.isBlank()) {
            summary.append("\n").append(label).append("：")
                .append(value.replaceAll("\\s+", " ").trim());
        }
    }

    private String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法创建 Agent 动作幂等键", error);
        }
    }
}

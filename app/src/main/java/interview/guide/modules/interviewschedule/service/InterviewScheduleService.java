package interview.guide.modules.interviewschedule.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewScheduleService {

    private final InterviewScheduleRepository repository;

    private static final String[] COPYABLE_FIELDS = {
        "companyName", "position", "interviewTime", "interviewType",
        "meetingLink", "roundNumber", "interviewer", "notes"
    };

    @Transactional
    public InterviewScheduleDTO create(CreateInterviewRequest request) {
        return createForOwner(request, AuthUserContext.currentUserId().orElse(null));
    }

    /**
     * 由受控业务动作创建日程时使用显式 owner，避免依赖当前线程中的认证上下文。
     * Agent 只能通过已确认的动作进入该入口，普通日程接口行为保持不变。
     */
    @Transactional
    public InterviewScheduleDTO createForOwner(CreateInterviewRequest request, String ownerId) {
        return createForOwner(request, ownerId, null);
    }

    /** Agent 动作专用创建入口：同一动作重试时复用已经创建的日程。 */
    @Transactional
    public InterviewScheduleDTO createForOwner(
        CreateInterviewRequest request, String ownerId, String agentActionId
    ) {
        if (agentActionId != null && !agentActionId.isBlank() && ownerId != null && !ownerId.isBlank()) {
            var existing = repository.findByAgentActionIdAndOwnerId(agentActionId.trim(), ownerId.trim());
            if (existing.isPresent()) {
                return toDTO(existing.get());
            }
        }
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setStatus(InterviewStatus.PENDING);
        if (ownerId != null && !ownerId.isBlank()) {
            entity.setOwnerId(ownerId.trim());
        }
        if (agentActionId != null && !agentActionId.isBlank()) {
            entity.setAgentActionId(agentActionId.trim());
        }

        return toDTO(repository.save(entity));
    }

    @Transactional
    public InterviewScheduleDTO update(Long id, CreateInterviewRequest request) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        BeanUtils.copyProperties(request, entity, "id", "status");
        return toDTO(repository.save(entity));
    }

    /** Agent 修改/改期日程时使用显式 owner，不能依赖当前线程认证上下文。 */
    @Transactional
    public InterviewScheduleDTO updateForOwner(Long id, CreateInterviewRequest request, String ownerId) {
        if (id == null || ownerId == null || ownerId.isBlank()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在");
        }
        InterviewScheduleEntity entity = repository.findByIdAndOwnerId(id, ownerId.trim())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在或无权访问"
            ));
        if (entity.getStatus() == InterviewStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已完成的面试不能修改");
        }
        if (entity.getStatus() == InterviewStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已取消的面试不能修改，请重新创建日程");
        }
        boolean timeChanged = request != null && request.getInterviewTime() != null
            && !request.getInterviewTime().equals(entity.getInterviewTime());
        BeanUtils.copyProperties(request, entity, "id", "status", "ownerId", "agentActionId");
        if (timeChanged) {
            entity.setStatus(InterviewStatus.RESCHEDULED);
        }
        return toDTO(repository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(getByIdOrThrow(id));
    }

    @Transactional
    public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        entity.setStatus(status);
        return toDTO(repository.save(entity));
    }

    /** Agent 查询指定用户的单条日程，不能依赖当前线程中的认证上下文。 */
    @Transactional(readOnly = true)
    public InterviewScheduleDTO getForOwner(Long id, String ownerId) {
        if (id == null || ownerId == null || ownerId.isBlank()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在");
        }
        return repository.findByIdAndOwnerId(id, ownerId.trim())
            .map(this::toDTO)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在或无权访问"
            ));
    }

    /** Agent 取消日程时保留历史记录，只变更状态并再次校验 owner。 */
    @Transactional
    public InterviewScheduleDTO cancelForOwner(Long id, String ownerId) {
        InterviewScheduleEntity entity = repository.findByIdAndOwnerId(
            id, ownerId == null ? null : ownerId.trim()
        ).orElseThrow(() -> new BusinessException(
            ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在或无权访问"
        ));
        if (entity.getStatus() == InterviewStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已完成的面试不能取消");
        }
        if (entity.getStatus() != InterviewStatus.CANCELLED) {
            entity.setStatus(InterviewStatus.CANCELLED);
            entity = repository.save(entity);
        }
        return toDTO(entity);
    }

    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
        List<InterviewScheduleEntity> entities;
        String ownerId = AuthUserContext.currentUserId().orElse(null);

        if (ownerId != null && start != null && end != null) {
            entities = repository.findByOwnerIdAndInterviewTimeBetween(ownerId, start, end);
        } else if (ownerId != null && status != null) {
            entities = repository.findByOwnerIdAndStatus(ownerId, InterviewStatus.valueOf(status));
        } else if (ownerId != null) {
            entities = repository.findAllByOwnerIdOrderByInterviewTimeAsc(ownerId);
        } else if (start != null && end != null) {
            entities = repository.findByInterviewTimeBetween(start, end);
        } else if (status != null) {
            entities = repository.findByStatus(InterviewStatus.valueOf(status));
        } else {
            entities = repository.findAll();
        }

        return entities.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    /**
     * Agent 只读查询入口：显式按 owner 查询，不能依赖当前线程认证上下文。
     * 返回数量由调用方限制，避免把整张日程表注入模型上下文。
     */
    @Transactional(readOnly = true)
    public List<InterviewScheduleDTO> listForOwner(
        String ownerId, LocalDateTime start, LocalDateTime end, int limit
    ) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "面试日程身份已失效");
        }
        if ((start == null) != (end == null)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "日程查询需要同时提供开始和结束时间");
        }
        if (start != null && end.isBefore(start)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "日程查询时间范围无效");
        }
        int safeLimit = Math.max(1, Math.min(50, limit));
        PageRequest pageRequest = PageRequest.of(0, safeLimit);
        return (start == null
            ? repository.findAllByOwnerIdOrderByInterviewTimeAsc(ownerId.trim(), pageRequest)
            : repository.findByOwnerIdAndInterviewTimeBetween(ownerId.trim(), start, end, pageRequest))
            .map(this::toDTO)
            .getContent();
    }

    public InterviewScheduleDTO getById(Long id) {
        return toDTO(getByIdOrThrow(id));
    }

    private InterviewScheduleEntity getByIdOrThrow(Long id) {
        return AuthUserContext.currentUserId()
            .map(ownerId -> repository.findByIdAndOwnerId(id, ownerId))
            .orElseGet(() -> repository.findById(id))
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在: " + id));
    }

    private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}

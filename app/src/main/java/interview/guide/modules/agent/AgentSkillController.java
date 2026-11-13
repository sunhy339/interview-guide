package interview.guide.modules.agent;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.agent.model.AgentUserSkillResponse;
import interview.guide.modules.agent.service.AgentUserSkillService;
import interview.guide.modules.auth.AuthIdentityService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 用户 Skill 库管理接口。 */
@RestController
@RequestMapping("/api/agent/skills")
@RequiredArgsConstructor
public class AgentSkillController {

    private final AgentUserSkillService skillService;
    private final AuthIdentityService authIdentityService;

    @GetMapping
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<List<AgentUserSkillResponse>> list(
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(skillService.list(authIdentityService.requireIdentity(ownerKey)));
    }

    @GetMapping("/{skillId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<AgentUserSkillResponse> get(
        @PathVariable String skillId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(skillService.get(skillId, authIdentityService.requireIdentity(ownerKey)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<AgentUserSkillResponse> upload(
        @RequestPart("file") MultipartFile file,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(skillService.upload(file, authIdentityService.requireIdentity(ownerKey)));
    }

    @PostMapping("/{skillId}/approve")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentUserSkillResponse> approve(
        @PathVariable String skillId,
        @RequestParam(value = "confirmSecurity", defaultValue = "false") boolean confirmSecurity,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(skillService.approve(
            skillId, authIdentityService.requireIdentity(ownerKey), confirmSecurity
        ));
    }

    @PostMapping("/{skillId}/enable")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentUserSkillResponse> enable(
        @PathVariable String skillId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(skillService.enable(
            skillId, authIdentityService.requireIdentity(ownerKey)
        ));
    }

    @PostMapping("/{skillId}/disable")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentUserSkillResponse> disable(
        @PathVariable String skillId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(skillService.disable(
            skillId, authIdentityService.requireIdentity(ownerKey)
        ));
    }

    @PostMapping("/{skillId}/reject")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentUserSkillResponse> reject(
        @PathVariable String skillId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(skillService.reject(
            skillId, authIdentityService.requireIdentity(ownerKey)
        ));
    }

    @DeleteMapping("/{skillId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<Void> delete(
        @PathVariable String skillId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        skillService.delete(skillId, authIdentityService.requireIdentity(ownerKey));
        return Result.success(null);
    }
}

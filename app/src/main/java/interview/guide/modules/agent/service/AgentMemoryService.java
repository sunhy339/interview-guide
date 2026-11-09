package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentMemoryItem;
import interview.guide.modules.agent.model.AgentUserMemoryEntity;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.repository.AgentUserMemoryRepository;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 轻量长期记忆服务。
 *
 * <p>记忆只保存 Skill、薄弱点和训练目标等结构化摘要，不保存原始问答，
 * 既能跨面试复用，也避免把隐私对话无限累积。</p>
 */
@Slf4j
@Service
public class AgentMemoryService {
    private static final String ANONYMOUS_KEY = "anonymous";
    private static final int MAX_WEAKNESSES = 8;
    private static final int MAX_GOALS = 5;
    private static final int MAX_ITEM_CHARS = 160;
    private static final int MAX_PROVENANCE_CHARS = 128;
    private static final String CATEGORY_WEAKNESS = "weakness";
    private static final String CATEGORY_GOAL = "goal";
    private static final String DEFAULT_SOURCE = "interview-report";

    private final AgentUserMemoryRepository repository;
    private final ObjectMapper objectMapper;
    private final AgentMemoryProperties properties;
    private final AgentAuditService auditService;
    private final PromptSanitizer promptSanitizer;

    /** 保留无 Spring 容器调用方式，便于单元测试和兼容旧调用方。 */
    public AgentMemoryService(
        AgentUserMemoryRepository repository,
        ObjectMapper objectMapper
    ) {
        this(repository, objectMapper, new AgentMemoryProperties(), null,
            new PromptSanitizer(new LlmProviderProperties()));
    }

    public AgentMemoryService(
        AgentUserMemoryRepository repository,
        ObjectMapper objectMapper,
        AgentMemoryProperties properties
    ) {
        this(repository, objectMapper, properties, null,
            new PromptSanitizer(new LlmProviderProperties()));
    }

    /** 兼容测试和迁移期内部调用方；安全净化默认仍然启用。 */
    public AgentMemoryService(
        AgentUserMemoryRepository repository,
        ObjectMapper objectMapper,
        AgentMemoryProperties properties,
        AgentAuditService auditService
    ) {
        this(repository, objectMapper, properties, auditService,
            new PromptSanitizer(new LlmProviderProperties()));
    }

    @Autowired
    public AgentMemoryService(
        AgentUserMemoryRepository repository,
        ObjectMapper objectMapper,
        AgentMemoryProperties properties,
        AgentAuditService auditService,
        PromptSanitizer promptSanitizer
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties == null ? new AgentMemoryProperties() : properties;
        this.auditService = auditService;
        this.promptSanitizer = promptSanitizer == null
            ? new PromptSanitizer(new LlmProviderProperties()) : promptSanitizer;
    }

    public String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            return ANONYMOUS_KEY;
        }
        String normalized = value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (normalized.isBlank()) {
            return ANONYMOUS_KEY;
        }
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    public AgentMemorySnapshot load(String memoryKey) {
        String key = normalizeKey(memoryKey);
        return repository.findByMemoryKey(key)
            .map(this::deserialize)
            .map(this::removeInactive)
            .orElseGet(() -> AgentMemorySnapshot.empty(key));
    }

    /**
     * 读取完整快照，仅供保存时保留已撤销/已过期条目的治理痕迹；不得直接注入 Agent。
     */
    private AgentMemorySnapshot loadStored(String memoryKey) {
        String key = normalizeKey(memoryKey);
        return repository.findByMemoryKey(key)
            .map(this::deserialize)
            .orElseGet(() -> AgentMemorySnapshot.empty(key));
    }

    @Transactional
    public AgentMemorySnapshot remember(
        String memoryKey,
        String skillId,
        List<String> weaknesses,
        String goal
    ) {
        String key = normalizeKey(memoryKey);
        AgentMemorySnapshot previous = load(key);
        String nextSkill = cleanMetadata(skillId, 64);
        if (nextSkill == null) {
            nextSkill = previous.lastSkillId();
        }
        List<AgentMemoryItem> items = mergeItems(
            previous.items(), weaknesses, CATEGORY_WEAKNESS, DEFAULT_SOURCE,
            null, null, "legacy", "legacy-summary", nextSkill, MAX_WEAKNESSES
        );
        items = mergeItems(
            items, goal == null ? List.of() : List.of(goal), CATEGORY_GOAL,
            DEFAULT_SOURCE, null, null, "legacy", "legacy-summary", nextSkill, MAX_GOALS
        );
        AgentMemorySnapshot snapshot = saveSnapshot(key, nextSkill, items);
        audit(key, "MEMORY_REMEMBERED", "memory", key, "success");
        return snapshot;
    }

    /** 由已确认的 Agent 动作写入一条指定分类的记忆，保留当前最近 Skill。 */
    @Transactional
    public AgentMemorySnapshot rememberItem(
        String memoryKey, String category, String content, String jobScope
    ) {
        return rememberItemWithProvenance(
            memoryKey, category, content, jobScope,
            null, null, "user-confirmed", "explicit-memory-action"
        );
    }

    /**
     * 由已确认的 Agent 动作写入一条带来源链的结构化记忆。
     *
     * <p>来源信息只作为治理元数据保存，不参与记忆正文和模型上下文；sourceId
     * 通常是当前业务资源 ID，不能由模型自行传入。</p>
     */
    @Transactional
    public AgentMemorySnapshot rememberItemWithProvenance(
        String memoryKey,
        String category,
        String content,
        String jobScope,
        String sourceId,
        String version,
        String authority,
        String selectedReason
    ) {
        String key = normalizeKey(memoryKey);
        String normalizedCategory = normalizeCategory(category);
        if (normalizedCategory == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "记忆分类不合法");
        }
        String normalizedContent = cleanContent(content);
        if (normalizedContent == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "记忆内容不能为空");
        }
        AgentMemorySnapshot previous = load(key);
        int max = CATEGORY_GOAL.equals(normalizedCategory) ? MAX_GOALS : MAX_WEAKNESSES;
        List<AgentMemoryItem> items = mergeItems(
            previous.items(), List.of(normalizedContent), normalizedCategory,
            "agent-action", cleanMetadata(sourceId, MAX_PROVENANCE_CHARS),
            cleanMetadata(version, MAX_PROVENANCE_CHARS),
            normalizeAuthority(authority), cleanMetadata(selectedReason, MAX_PROVENANCE_CHARS),
            cleanMetadata(jobScope, 64), max
        );
        AgentMemorySnapshot snapshot = saveSnapshot(key, previous.lastSkillId(), items);
        audit(key, "MEMORY_REMEMBERED", "memory", key, "success");
        return snapshot;
    }

    /** 编辑一条结构化记忆，保留其分类和来源，只允许修改内容和适用方向。 */
    @Transactional
    public AgentMemorySnapshot updateItem(
        String memoryKey, String itemId, String content, String jobScope
    ) {
        String key = normalizeKey(memoryKey);
        String normalizedId = clean(itemId, 64);
        String normalizedContent = cleanContent(content);
        if (normalizedId == null || normalizedContent == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "记忆标识和内容不能为空");
        }
        AgentMemorySnapshot previous = load(key);
        List<AgentMemoryItem> items = new ArrayList<>(previous.items());
        int index = indexOf(items, normalizedId);
        if (index < 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "长期记忆不存在");
        }
        AgentMemoryItem current = items.get(index);
        String normalizedScope = jobScope == null ? current.jobScope() : cleanMetadata(jobScope, 64);
        items.set(index, new AgentMemoryItem(
            current.id(), current.category(), normalizedContent, current.source(),
            current.sourceId(), current.version(), current.authority(), current.selectedReason(),
            normalizedScope,
            current.confidence(), LocalDateTime.now(), expiresAt(LocalDateTime.now()), null
        ));
        AgentMemorySnapshot snapshot = saveSnapshot(key, previous.lastSkillId(), items);
        audit(key, "MEMORY_UPDATED", "memory-item", normalizedId, "success");
        return snapshot;
    }

    /** 删除一条结构化记忆；删除后会同步更新兼容旧接口的摘要字段。 */
    @Transactional
    public AgentMemorySnapshot deleteItem(String memoryKey, String itemId) {
        String key = normalizeKey(memoryKey);
        String normalizedId = clean(itemId, 64);
        AgentMemorySnapshot previous = load(key);
        List<AgentMemoryItem> items = new ArrayList<>(previous.items());
        int index = indexOf(items, normalizedId);
        if (index < 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "长期记忆不存在");
        }
        AgentMemoryItem current = items.get(index);
        items.set(index, new AgentMemoryItem(
            current.id(), current.category(), current.content(), current.source(),
            current.sourceId(), current.version(), current.authority(), current.selectedReason(),
            current.jobScope(),
            current.confidence(), current.updatedAt(), current.expiresAt(), LocalDateTime.now()
        ));
        AgentMemorySnapshot snapshot = saveSnapshot(key, previous.lastSkillId(), items);
        audit(key, "MEMORY_REVOKED", "memory-item", normalizedId, "success");
        return snapshot;
    }

    @Transactional
    public void clear(String memoryKey) {
        String key = normalizeKey(memoryKey);
        repository.deleteByMemoryKey(key);
        audit(key, "MEMORY_CLEARED", "memory", key, "success");
    }

    private AgentMemorySnapshot deserialize(AgentUserMemoryEntity entity) {
        try {
            MemoryPayload payload = objectMapper.readValue(entity.getMemoryJson(), MemoryPayload.class);
            List<AgentMemoryItem> items = normalizeItems(
                payload == null ? null : payload.items(),
                payload == null ? null : payload.weaknesses(),
                payload == null ? null : payload.recentGoals(),
                entity.getUpdatedAt()
            );
            return new AgentMemorySnapshot(
                entity.getMemoryKey(), payload == null ? null : cleanMetadata(payload.lastSkillId(), 64),
                valuesForCategory(items, CATEGORY_WEAKNESS, MAX_WEAKNESSES),
                valuesForCategory(items, CATEGORY_GOAL, MAX_GOALS), entity.getUpdatedAt(), items
            );
        } catch (RuntimeException error) {
            log.warn("Agent 长期记忆读取失败: memoryKey={}", entity.getMemoryKey(), error);
            return AgentMemorySnapshot.empty(entity.getMemoryKey());
        }
    }

    private List<String> merge(List<String> oldValues, List<String> newValues, int max) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (newValues != null) {
            newValues.forEach(value -> addIfPresent(values, value));
        }
        if (oldValues != null) {
            oldValues.forEach(value -> addIfPresent(values, value));
        }
        return values.stream().limit(max).toList();
    }

    private List<AgentMemoryItem> mergeItems(
        List<AgentMemoryItem> oldItems,
        List<String> newValues,
        String category,
        String source,
        String sourceId,
        String version,
        String authority,
        String selectedReason,
        String jobScope,
        int max
    ) {
        List<AgentMemoryItem> result = new ArrayList<>();
        List<AgentMemoryItem> categoryItems = new ArrayList<>();
        if (oldItems != null) {
            oldItems.stream()
                .filter(item -> item != null)
                .forEach(item -> {
                    if (category.equals(item.category())) {
                        categoryItems.add(item);
                    } else {
                        result.add(item);
                    }
                });
        }
        if (newValues != null) {
            newValues.forEach(value -> {
                String normalized = cleanContent(value);
                if (normalized == null || categoryItems.stream().anyMatch(
                    item -> normalized.equals(item.content())
                )) {
                    return;
                }
                categoryItems.add(0, new AgentMemoryItem(
                    UUID.randomUUID().toString(), category, normalized, source,
                    sourceId, version, authority, selectedReason,
                    cleanMetadata(jobScope, 64), 0.7d, LocalDateTime.now(),
                    expiresAt(LocalDateTime.now()), null
                ));
            });
        }
        result.addAll(categoryItems.stream().limit(max).toList());
        return List.copyOf(result);
    }

    private List<AgentMemoryItem> normalizeItems(
        List<MemoryItemPayload> payloadItems,
        List<String> legacyWeaknesses,
        List<String> legacyGoals,
        LocalDateTime fallbackUpdatedAt
    ) {
        List<AgentMemoryItem> result = new ArrayList<>();
        if (payloadItems != null) {
            int index = 0;
            for (MemoryItemPayload item : payloadItems) {
                if (item == null) {
                    continue;
                }
                String content = cleanContent(item.content());
                String category = normalizeCategory(item.category());
                if (content == null || category == null || result.size() >= MAX_WEAKNESSES + MAX_GOALS) {
                    continue;
                }
                String id = clean(item.id(), 64);
                if (id == null) {
                    id = category + "-" + index;
                }
                result.add(new AgentMemoryItem(
                    id, category, content, cleanMetadata(item.source(), 64),
                    cleanMetadata(item.sourceId(), MAX_PROVENANCE_CHARS),
                    cleanMetadata(item.version(), MAX_PROVENANCE_CHARS),
                    normalizeAuthority(item.authority()),
                    cleanMetadata(item.selectedReason(), MAX_PROVENANCE_CHARS),
                    cleanMetadata(item.jobScope(), 64),
                    normalizeConfidence(item.confidence()),
                    item.updatedAt() == null ? fallbackUpdatedAt : item.updatedAt(),
                    item.expiresAt() == null
                        ? expiresAt(item.updatedAt() == null ? fallbackUpdatedAt : item.updatedAt())
                        : item.expiresAt(),
                    item.revokedAt()
                ));
                index++;
            }
        }
        if (result.isEmpty()) {
            addLegacyItems(result, legacyWeaknesses, CATEGORY_WEAKNESS, MAX_WEAKNESSES, fallbackUpdatedAt);
            addLegacyItems(result, legacyGoals, CATEGORY_GOAL, MAX_GOALS, fallbackUpdatedAt);
        }
        return List.copyOf(result);
    }

    private void addLegacyItems(
        List<AgentMemoryItem> result,
        List<String> values,
        String category,
        int max,
        LocalDateTime updatedAt
    ) {
        List<String> cleaned = cleanList(values, max);
        for (int index = 0; index < cleaned.size(); index++) {
            result.add(new AgentMemoryItem(
                category + "-" + index, category, cleaned.get(index), "legacy-summary",
                null, null, "legacy", "legacy-summary", null,
                0.5d, updatedAt, expiresAt(updatedAt), null
            ));
        }
    }

    private List<String> valuesForCategory(List<AgentMemoryItem> items, String category, int max) {
        return items == null ? List.of() : items.stream()
            .filter(item -> item != null && category.equals(item.category()))
            .map(AgentMemoryItem::content)
            .map(this::cleanContent)
            .filter(value -> value != null)
            .distinct()
            .limit(max)
            .toList();
    }

    private int indexOf(List<AgentMemoryItem> items, String itemId) {
        if (itemId == null || items == null) {
            return -1;
        }
        for (int index = 0; index < items.size(); index++) {
            if (itemId.equals(items.get(index).id())) {
                return index;
            }
        }
        return -1;
    }

    private AgentMemorySnapshot saveSnapshot(
        String key, String lastSkillId, List<AgentMemoryItem> items
    ) {
        LocalDateTime now = LocalDateTime.now();
        List<AgentMemoryItem> providedItems = items == null ? List.of() : items.stream()
            .filter(item -> item != null)
            .toList();
        List<AgentMemoryItem> storedItems = mergeStoredItems(key, providedItems);
        List<AgentMemoryItem> safeItems = storedItems.stream()
            .filter(item -> !isInactive(item, now))
            .toList();
        AgentMemorySnapshot next = new AgentMemorySnapshot(
            key,
            cleanMetadata(lastSkillId, 64),
            valuesForCategory(safeItems, CATEGORY_WEAKNESS, MAX_WEAKNESSES),
            valuesForCategory(safeItems, CATEGORY_GOAL, MAX_GOALS),
            LocalDateTime.now(),
            safeItems
        );
        try {
            AgentUserMemoryEntity entity = repository.findByMemoryKey(key)
                .orElseGet(() -> AgentUserMemoryEntity.builder().memoryKey(key).build());
            entity.setMemoryJson(objectMapper.writeValueAsString(new MemoryPayload(
                next.lastSkillId(), next.weaknesses(), next.recentGoals(),
                storedItems.stream().map(MemoryItemPayload::from).toList()
            )));
            repository.save(entity);
        } catch (RuntimeException error) {
            log.warn("Agent 长期记忆保存失败: memoryKey={}", key, error);
        }
        return next;
    }

    private String normalizeCategory(String category) {
        if (CATEGORY_WEAKNESS.equals(category) || CATEGORY_GOAL.equals(category)) {
            return category;
        }
        return null;
    }

    private String normalizeAuthority(String authority) {
        String normalized = cleanMetadata(authority, MAX_PROVENANCE_CHARS);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "user-confirmed", "system-derived", "legacy" -> normalized;
            default -> "unclassified";
        };
    }

    private void addIfPresent(LinkedHashSet<String> values, String value) {
        String clean = cleanContent(value);
        if (clean != null) {
            values.add(clean);
        }
    }

    private List<String> cleanList(List<String> values, int max) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            values.forEach(value -> {
                String clean = cleanContent(value);
                if (clean != null && !result.contains(clean) && result.size() < max) {
                    result.add(clean);
                }
            });
        }
        return List.copyOf(result);
    }

    private String clean(String value, int max) {
        if (value == null) {
            return null;
        }
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return null;
        }
        return clean.substring(0, Math.min(max, clean.length()));
    }

    /** 记忆是跨轮复用的用户数据，不能把提示注入原样持久化后再送回模型。 */
    private String cleanContent(String value) {
        if (value == null) {
            return null;
        }
        return clean(promptSanitizer.sanitizeForContext(value), MAX_ITEM_CHARS);
    }

    /** 旧版本持久化的记忆元数据也可能进入筛选、展示和 Prompt，读取时必须重新净化。 */
    private String cleanMetadata(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return clean(promptSanitizer.sanitizeForContext(value), maxLength);
    }

    private record MemoryPayload(
        String lastSkillId,
        List<String> weaknesses,
        List<String> recentGoals,
        List<MemoryItemPayload> items
    ) {
    }

    private record MemoryItemPayload(
        String id,
        String category,
        String content,
        String source,
        String sourceId,
        String version,
        String authority,
        String selectedReason,
        String jobScope,
        Double confidence,
        LocalDateTime updatedAt,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt
    ) {
        private static MemoryItemPayload from(AgentMemoryItem item) {
            return new MemoryItemPayload(
                item.id(), item.category(), item.content(), item.source(), item.sourceId(),
                item.version(), item.authority(), item.selectedReason(), item.jobScope(),
                item.confidence(), item.updatedAt(), item.expiresAt(), item.revokedAt()
            );
        }
    }

    private double normalizeConfidence(Double confidence) {
        if (confidence == null || confidence.isNaN() || confidence.isInfinite()) {
            return 0.5d;
        }
        return Math.max(0d, Math.min(1d, confidence));
    }

    private AgentMemorySnapshot removeInactive(AgentMemorySnapshot snapshot) {
        if (snapshot == null || snapshot.items().isEmpty()) {
            return snapshot;
        }
        LocalDateTime now = LocalDateTime.now();
        List<AgentMemoryItem> activeItems = snapshot.items().stream()
            .filter(item -> !isInactive(item, now))
            .toList();
        if (activeItems.size() == snapshot.items().size()) {
            return snapshot;
        }
        return new AgentMemorySnapshot(
            snapshot.memoryKey(), activeItems.isEmpty() ? null : snapshot.lastSkillId(),
            valuesForCategory(activeItems, CATEGORY_WEAKNESS, MAX_WEAKNESSES),
            valuesForCategory(activeItems, CATEGORY_GOAL, MAX_GOALS),
            snapshot.updatedAt(), activeItems
        );
    }

    private boolean isInactive(AgentMemoryItem item, LocalDateTime now) {
        return item.revokedAt() != null
            || (properties.isExpirationEnabled()
                && item.expiresAt() != null
                && !item.expiresAt().isAfter(now));
    }

    /**
     * 以当前写入结果覆盖同 id 条目，同时保留历史撤销/过期条目。
     * 这只用于治理和审计，返回给 Agent 的快照仍然只包含 active 条目。
     */
    private List<AgentMemoryItem> mergeStoredItems(
        String key, List<AgentMemoryItem> providedItems
    ) {
        List<AgentMemoryItem> storedItems = new ArrayList<>(loadStored(key).items());
        for (AgentMemoryItem provided : providedItems) {
            int index = indexOf(storedItems, provided.id());
            if (index >= 0) {
                storedItems.set(index, provided);
            } else {
                storedItems.add(provided);
            }
        }
        return List.copyOf(storedItems);
    }

    private LocalDateTime expiresAt(LocalDateTime updatedAt) {
        if (!properties.isExpirationEnabled() || updatedAt == null) {
            return null;
        }
        return updatedAt.plus(properties.getRetentionDays(), ChronoUnit.DAYS);
    }

    private void audit(
        String ownerKey, String action, String resourceType, String resourceId, String result
    ) {
        if (auditService != null) {
            auditService.recordQuietly(
                ownerKey, null, null, null, action, resourceType, resourceId, result
            );
        }
    }
}

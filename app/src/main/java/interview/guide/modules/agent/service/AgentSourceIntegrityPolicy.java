package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 校验本轮 Agent 来源摘要的结构完整性。
 *
 * <p>这里只检查来源元数据的一致性，不判断来源正文是否真的支持回答中的每个事实。
 * 结果只用于低敏观测和质量评测，不能作为对用户事实正确性的直接结论。</p>
 */
public final class AgentSourceIntegrityPolicy {

    private AgentSourceIntegrityPolicy() {
    }

    public static Assessment assess(List<AgentSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return new Assessment(Status.NOT_APPLICABLE, 0, Set.of(), Set.of(), Set.of(), 0);
        }

        Map<String, Integer> referenceCounts = new LinkedHashMap<>();
        Map<String, Set<String>> versionsBySourceId = new LinkedHashMap<>();
        Map<String, Set<String>> authoritiesBySourceId = new LinkedHashMap<>();
        Set<String> legacyFields = new LinkedHashSet<>();

        for (AgentSource source : sources) {
            if (source == null) {
                legacyFields.add("null-source");
                continue;
            }
            String reference = normalize(source.reference());
            if (!reference.isBlank()) {
                referenceCounts.merge(reference.toUpperCase(Locale.ROOT), 1, Integer::sum);
            }

            String sourceId = normalize(source.sourceId());
            String version = normalize(source.version());
            String authority = normalize(source.authority());
            if (sourceId.isBlank() || version.isBlank() || authority.isBlank()) {
                legacyFields.add(legacyField(sourceId, version, authority));
            }
            if (!sourceId.isBlank() && !version.isBlank()) {
                versionsBySourceId.computeIfAbsent(sourceId, ignored -> new LinkedHashSet<>()).add(version);
            }
            if (!sourceId.isBlank() && !authority.isBlank()) {
                authoritiesBySourceId.computeIfAbsent(sourceId, ignored -> new LinkedHashSet<>())
                    .add(authority);
            }
        }

        Set<String> duplicateReferences = referenceCounts.entrySet().stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> conflictingVersions = conflictingKeys(versionsBySourceId);
        Set<String> conflictingAuthorities = conflictingKeys(authoritiesBySourceId);

        Status status;
        if (!duplicateReferences.isEmpty()) {
            status = Status.DUPLICATE_REFERENCE;
        } else if (!conflictingVersions.isEmpty()) {
            status = Status.SOURCE_VERSION_CONFLICT;
        } else if (!conflictingAuthorities.isEmpty()) {
            status = Status.SOURCE_AUTHORITY_CONFLICT;
        } else if (!legacyFields.isEmpty()) {
            status = Status.LEGACY_METADATA;
        } else {
            status = Status.OK;
        }
        return new Assessment(
            status,
            sources.size(),
            Set.copyOf(duplicateReferences),
            Set.copyOf(conflictingVersions),
            Set.copyOf(conflictingAuthorities),
            legacyFields.size()
        );
    }

    private static Set<String> conflictingKeys(Map<String, Set<String>> valuesByKey) {
        return valuesByKey.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String legacyField(String sourceId, String version, String authority) {
        List<String> missing = new ArrayList<>();
        if (sourceId.isBlank()) missing.add("source-id");
        if (version.isBlank()) missing.add("version");
        if (authority.isBlank()) missing.add("authority");
        return String.join(",", missing);
    }

    public enum Status {
        NOT_APPLICABLE("not-applicable"),
        OK("ok"),
        LEGACY_METADATA("legacy-metadata"),
        DUPLICATE_REFERENCE("duplicate-reference"),
        SOURCE_VERSION_CONFLICT("source-version-conflict"),
        SOURCE_AUTHORITY_CONFLICT("source-authority-conflict");

        private final String auditValue;

        Status(String auditValue) {
            this.auditValue = auditValue;
        }

        public String auditValue() {
            return auditValue;
        }
    }

    public record Assessment(
        Status status,
        int sourceCount,
        Set<String> duplicateReferences,
        Set<String> conflictingSourceIds,
        Set<String> conflictingAuthoritySourceIds,
        int legacyMetadataCount
    ) {
        public Assessment {
            status = status == null ? Status.NOT_APPLICABLE : status;
            sourceCount = Math.max(0, sourceCount);
            duplicateReferences = duplicateReferences == null ? Set.of() : Set.copyOf(duplicateReferences);
            conflictingSourceIds = conflictingSourceIds == null ? Set.of() : Set.copyOf(conflictingSourceIds);
            conflictingAuthoritySourceIds = conflictingAuthoritySourceIds == null
                ? Set.of() : Set.copyOf(conflictingAuthoritySourceIds);
            legacyMetadataCount = Math.max(0, legacyMetadataCount);
        }

        /** 只返回稳定状态标签，避免把来源标识写进审计结果。 */
        public String auditValue() {
            return status.auditValue();
        }
    }
}

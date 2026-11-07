package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentHistoryMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 模型调用前的输入预算策略。
 *
 * <p>项目当前不绑定某一家 Provider 的 tokenizer，因此这里使用偏保守的字符估算，
 * 只用于提前裁剪，不把估算值当成账单 Token。系统 Prompt 和稳定安全前缀不在这里修改；
 * 优先保留当前问题和面试报告，再依次压缩历史、RAG、Skill、摘要和 Loop 诊断信息。</p>
 */
public final class AgentPromptBudgetPolicy {

    private static final int MIN_BUDGET_TOKENS = 4_000;
    private static final int PROMPT_OVERHEAD_TOKENS = 1_200;
    private static final int COMPACT_MARKER_CHARS = 24;

    private AgentPromptBudgetPolicy() {
    }

    public static BudgetedPrompt fit(
        String report,
        String skill,
        String memory,
        String rag,
        String sessionSummary,
        String plan,
        String loopTrace,
        String currentMessage,
        List<AgentHistoryMessage> history,
        int maxInputTokens
    ) {
        int budget = Math.max(MIN_BUDGET_TOKENS, maxInputTokens);
        String[] sections = {
            safe(report), safe(skill), safe(memory), safe(rag), safe(sessionSummary),
            safe(plan), safe(loopTrace), safe(currentMessage)
        };

        int fixedTokens = fixedTokens(sections);
        int availableFixedTokens = Math.max(0, budget - PROMPT_OVERHEAD_TOKENS);
        if (fixedTokens > availableFixedTokens) {
            // 低优先级资料先压缩；报告和当前问题最后才压缩，避免普通问答或复盘失去主线。
            int overflow = fixedTokens - availableFixedTokens;
            int[] order = {3, 1, 4, 2, 6, 5, 0, 7};
            for (int index : order) {
                if (overflow <= 0) break;
                int currentTokens = estimateTokens(sections[index]);
                int minimumTokens = index == 7 ? 128 : 32;
                int targetTokens = Math.max(minimumTokens, currentTokens - overflow);
                if (targetTokens >= currentTokens) continue;
                sections[index] = compactToTokens(sections[index], targetTokens);
                int actualTokens = estimateTokens(sections[index]);
                overflow -= Math.max(0, currentTokens - actualTokens);
            }
        }

        int historyBudgetTokens = Math.max(
            0, budget - PROMPT_OVERHEAD_TOKENS - fixedTokens(sections)
        );
        int historyChars = tokensToChars(historyBudgetTokens);
        int messageChars = Math.min(AgentHistoryPolicy.MAX_MESSAGE_CHARS, Math.max(0, historyChars));
        List<AgentHistoryMessage> normalizedHistory = AgentHistoryPolicy.normalize(
            history, historyChars, messageChars
        );
        int estimated = PROMPT_OVERHEAD_TOKENS + fixedTokens(sections) + estimateHistory(normalizedHistory);
        return new BudgetedPrompt(
            sections[0], sections[1], sections[2], sections[3], sections[4],
            sections[5], sections[6], sections[7], normalizedHistory, estimated
        );
    }

    /** 仅供预算裁剪使用的保守估算：中文/非 ASCII 字符按 1 Token，ASCII 单词按约 4 字符。 */
    public static int estimateTokens(String value) {
        if (value == null || value.isEmpty()) return 0;
        int asciiUnits = 0;
        int nonAsciiTokens = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint <= 0x7F && (Character.isLetterOrDigit(codePoint)
                || Character.isWhitespace(codePoint))) {
                asciiUnits++;
            } else if (codePoint <= 0x7F) {
                // ASCII 标点和结构符号通常不能与相邻文本完全合并，按半个字符计。
                asciiUnits += 2;
            } else {
                nonAsciiTokens++;
            }
        }
        return Math.max(1, (asciiUnits + 3) / 4 + nonAsciiTokens);
    }

    private static int fixedTokens(String[] sections) {
        int total = 0;
        for (String section : sections) {
            total += estimateTokens(section);
        }
        return total;
    }

    private static int estimateHistory(List<AgentHistoryMessage> history) {
        if (history == null) return 0;
        return history.stream()
            .mapToInt(message -> estimateTokens(message == null ? null : message.content()) + 4)
            .sum();
    }

    private static String compactToTokens(String value, int maxTokens) {
        if (value == null || value.isEmpty()) return "";
        int maxChars = tokensToChars(maxTokens);
        if (value.length() <= maxChars) return value;
        if (maxChars <= COMPACT_MARKER_CHARS + 2) {
            return value.substring(0, Math.max(0, maxChars));
        }
        String marker = "\n[上下文预算已压缩]\n";
        int available = Math.max(2, maxChars - marker.length());
        int head = (available + 1) / 2;
        int tail = available - head;
        return value.substring(0, head) + marker + value.substring(value.length() - tail);
    }

    private static int tokensToChars(int tokens) {
        if (tokens <= 0) return 0;
        // 按最坏情况一字符一 Token 截断，避免中文上下文被低估后再次触发 Provider 超限。
        return Math.max(1, tokens);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record BudgetedPrompt(
        String report,
        String skill,
        String memory,
        String rag,
        String sessionSummary,
        String plan,
        String loopTrace,
        String currentMessage,
        List<AgentHistoryMessage> history,
        int estimatedInputTokens
    ) {
        public BudgetedPrompt {
            history = history == null ? List.of() : List.copyOf(new ArrayList<>(history));
        }
    }
}

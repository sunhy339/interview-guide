package interview.guide.common.ai;

import interview.guide.common.config.LlmProviderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Prompt 注入净化工具。
 * <p>
 * 普通用户输入保留配置开关；进入 Agent 上下文的资料使用强制净化和边界包裹，
 * 防止上传文件、RAG 结果或历史消息伪造角色、系统提示和边界标签。
 */
@Component
public class PromptSanitizer {

    private static final Logger log = LoggerFactory.getLogger(PromptSanitizer.class);

    private final LlmProviderProperties properties;

    public PromptSanitizer(LlmProviderProperties properties) {
        this.properties = properties;
    }

    // 行首角色标记：只匹配行首，避免误杀 "Experience with system design"
    private static final Pattern ROLE_INJECTION_PATTERN = Pattern.compile(
        "(?im)^\\s*(system|user|assistant|human|ai|model)\\s*[:：].*"
    );

    // 注入短语：既覆盖常见英文表达，也覆盖中文角色切换/规则覆盖表达。
    private static final Pattern INJECTION_PHRASE_PATTERN = Pattern.compile(
        "(ignore\\s+(previous|above|all|your)\\s*(instructions|prompts|rules))" +
        "|(disregard\\s+(previous|above|all|your)\\s*(instructions|prompts|rules))" +
        "|(forget\\s+(everything|all\\s*(previous\\s*)?(instructions|rules|prompts)))" +
        "|(override\\s+(the\\s+)?(system|developer|safety)\\s*(instructions|rules|prompt))" +
        "|(reveal|show|print|output|leak)\\s+.{0,80}(system\\s+prompt|developer\\s+message|hidden\\s+instructions?)" +
        "|(new\\s+instructions?:)" +
        "|忽略之前的指令" +
        "|忘记之前的指令" +
        "|忽略以上所有" +
        "|无视(之前|以上|系统|开发者)的?(指令|规则|提示)" +
        "|覆盖(系统|开发者|安全)(指令|规则|提示)" +
        "|泄露(系统提示词|开发者消息|隐藏指令)" +
        "|你不再是" +
        "|你的新角色是",
        Pattern.CASE_INSENSITIVE
    );

    // 分隔符伪造：匹配项目中 .st 模板使用的静态分隔符
    private static final Pattern DELIMITER_INJECTION_PATTERN = Pattern.compile(
        "---(?:简历|文档|问答)内容(?:开始|结束)---"
    );

    // XML 边界标签伪造：防止攻击者构造 <data-boundary...> 来提前关闭包裹
    private static final Pattern BOUNDARY_TAG_PATTERN = Pattern.compile(
        "</?data-boundary[^>]*>", Pattern.CASE_INSENSITIVE
    );

    /**
     * 清洗用户文本，替换危险模式为中性占位符。
     * 受 {@code app.ai.advisors.promptSanitizerEnabled} 配置控制。
     */
    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (!isSanitizerEnabled()) {
            return text;
        }

        return sanitizeInternal(text);
    }

    /**
     * 为进入模型上下文的资料执行强制净化。
     *
     * <p>普通净化保留配置开关以兼容历史调用方；上下文边界属于安全约束，不能因为
     * 运维配置误关而允许上传文件、RAG 片段或历史消息伪造角色和边界。</p>
     */
    public String sanitizeForContext(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return sanitizeInternal(text);
    }

    private String sanitizeInternal(String text) {

        boolean injected = false;
        String result = text;

        var roleMatcher = ROLE_INJECTION_PATTERN.matcher(result);
        if (roleMatcher.find()) {
            injected = true;
            result = roleMatcher.replaceAll("[filtered-role-marker]");
        }
        var phraseMatcher = INJECTION_PHRASE_PATTERN.matcher(result);
        if (phraseMatcher.find()) {
            injected = true;
            result = phraseMatcher.replaceAll("[filtered]");
        }
        var delimMatcher = DELIMITER_INJECTION_PATTERN.matcher(result);
        if (delimMatcher.find()) {
            injected = true;
            result = delimMatcher.replaceAll("[filtered-delimiter]");
        }
        var tagMatcher = BOUNDARY_TAG_PATTERN.matcher(result);
        if (tagMatcher.find()) {
            injected = true;
            result = tagMatcher.replaceAll("[filtered-boundary-tag]");
        }

        if (injected) {
            log.warn("检测到潜在 Prompt 注入尝试，文本长度: {}", text.length());
        }
        return result;
    }

    /**
     * 用不可预测的分隔符包裹用户文本。
     * 格式：{@code <data-boundary-{uuid片段}-{label}> ... </data-boundary-{uuid片段}-{label}>}
     * UUID 片段使攻击者无法提前构造伪造分隔符。
     */
    public String wrapWithDelimiters(String label, String text) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String safeLabel = safeLabel(label);
        String openTag = "<data-boundary-" + id + "-" + safeLabel + ">";
        String closeTag = "</data-boundary-" + id + "-" + safeLabel + ">";
        String safeText = sanitizeForContext(text);
        return openTag + "\n" + (safeText == null ? "" : safeText) + "\n" + closeTag;
    }

    /**
     * 用固定分隔符包裹会话级固定资料。
     *
     * <p>固定资料位于 Prompt 前缀中，不能使用 {@link #wrapWithDelimiters(String, String)}
     * 的随机 UUID，否则同一会话每次请求都会产生不同的前缀，降低模型提供商前缀缓存命中率。
     * 文本仍会经过净化，避免资料内容伪造边界标签或角色指令。</p>
     */
    public String wrapWithStableDelimiters(String label, String text) {
        String safeLabel = label == null || label.isBlank()
            ? "data"
            : label.replaceAll("[^a-zA-Z0-9_-]", "-");
        String safeText = sanitizeForContext(text);
        String openTag = "<data-boundary-" + safeLabel + ">";
        String closeTag = "</data-boundary-" + safeLabel + ">";
        return openTag + "\n" + (safeText == null ? "" : safeText) + "\n" + closeTag;
    }

    /**
     * 检测注入尝试（仅日志告警，不阻断）。
     */
    public boolean detectInjectionAttempt(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return ROLE_INJECTION_PATTERN.matcher(text).find()
            || INJECTION_PHRASE_PATTERN.matcher(text).find()
            || DELIMITER_INJECTION_PATTERN.matcher(text).find()
            || BOUNDARY_TAG_PATTERN.matcher(text).find();
    }

    private String safeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "data";
        }
        return label.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    private boolean isSanitizerEnabled() {
        return properties.getAdvisors() == null
            || properties.getAdvisors().isPromptSanitizerEnabled();
    }
}

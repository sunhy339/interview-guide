package interview.guide.common.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 为不应暴露正文的 Prompt 元数据生成稳定指纹。
 *
 * <p>指纹只用于运行观测和回归比对，不用于授权，也不能从中恢复 Prompt 正文。</p>
 */
public final class PromptFingerprint {

    private PromptFingerprint() {
    }

    public static String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}

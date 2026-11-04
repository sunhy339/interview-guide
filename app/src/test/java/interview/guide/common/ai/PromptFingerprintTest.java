package interview.guide.common.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptFingerprintTest {

    @Test
    void producesStableSha256WithoutExposingPromptContent() {
        assertThat(PromptFingerprint.sha256("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(PromptFingerprint.sha256("固定前缀"))
            .isEqualTo(PromptFingerprint.sha256("固定前缀"))
            .hasSize(64);
    }
}

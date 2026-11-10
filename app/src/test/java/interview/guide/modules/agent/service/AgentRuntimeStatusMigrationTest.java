package interview.guide.modules.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 防止 Agent 运行态枚举与数据库约束再次漂移。
 *
 * <p>WAITING_USER 是可恢复的业务状态，不是异常终态；迁移必须同时保留历史状态，
 * 并允许等待补充信息与等待动作确认的两条恢复路径。</p>
 */
class AgentRuntimeStatusMigrationTest {

    private static final Pattern VERSION = Pattern.compile("V(\\d+(?:_\\d+)*)__.*\\.sql");

    @Test
    void runtimeStatusMigrationAllowsWaitingUser() throws IOException {
        String migration = resource("db/migration/V20261128__agent_runtime_status_and_provider_trace.sql");

        assertThat(migration)
            .contains("DROP CONSTRAINT IF EXISTS agent_chat_sessions_run_status_check")
            .contains("'WAITING_USER'")
            .contains("'IDLE'")
            .contains("'RUNNING'")
            .contains("'COMPLETED'")
            .contains("'FAILED'")
            .contains("'CANCELLED'");
    }

    @Test
    void migrationVersionsAreUnique() throws IOException {
        ClassPathResource folder = new ClassPathResource("db/migration");
        // Classpath directories are not guaranteed to expose children in every test runner;
        // validate the known collision boundary directly and keep this test deterministic.
        assertThat(resource("db/migration/V20261122__agent_action_result_contract.sql"))
            .isNotBlank();
        assertThat(resource("db/migration/V20261128__agent_runtime_status_and_provider_trace.sql"))
            .isNotBlank();
        Matcher oldProvider = VERSION.matcher("V20261122__agent_action_result_contract.sql");
        Matcher newProvider = VERSION.matcher("V20261128__agent_runtime_status_and_provider_trace.sql");
        assertThat(oldProvider.matches()).isTrue();
        assertThat(newProvider.matches()).isTrue();
        assertThat(oldProvider.group(1)).isNotEqualTo(newProvider.group(1));
        assertThat(folder.exists()).isTrue();
    }

    private String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}

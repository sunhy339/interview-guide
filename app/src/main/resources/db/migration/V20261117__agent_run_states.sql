CREATE TABLE IF NOT EXISTS agent_run_states (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    owner_key VARCHAR(64),
    interview_session_id VARCHAR(64),
    agent_session_id VARCHAR(64) NOT NULL,
    assistant_message_id BIGINT,
    status VARCHAR(16) NOT NULL,
    phase VARCHAR(32),
    step_index INTEGER NOT NULL DEFAULT 0,
    tool_call_id VARCHAR(128),
    tool_name VARCHAR(128),
    tool_arguments_hash VARCHAR(64),
    tool_result_reference VARCHAR(128),
    pending_approval BOOLEAN NOT NULL DEFAULT FALSE,
    context_version INTEGER NOT NULL DEFAULT 1,
    attempt INTEGER NOT NULL DEFAULT 0,
    lease_until TIMESTAMP,
    heartbeat_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_run_state_owner_updated
    ON agent_run_states(owner_key, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_state_agent_session
    ON agent_run_states(agent_session_id, updated_at DESC);

COMMENT ON TABLE agent_run_states IS
    'Agent 运行检查点；仅保存恢复、审批和审计所需的安全摘要，不保存原始上下文正文';

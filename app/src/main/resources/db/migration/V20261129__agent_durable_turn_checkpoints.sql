CREATE TABLE IF NOT EXISTS agent_run_checkpoints (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    owner_key VARCHAR(64),
    agent_session_id VARCHAR(64),
    checkpoint_version INTEGER NOT NULL DEFAULT 1,
    checkpoint_type VARCHAR(48) NOT NULL,
    run_status VARCHAR(16),
    phase VARCHAR(32),
    recovery_boundary VARCHAR(32),
    assistant_message_id BIGINT,
    step_index INTEGER,
    attempt INTEGER NOT NULL DEFAULT 0,
    pending_approval BOOLEAN NOT NULL DEFAULT FALSE,
    context_version INTEGER,
    model_turn_id VARCHAR(64),
    model_provider VARCHAR(64),
    model_turn_status VARCHAR(16),
    model_turn_index INTEGER,
    model_result_reference VARCHAR(128),
    provider_response_id VARCHAR(128),
    provider_response_ids_hash VARCHAR(64),
    provider_tool_call_ids_hash VARCHAR(64),
    provider_response_count INTEGER,
    provider_tool_call_count INTEGER,
    tool_call_id VARCHAR(128),
    tool_name VARCHAR(128),
    tool_arguments_hash VARCHAR(64),
    tool_result_reference VARCHAR(128),
    tool_result_contract_version VARCHAR(64),
    tool_result_status VARCHAR(64),
    resume_action_id VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_run_checkpoint_run_id
    ON agent_run_checkpoints(run_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_agent_run_checkpoint_owner_session
    ON agent_run_checkpoints(owner_key, agent_session_id, created_at DESC);

COMMENT ON TABLE agent_run_checkpoints IS
    'Agent Durable RunState 追加式低敏检查点；不保存 Prompt、模型正文、工具参数或工具结果正文';
COMMENT ON COLUMN agent_run_checkpoints.checkpoint_type IS
    '模型轮次、Provider 工具批次、工具结果、等待用户和终态等边界类型';

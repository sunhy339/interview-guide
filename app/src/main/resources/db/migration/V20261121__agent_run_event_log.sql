CREATE TABLE IF NOT EXISTS agent_run_events (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    owner_key VARCHAR(64),
    agent_session_id VARCHAR(64),
    event_type VARCHAR(64) NOT NULL,
    model_turn_id VARCHAR(64),
    tool_call_id VARCHAR(128),
    tool_name VARCHAR(128),
    arguments_hash VARCHAR(64),
    result_hash VARCHAR(64),
    status VARCHAR(32),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_run_event_run_id
    ON agent_run_events(run_id, id ASC);

CREATE INDEX IF NOT EXISTS idx_agent_run_event_owner_session
    ON agent_run_events(owner_key, agent_session_id, created_at DESC);

COMMENT ON TABLE agent_run_events IS
    'Agent Run 追加式低敏事件；仅保存轮次、工具关联和摘要哈希，不保存正文';

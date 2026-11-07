ALTER TABLE agent_action_requests
    ADD COLUMN IF NOT EXISTS run_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_agent_action_agent_run
    ON agent_action_requests(agent_session_id, run_id, created_at DESC);

COMMENT ON COLUMN agent_action_requests.run_id IS
    '产生该动作的 Agent 运行代次；历史动作可为空，恢复时用于关联动作与运行证据';

ALTER TABLE agent_chat_sessions
    ADD COLUMN IF NOT EXISTS pending_type VARCHAR(32);

ALTER TABLE agent_chat_sessions
    ADD COLUMN IF NOT EXISTS pending_schema_version VARCHAR(32);

ALTER TABLE agent_chat_sessions
    ADD COLUMN IF NOT EXISTS pending_required_fields TEXT;

ALTER TABLE agent_chat_sessions
    ADD COLUMN IF NOT EXISTS pending_action_id VARCHAR(64);

COMMENT ON COLUMN agent_chat_sessions.pending_type IS
    'Agent 可恢复等待交互类型，例如 INPUT 或 APPROVAL；不保存原始资料正文';

COMMENT ON COLUMN agent_chat_sessions.pending_required_fields IS
    'Agent 等待用户补充的安全字段名 JSON，不保存模型隐藏推理';

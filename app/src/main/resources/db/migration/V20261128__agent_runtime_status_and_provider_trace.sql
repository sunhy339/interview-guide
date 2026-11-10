-- Agent 等待用户补充信息是可恢复的运行态，必须与已存在的生命周期状态约束保持一致。
ALTER TABLE agent_chat_sessions
    DROP CONSTRAINT IF EXISTS agent_chat_sessions_run_status_check;

ALTER TABLE agent_chat_sessions
    ADD CONSTRAINT agent_chat_sessions_run_status_check
    CHECK (run_status IN ('IDLE', 'RUNNING', 'WAITING_USER', 'COMPLETED', 'FAILED', 'CANCELLED'));

-- Provider 追踪元数据只保存低敏关联信息，不保存模型消息正文。
ALTER TABLE agent_run_events
    ADD COLUMN IF NOT EXISTS provider_response_id VARCHAR(128);

ALTER TABLE agent_run_events
    ADD COLUMN IF NOT EXISTS provider_response_ids_hash VARCHAR(64);

ALTER TABLE agent_run_events
    ADD COLUMN IF NOT EXISTS provider_tool_call_ids_hash VARCHAR(64);

ALTER TABLE agent_run_events
    ADD COLUMN IF NOT EXISTS provider_response_count INTEGER;

ALTER TABLE agent_run_events
    ADD COLUMN IF NOT EXISTS provider_tool_call_count INTEGER;

ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS provider_response_id VARCHAR(128);

ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS provider_response_ids_hash VARCHAR(64);

ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS provider_tool_call_ids_hash VARCHAR(64);

ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS provider_response_count INTEGER;

ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS provider_tool_call_count INTEGER;

COMMENT ON COLUMN agent_run_events.provider_response_id IS
    'Provider 响应稳定标识；只用于低敏 Trace 关联，不保存模型消息正文';

COMMENT ON COLUMN agent_run_events.provider_tool_call_ids_hash IS
    'Provider 工具调用 ID 批次摘要；不保存工具参数或结果正文';

COMMENT ON COLUMN agent_run_states.provider_response_id IS
    '最近模型轮次的 Provider 响应稳定标识；不代表业务动作成功';

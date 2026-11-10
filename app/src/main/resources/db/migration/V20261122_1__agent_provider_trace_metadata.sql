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

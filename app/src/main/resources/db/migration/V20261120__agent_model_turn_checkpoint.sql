ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS model_turn_id VARCHAR(64);

ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS model_provider VARCHAR(64);

ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS model_turn_status VARCHAR(16);

ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS model_turn_index INTEGER NOT NULL DEFAULT 0;

ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS model_result_reference VARCHAR(128);

COMMENT ON COLUMN agent_run_states.model_turn_id IS
    '模型轮次的安全摘要标识；不保存请求正文、工具原始参数或模型隐藏推理';

COMMENT ON COLUMN agent_run_states.model_result_reference IS
    '模型轮次结果状态摘要；不代表模型输出正文或业务动作已经成功';

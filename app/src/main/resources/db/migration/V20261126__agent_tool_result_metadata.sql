ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS tool_result_contract_version VARCHAR(64);

ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS tool_result_status VARCHAR(64);

COMMENT ON COLUMN agent_run_states.tool_result_contract_version IS
    '工具结果契约版本的低敏摘要；不保存工具结果正文';

COMMENT ON COLUMN agent_run_states.tool_result_status IS
    '工具结果有限状态的低敏摘要；不保存工具结果正文';

ALTER TABLE agent_action_requests
    ADD COLUMN IF NOT EXISTS result_contract_version VARCHAR(32),
    ADD COLUMN IF NOT EXISTS result_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS result_effect VARCHAR(32),
    ADD COLUMN IF NOT EXISTS result_resource_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS result_resource_id VARCHAR(128);

COMMENT ON COLUMN agent_action_requests.result_contract_version IS
    'Agent 动作执行结果的结构化契约版本；不保存模型原文';
COMMENT ON COLUMN agent_action_requests.result_code IS
    'Agent 动作执行结果码，由受控业务服务写入';
COMMENT ON COLUMN agent_action_requests.result_effect IS
    'Agent 动作执行结果影响类型，由受控业务服务写入';
COMMENT ON COLUMN agent_action_requests.result_resource_type IS
    'Agent 动作执行结果关联的业务资源类型';
COMMENT ON COLUMN agent_action_requests.result_resource_id IS
    'Agent 动作执行结果关联的业务资源标识';

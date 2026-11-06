ALTER TABLE agent_action_requests
    ADD COLUMN IF NOT EXISTS context_asset_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_agent_action_context_asset
    ON agent_action_requests(context_asset_id);

COMMENT ON COLUMN agent_action_requests.context_asset_id IS
    '导入简历动作指向的 Agent 私有素材 ID；确认执行时不从可编辑草稿中读取';

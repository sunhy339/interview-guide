CREATE TABLE IF NOT EXISTS agent_async_operations (
    id BIGSERIAL PRIMARY KEY,
    operation_id VARCHAR(64) NOT NULL UNIQUE,
    owner_key VARCHAR(64) NOT NULL,
    agent_session_id VARCHAR(64),
    run_id VARCHAR(64),
    asset_id VARCHAR(64),
    knowledge_base_id BIGINT,
    operation VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    detail VARCHAR(500),
    attempt INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_async_operation_run_kb
    ON agent_async_operations(owner_key, run_id, knowledge_base_id, operation)
    WHERE run_id IS NOT NULL AND knowledge_base_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_async_operation_kb
    ON agent_async_operations(knowledge_base_id, operation, status);

CREATE INDEX IF NOT EXISTS idx_agent_async_operation_session
    ON agent_async_operations(owner_key, agent_session_id, created_at DESC);

ALTER TABLE agent_run_events
    ADD COLUMN IF NOT EXISTS resource_id VARCHAR(128);

ALTER TABLE agent_run_events
    ADD COLUMN IF NOT EXISTS operation VARCHAR(64);

COMMENT ON TABLE agent_async_operations IS
    'Agent 异步领域任务与原始 Run 的低敏关联，不保存 Prompt、正文或工具参数';

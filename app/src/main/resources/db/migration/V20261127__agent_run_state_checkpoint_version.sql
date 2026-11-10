ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS checkpoint_version INTEGER;

UPDATE agent_run_states
SET checkpoint_version = 1
WHERE checkpoint_version IS NULL;

COMMENT ON COLUMN agent_run_states.checkpoint_version IS
    'Agent RunState 持久化契约版本；未知版本不会被自动恢复';

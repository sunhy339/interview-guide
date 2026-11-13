CREATE INDEX IF NOT EXISTS idx_agent_run_checkpoint_created_at
    ON agent_run_checkpoints(created_at, id);

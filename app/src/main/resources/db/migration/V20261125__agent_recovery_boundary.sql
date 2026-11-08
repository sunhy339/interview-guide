ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS recovery_boundary VARCHAR(32);

COMMENT ON COLUMN agent_run_states.recovery_boundary IS
    'Agent 重启恢复边界；工具已开始但结果未知时禁止自动重放，不保存 Prompt 或业务结果正文';

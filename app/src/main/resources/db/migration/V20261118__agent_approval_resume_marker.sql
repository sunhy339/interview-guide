ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS resume_action_id VARCHAR(64);

ALTER TABLE agent_run_states
    ADD COLUMN IF NOT EXISTS resume_notice VARCHAR(500);

COMMENT ON COLUMN agent_run_states.resume_action_id IS
    '审批事务完成后待续跑动作的稳定标识；重启恢复时只续跑回答，不重放原始写操作';

COMMENT ON COLUMN agent_run_states.resume_notice IS
    '审批结果续跑提示的安全摘要，不保存原始工具参数或模型隐藏推理';

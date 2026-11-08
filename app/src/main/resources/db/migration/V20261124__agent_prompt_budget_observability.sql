ALTER TABLE agent_chat_sessions
    ADD COLUMN IF NOT EXISTS run_estimated_prompt_tokens INTEGER;

ALTER TABLE agent_run_usages
    ADD COLUMN IF NOT EXISTS estimated_prompt_tokens INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN agent_chat_sessions.run_estimated_prompt_tokens IS
    '最近一次 Agent 运行的输入上下文保守估算；不是 Provider 账单 Token';

COMMENT ON COLUMN agent_run_usages.estimated_prompt_tokens IS
    'Agent 输入上下文保守估算；Provider 未返回 usage 时用于可见性，不作为账单 Token';

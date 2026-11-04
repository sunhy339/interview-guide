ALTER TABLE agent_chat_sessions
    ADD COLUMN IF NOT EXISTS run_prompt_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS run_prompt_fingerprint VARCHAR(64);

ALTER TABLE agent_run_usages
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS prompt_fingerprint VARCHAR(64);

COMMENT ON COLUMN agent_chat_sessions.run_prompt_version IS
    '最近一次 Agent 运行使用的固定系统 Prompt 版本，不保存 Prompt 正文';
COMMENT ON COLUMN agent_chat_sessions.run_prompt_fingerprint IS
    '最近一次 Agent 运行固定系统 Prompt 的 SHA-256 指纹';
COMMENT ON COLUMN agent_run_usages.prompt_version IS
    '该 Agent 运行使用的固定系统 Prompt 版本，不保存 Prompt 正文';
COMMENT ON COLUMN agent_run_usages.prompt_fingerprint IS
    '该 Agent 运行固定系统 Prompt 的 SHA-256 指纹';

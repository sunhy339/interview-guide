ALTER TABLE agent_run_usages
    ALTER COLUMN interview_session_id DROP NOT NULL;

COMMENT ON COLUMN agent_run_usages.interview_session_id IS
    '可选的来源面试；通用 Agent 运行可以没有面试报告上下文';

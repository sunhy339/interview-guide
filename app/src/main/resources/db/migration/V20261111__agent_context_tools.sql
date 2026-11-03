ALTER TABLE agent_action_requests
    ALTER COLUMN interview_session_id DROP NOT NULL;

COMMENT ON COLUMN agent_action_requests.interview_session_id IS
    '可选的来源面试；通用 Agent 生成的日程草稿可以不绑定面试报告';

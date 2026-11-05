ALTER TABLE agent_action_requests
    ADD COLUMN IF NOT EXISTS schedule_id BIGINT;

COMMENT ON COLUMN agent_action_requests.schedule_id IS
    '取消既有面试日程时指向的日程 ID；创建日程动作为空';

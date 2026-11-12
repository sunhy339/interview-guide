ALTER TABLE agent_chat_messages
    ADD COLUMN IF NOT EXISTS steering BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent_chat_messages
    ADD COLUMN IF NOT EXISTS steering_consumed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent_chat_messages
    ADD COLUMN IF NOT EXISTS steering_run_id VARCHAR(36);

CREATE INDEX IF NOT EXISTS idx_agent_chat_messages_steering_pending
    ON agent_chat_messages (session_id, steering_run_id, steering_consumed, sequence_num)
    WHERE steering = TRUE AND steering_consumed = FALSE;

COMMENT ON COLUMN agent_chat_messages.steering IS
    '用户在 Agent 运行期间发送的待处理补充输入；不创建第二个 Agent 任务';

COMMENT ON COLUMN agent_chat_messages.steering_consumed IS
    '待处理补充输入是否已经注入当前工具回合后的模型上下文';

COMMENT ON COLUMN agent_chat_messages.steering_run_id IS
    '接收这条补充输入时绑定的 Agent runId，防止跨运行误注入';

-- Spring AI 模型上下文持久化。
-- 与 chat_history 分离：chat_history 只保存页面展示文本，本表保存可恢复的模型消息协议。
CREATE TABLE IF NOT EXISTS spring_ai_chat_message
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '消息记录 ID',
    conversation_id VARCHAR(128) NOT NULL COMMENT 'Spring AI 会话 ID',
    app_id         BIGINT NULL COMMENT '应用 ID，便于按应用清理和检索',
    sequence_no    INT NOT NULL COMMENT '会话内稳定消息序号',
    message_type   VARCHAR(32) NOT NULL COMMENT 'Spring AI 消息类型',
    message_data   JSON NOT NULL COMMENT '完整消息 JSON，包含 metadata 和工具协议字段',
    create_time    DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '创建时间',
    update_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_spring_ai_chat_message_sequence (conversation_id, sequence_no),
    INDEX idx_spring_ai_chat_message_app (app_id),
    INDEX idx_spring_ai_chat_message_conversation (conversation_id, sequence_no)
) COMMENT 'Spring AI 模型上下文消息' COLLATE = utf8mb4_unicode_ci;

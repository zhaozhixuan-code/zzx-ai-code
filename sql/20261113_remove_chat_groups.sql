-- 移除尚未接入业务的对话房间结构，仅执行一次。
-- 执行前备份 chat_history、chat_group、chat_group_member 的结构与数据。
-- 保留 chat_history 的应用对话记录、基础字段及现有单人查询索引。
USE zzx_ai_code;

ALTER TABLE chat_history
    DROP INDEX idx_group_chat,
    DROP INDEX idx_migrateFlag,
    DROP COLUMN groupId,
    DROP COLUMN migrateFlag;

DROP TABLE IF EXISTS chat_group_member;
DROP TABLE IF EXISTS chat_group;

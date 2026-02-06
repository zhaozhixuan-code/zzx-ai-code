create database zzx_ai_code;

-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
) comment '用户' collate = utf8mb4_unicode_ci;


-- 应用表
create table app
(
    id           bigint auto_increment comment 'id' primary key,
    appName      varchar(256)                       null comment '应用名称',
    cover        varchar(512)                       null comment '应用封面',
    initPrompt   text                               null comment '应用初始化的 prompt',
    codeGenType  varchar(64)                        null comment '代码生成类型（枚举）',
    deployKey    varchar(64)                        null comment '部署标识',
    deployedTime datetime                           null comment '部署时间',
    priority     int      default 0                 not null comment '优先级',
    userId       bigint                             not null comment '创建用户id',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey), -- 确保部署标识唯一
    INDEX idx_appName (appName),         -- 提升基于应用名称的查询性能
    INDEX idx_userId (userId)            -- 提升基于用户 ID 的查询性能
) comment '应用' collate = utf8mb4_unicode_ci;

-- 对话历史表
create table chat_history
(
    id          bigint auto_increment comment 'id' primary key,
    message     text                               not null comment '消息',
    messageType varchar(32)                        not null comment 'user/ai',
    appId       bigint                             not null comment '应用id',
    userId      bigint                             not null comment '创建用户id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    INDEX idx_appId (appId),                       -- 提升基于应用的查询性能
    INDEX idx_createTime (createTime),             -- 提升基于时间的查询性能
    INDEX idx_appId_createTime (appId, createTime) -- 游标查询核心索引
) comment '对话历史' collate = utf8mb4_unicode_ci;

-- 对话房间表
CREATE TABLE chat_group
(
    id         bigint auto_increment comment 'id' primary key,
    appId      BIGINT                             NOT NULL COMMENT '应用id',
    roomName   VARCHAR(100)                       not null COMMENT '房间名称',
    creatorId  bigint                             not null comment '创建者用户ID',
    createTime DATETIME DEFAULT CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    INDEX idx_app (appId),          -- 提升基于应用的查询性能
    INDEX idx_creatorId (creatorId) -- 提升基于创建者的查询性能
) comment '对话房间' collate = utf8mb4_unicode_ci;

-- 群组成员表，管理群成员关系
CREATE TABLE chat_group_member
(
    id         bigint auto_increment comment 'id' primary key,
    groupId    bigint                                not null comment '群组ID',
    userId     bigint                                not null comment '用户ID',
    role       varchar(32) default 'member'          not null comment '成员角色：admin-管理员，member-普通成员',
    joinTime   datetime    default CURRENT_TIMESTAMP not null comment '加入时间',
    quitTime   datetime                              null comment '退出时间',
    isQuit     tinyint     default 0                 not null comment '是否退出：0-未退出，1-已退出',
    createTime datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint     default 0                 not null comment '是否删除',
    UNIQUE KEY uk_groupId_userId (groupId, userId), -- 确保一个用户只能加入一个群组一次
    INDEX idx_groupId (groupId),                    -- 提升基于群组的查询性能
    INDEX idx_userId (userId)                       -- 提升基于用户的查询性能
) comment '群组成员表' collate = utf8mb4_unicode_ci;

-- 1. 先切换到你的数据库
USE zzx_ai_code;

-- 2. 新增groupId字段（区分单人/多人，null=单人，数值=多人）
ALTER TABLE chat_history
    ADD COLUMN groupId bigint NULL COMMENT '群组ID（关联chat_group.id，null表示单人会话）'
        AFTER appId;

-- 3. 新增migrateFlag字段（标记是否为从单人同步的历史消息）
ALTER TABLE chat_history
    ADD COLUMN migrateFlag tinyint DEFAULT 0 NOT NULL COMMENT '迁移标记：0-原生消息，1-从单人同步到群聊的消息'
        AFTER groupId;

-- 4. 新增单人聊天核心索引（适配原有单人查询，性能优化）
ALTER TABLE chat_history
    ADD INDEX idx_single_chat (appId, userId, createTime);

-- 5. 新增多人聊天核心索引（适配群聊查询）
ALTER TABLE chat_history
    ADD INDEX idx_group_chat (groupId, appId, createTime);

-- 6. 新增迁移标记索引（可选，便于筛选同步的历史消息）
ALTER TABLE chat_history
    ADD INDEX idx_migrateFlag (migrateFlag);



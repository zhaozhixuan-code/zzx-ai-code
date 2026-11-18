-- 单体项目质量报告；由管理员执行一次，不在应用启动时自动建表。
CREATE TABLE IF NOT EXISTS quality_task (
    task_id VARCHAR(36) PRIMARY KEY,
    app_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    instance_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP(3) NOT NULL,
    payload LONGTEXT NOT NULL,
    isDelete TINYINT NOT NULL DEFAULT 0,
    INDEX idx_quality_app_started (app_id, started_at),
    INDEX idx_quality_instance_status (instance_id, status)
);
CREATE TABLE IF NOT EXISTS quality_round (
    task_id VARCHAR(36) NOT NULL,
    check_round INT NOT NULL,
    payload LONGTEXT NOT NULL,
    isDelete TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (task_id, check_round)
);

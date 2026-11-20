# Pi Agent 提交审查

推送到 `master` 时，GitHub Actions 用 `git rev-list` 取提交范围，用 `git show` 取每个提交的 diff，包含审查脚本、工作流和文档的改动。`review_notify.py` 将 diff 交给 Pi，再把结果按提交作者发到飞书。Pi 只接收 diff，文件读取、Shell 和其他工具都关闭。Git diff 为空时，报告会说明本提交无文件变更。

飞书文本使用「分支、提交人、出现的问题、建议如何修复、紧急程度」五个字段。Git 命令失败、diff 超过 256 KiB 或命中凭据检查、Pi 超时或模型请求失败时，报告会写明原因，并将作业标记为失败。消息按飞书请求大小限制分批，重跑作业只输出 Actions 日志。

Runner 沿用 `~/.pi/agent/settings.json` 及 `models.json` 选择和配置模型。已设置 `PI_CODING_AGENT_DIR` 的 Runner 使用该目录。飞书沿用 `~/.config/ai-commit-review/feishu.json` 里的 `webhook_url` 和 `signing_secret`。

Runner 注册、配置示例及仓库变量见[部署手册](../docs/ai-commit-review/03-提交审查部署手册.md)。飞书报文和签名见[自定义机器人使用指南](https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot)。

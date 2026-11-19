# AI 提交审查

GitHub 收到 `master` push 后，将 **AI commit review** 派给带 `ai-commit-review` 标签的服务器 Runner。Runner 根据 push 的前后 SHA 拉取源码，运行 Python 和 Pi Agent，校验审查结果后从服务器直接向飞书发送一条纯文本摘要。完整报告在内存中用于汇总，源码、差异和 Pi 的工作文件使用任务临时目录，随任务退出清理。工作流不上传报告、归档或飞书回执。

首次部署或更换服务器时，见 [AI 提交审查部署手册](../docs/ai-commit-review/03-提交审查部署手册.md)。

## 启用

服务器 Runner `zzx-ai-review` 由 `ai-review-runner` 用户运行。服务器需要 Git、Bash、Python 3 和 pip，并能访问 GitHub、依赖源、Pi 配置的模型服务和飞书。工作流使用 Node `22.22.1` 和 Pi `1.5.2`。

已有服务器配置可以用下面的命令交给 Runner 用户。新服务器在同一位置准备权限为 `0600` 的 `feishu.json`，其中 `webhook_url`、`signing_secret` 和 `target_name` 分别填写现有机器人 Webhook、签名密钥和群名。

```bash
sudo install -d -o ai-review-runner -g ai-review-runner -m 0700 /var/lib/ai-review-runner/.config/ai-commit-review
sudo install -o ai-review-runner -g ai-review-runner -m 0600 \
  /root/ai-commit-review/secrets/feishu.json \
  /var/lib/ai-review-runner/.config/ai-commit-review/feishu.json
```

在 GitHub 的 **Settings → Secrets and variables → Actions → Variables** 管理开关。

| 仓库变量 | 作用 |
| --- | --- |
| `AI_REVIEW_MASTER_ENABLED` | 设为 `true`，处理 `master` 的 push。 |
| `AI_REVIEW_RUN_MODEL` | 设为 `true`，启用自动和手动审查。 |
| `AI_REVIEW_SEND_FEISHU` | 设为 `true`，将 `master` push 的摘要发到飞书。 |
| `AI_REVIEW_TIMEOUT_SECONDS` | 单提交的预算，范围 1～480 秒，默认 480 秒。 |

## 切换模型或接入地址

登录服务器后，编辑 `ai-review-runner` 用户的 Pi 配置。默认目录是 `/var/lib/ai-review-runner/.pi/agent/`。如果 Runner 服务设置了 `PI_CODING_AGENT_DIR`，使用该目录。每次运行会重新读取配置，模型和接入地址的日常切换无需重启 Runner 或修改 GitHub 工作流。

- `settings.json` 用 `defaultProvider` 和 `defaultModel` 选择连接及模型。未配置时使用 `deepseek` 和 `deepseek-flash`。
- `models.json` 管理自定义连接。在 `providers.<provider>.baseUrl` 填接入地址，在 `models` 注册网关实际使用的模型 ID。内置模型只换地址时，可在对应 provider 下设置 `baseUrl` 和该接入的凭据。
- API Key 可用 Pi 的 `/login` 保存到 Runner 用户的 `auth.json`，也可通过 `models.json` 的 `apiKey` 设置。文件需由 Runner 用户持有，含凭据的文件权限设为 `0600`。`apiKey` 可引用 Runner 服务环境中以 `_API_KEY` 结尾的变量。审查使用 API Key 认证。默认 DeepSeek 直连也可从 Runner 服务的 `DEEPSEEK_API_KEY` 读取密钥。

例如，将支持工具调用的 OpenAI 兼容网关命名为 `review-gateway`。先保存 `models.json`，再在 `settings.json` 选择它。

`models.json`

```json
{
  "providers": {
    "review-gateway": {
      "baseUrl": "https://gateway.example.com/v1",
      "api": "openai-completions",
      "apiKey": "替换为该网关的 API Key",
      "models": [{ "id": "deepseek-flash" }]
    }
  }
}
```

`settings.json`

```json
{
  "defaultProvider": "review-gateway",
  "defaultModel": "deepseek-flash"
}
```

多个网关各用一个 provider 名，可分别保留它们的地址和 API Key。下次运行会使用更新后的模型配置。

## 查看结果和补审

- 飞书每次收到一条摘要，显示提交数、完成状态、P0/P1/P2 数量和最多三条问题标题。仅涉及过滤路径的提交会显示为跳过。未完成、失败和未处理分别计数。
- 一次运行最多审查 50 个提交。总审查预算耗尽或超过上限时，剩余提交计入未处理。手动补审可在 **Actions → AI commit review → Run workflow** 填入完整 `commit`，或填写 `base` 和 `head` 指定范围。勾选 `send_feishu` 后会重新审查并发送一条摘要。
- 在作业的 `Review commits and send the Feishu summary` 步骤检查执行状态。`Feishu delivery` 显示 `sent` 且业务码为 `0` 表示飞书已受理，`unknown` 时请在群内确认收件。GitHub 的 **Re-run jobs** 只重审，防止一条 push 通知重复发送。

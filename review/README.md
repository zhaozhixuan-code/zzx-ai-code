# AI 提交审查

GitHub 收到 `master` 或 `codex/ai-review-e2e` 的 push 后，由 **AI commit review** 计算该次推送的提交范围。GitHub 托管 runner 使用 Pi 和 DeepSeek 对每个提交与其第一父版本做静态审查，归档报告，再按设置向飞书发送批次摘要。旧报告的手动归档仍由专用 runner 执行。

## 启用

在仓库 **Settings → Environments** 配置两个环境，并限制可使用环境的分支。当前允许 `master` 和 `codex/ai-review-e2e`。

| 环境 | 类型 | 名称 |
| --- | --- | --- |
| `ai-review-model` | Secret | `DEEPSEEK_API_KEY` |
| `ai-review-feishu` | Secret | `FEISHU_WEBHOOK_URL`、`FEISHU_SIGNING_SECRET` |
| `ai-review-feishu` | Variable | `FEISHU_TARGET_NAME` |

在 **Settings → Secrets and variables → Actions → Variables** 管理自动运行开关。

| 仓库变量 | 设为 `true` 时 |
| --- | --- |
| `AI_REVIEW_MASTER_ENABLED` | 接收 `master` 的 push。 |
| `AI_REVIEW_RUN_MODEL` | 运行模型并归档报告。 |
| `AI_REVIEW_SEND_FEISHU` | 自动发送 `master` 推送的批次摘要。 |

先在验收分支检查 `range.json` 和审查归档。运行正常后开启 `master` 推送入口。确认机器人目标群、签名和发送范围后再启用自动通知。单提交默认预算为 480 秒，可用 `AI_REVIEW_TIMEOUT_SECONDS` 设置 1～480 秒。

## 查看结果和补审

- 在 **Actions → AI commit review** 查看相应运行。范围归档 `ai-review-range-<尝试号>` 保存 SHA、批次和 `deferred`；批次归档 `ai-review-batch-<运行ID>-<尝试号>-<批次>` 保存清单、报告和执行记录。批次从 0 开始，归档默认保留 30 天。
- 每次 push 最多安排 50 个提交，每批最多 10 个。手动补审时，在 **Run workflow** 填写 `commit`，或用 `base` 和 `head` 指定范围。三个字段都填完整 SHA。对 `deferred` 可逐个补审，或按固定范围再分段。
- 同一审查键的完成报告可以复用。勾选 `force_review` 会重新审查并保存独立尝试。使用 **Re-run failed jobs** 会沿用该运行已归档的计划，通知作业会跳过。
- `completed/no_findings` 表示本次审查无有效发现。`failed` 或 `incomplete` 请检查报告中的原因、执行记录和覆盖限制。仅涉及被过滤路径等没有可审查源码变更的提交会归档为 `skipped/inconclusive`。
- 打开批次归档中的 `manifest.json` 核对每个提交的处理状态，再按清单中的路径打开 `review.md` 查看审查结论或 `execution.json` 查看执行记录。若该次运行只有范围归档，先查看 `review` 作业是否被跳过，并检查 `AI_REVIEW_RUN_MODEL`。

## 核对飞书自动投递

- 对 `master` 的 push 启用自动通知时，`AI_REVIEW_MASTER_ENABLED`、`AI_REVIEW_RUN_MODEL` 和 `AI_REVIEW_SEND_FEISHU` 都应为 `true`。普通手动补审会生成通知预览，手动发送已有报告请使用下方的仅通知入口。
- 运行成功但飞书没有消息时，在对应 Actions 运行中打开 `notify (批次号)`，查看 `Send a single signed Feishu summary`。若显示跳过，先核对事件是否为 `master` 的首次 push 运行，以及 `AI_REVIEW_SEND_FEISHU` 是否已开启。
- 在运行的 Artifacts 中打开后缀为 `-receipt` 的通知归档。`feishu-receipt.json` 中的 `status: sent`、`http_status: 200`、`business_code: 0` 表示飞书已受理。只有 `-pending` 归档时先到目标群核对，确认上一条投递结果后再决定是否补发。

## 仅通知或补发

1. 在 **Run workflow** 填写 `notification_run`、`notification_attempt` 和 `notification_batch`，分别为原审查运行、尝试号和批次索引。留空 `confirm_message_sha256` 生成预览。
2. 从动作摘要核对报告和消息 SHA-256。确认目标群与投递后，使用相同输入并填写 `confirm_message_sha256`。仅通知作业直接读取已有归档。
3. 已有 `sent` 回执时会拒绝再次投递。只有待确认归档时，按 `unknown` 处理并先核对群内收件。需要补发时填写 `previous_delivery_run`，引用最近的 `failed` 或 `unknown` 投递，并沿用确认过的消息摘要。

## 暂停

关闭 `AI_REVIEW_SEND_FEISHU` 可暂停自动通知。关闭 `AI_REVIEW_MASTER_ENABLED` 可暂停 `master` push 审查。要暂停所有入口，可禁用 **AI commit review**，并在 Actions 里处理排队或运行中的任务。

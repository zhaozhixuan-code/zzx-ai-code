# GitHub 提交 AI 审查与飞书通知：开发跟踪

更新日期：2026-11-19。配套：[需求文档](01-requirements.md)。

当前交付将 `master` push 派给服务器 Runner。服务器拉取固定 SHA，调用 Python 与 Pi 审查，并直接向飞书发送一条摘要。完整报告只用于进程内校验，运行结束后清理临时材料。部署和开关见 [审查使用说明](../../review/README.md)。

## 1. 开发进度总表

验收分支已通过真实 push、模型审查、报告归档及复用的验证。`master` 已启用自动审查，真实 push 验证了空变更归档，真实手动补审验证了源码审查。Actions 已完成确认后的飞书投递，并已通过一次真实 `master` push 验证自动发送，两个批次均有成功回执。`59e2ef0` 在服务器的 480 秒业务审查以超时结束。

| 步骤 | 需求与目标 | 前置依赖 | 状态 | 完成证据或剩余问题 |
| --- | --- | --- | --- | --- |
| S00 | 整理需求和开发计划，明确审查、报告、通知流程 | 无 | 已完成 | 需求文档和本文；未执行功能验收 |
| S01 | 确认仓库、分支、模型、机器人和版本配置 | S00 | 已完成 | `master` 与验收分支已配置；Pi 1.5.2、DeepSeek、专用 GitHub 环境、依赖锁和固定控制 SHA 已核对 |
| S02 | 准确找出本次 push 需要审查的全部提交 | S01；本地样例可先行 | 已完成 | GitHub 归档已核对单提交、三提交、连续推送和 51 提交范围；新分支、强推、删除、缺失对象由本地 Git 用例覆盖 |
| S03 | 为每个提交准备对应版本的源码和差异 | S02 | 已完成 | 三份真实审查报告各自固定 commit 和第一父 SHA；根提交、合并、重命名、删除和敏感配置过滤由本地 Git 用例覆盖 |
| S04 | 提供只读、受限的代码查阅工具 | S03 | 已完成 | 三个工具已部署；Linux 下路径、白名单、符号链接、篡改、限额与版本读取测试通过，Pi 1.5.2 扩展加载通过；实际模型工具循环归 S05 验证，见第 6 节 |
| S05 | 接入 Pi，按规则自主查阅代码并输出结果 | S01、S04 | 已完成 | 接入及约定受控验收通过：Linux 工具检查、9 项执行器测试、真实缺陷与反例；59e2ef0 实际试跑超时 failed/timeout，待补跑；S06 已生成失败报告，见第 6 节 |
| S06 | 校验报告及证据，生成 JSON、Markdown 和批次清单 | S05 | 已完成 | control/s06-v3 部署；22 项报告/纠正测试和 9 项执行器测试通过；S05 三份历史任务已生成正式报告与批次清单，真实提交保持 failed/timeout；见第 6 节 |
| S07 | 保存报告，支持有限期复用、重审和补跑 | S06 | 已完成 | GitHub 归档、跨运行复用、同键强制重审和只重跑失败作业均已通过真实运行核对 |
| S08 | 根据已保存报告发送飞书摘要，支持仅补发 | S06；真实报告通知前完成 S07 | 已完成 | 服务器群内收件已确认。Actions 单次投递、回执归档及基于真实回执的去重已验证。异常投递验收见 S10 |
| S09 | 接入 GitHub Actions，实现 push 自动触发与分批执行 | S02～S08 | 已完成 | 验收分支自动审查及归档通过。`master` 的计划、空变更跳过及历史源码补审均有真实运行记录。修订覆盖规则后，原失败提交的强制补审已通过。`master` 自动通知已启用，真实 push `44461898674` 发出一条摘要 |
| S10 | 完成真实链路验收，交付使用说明 | S09 | 待验证 | 第 7 节记录 A-01～A-14。正式 push 自动投递已有飞书成功回执。A-01 待确认含可审查源码的同一次正式 push 及群内显示，A-09、A-10 待真实异常证据 |
| S11 | 在同一台服务器完成审查并直发简短飞书摘要 | S09 | 待验证 | 工作流与 Python 入口已改造。上线需将现有飞书配置交给 Runner 用户，并验证真实 push 对应的飞书投递 |

状态统一使用：未开始、进行中、待验证、阻塞、已完成。每完成一步，更新本表的状态与证据；代码已写但必要验证未完成时标记“待验证”。

原方案 M0～M5 阶段对应：M0 → S01；M1 → S02～S03；M2 → S04～S06；M3 → S07～S08；M4 → S09；M5 → S10。原验收编号 A-01～A-14 保持不变。

## 2. 当前流程

```text
master push → GitHub 派单 → 服务器 Runner 拉取固定 Git SHA
            → Python 调用 Pi 并校验结果 → 服务器直发飞书纯文本摘要
```

自动任务和标准手动审查都不再经过归档中转。第 3 节及后续章节保留首版的设计与验收记录，其中的归档和通知步骤用于理解历史运行。服务器直发的部署验收以实际运行、飞书回执和无报告归档为依据。

## 3. 分步骤需求、目标与验收

### S01：确认接入配置与依赖版本

**需求**：明确审查哪个仓库、哪些分支、使用什么模型，以及向哪个机器人发送通知。

**目标**：形成可填写的配置示例，锁定 Pi 版本及受信任的审查器版本。

工作内容：

- 确认目标仓库访问方式、分支、首次历史范围、审查重点、模型及机器人是否启用签名。密钥通过 GitHub Secrets 配置，不写入文档或聊天。
- 固定审查器控制 commit SHA，规则保存为 `REVIEW.md`；审查器、扩展与依赖来自控制版本，待审查提交只作为数据读取。
- 锁定 Pi 及扩展依赖版本，用该版本 `pi --help` 核对参数。控制版本更新后先验证再切换。
- 记录控制版本、规则摘要、审查配置摘要和 Pi 实际版本，便于报告追溯。

**验收**：配置项及默认值可解释；版本已锁定；模型进程只收到必要模型凭据，不继承 GitHub token、飞书凭据或其他业务密钥。

### S02：确定提交范围

**需求**：一次 push 有多个提交时，每个新增提交都要有审查记录。

**目标**：支持单提交、本地 base/head 范围、GitHub push 事件三种输入，不能只审查最后一个提交。

工作内容：

- 普通 push 按 after 可达、before 不可达的集合枚举，采用 `git rev-list --reverse --topo-order <before>..<after>`。
- 枚举新增集合不使用 `--first-parent`，也不把事件 `commits` 数组作为唯一来源；只在单个合并提交生成 diff 时取第一父提交。
- 校验仓库、分支、事件和 commit SHA；Git 调用使用参数数组，不拼接模型或事件提供的 Shell 文本。

| 特殊情况 | 处理规则 |
| --- | --- |
| 根提交 | 使用对应 Git 对象格式的空树作为 diff 基准 |
| 新分支、before 全零 | 默认要求指定 base；只有显式配置才审查 after 的全部可达历史 |
| 强制推送 | 仍按可达性集合处理，并记录强推标记 |
| 删除分支、空范围 | 明确跳过或记录空范围，不调用模型 |
| 缺少 Git 对象 | 在只读授权范围内尝试 fetch；仍缺失则失败并记录 SHA |
| 超过自动处理上限 | 保存范围与待处理清单，显式分批补审；不能静默只取前 N 个 |

**验收**：单提交、多提交、根提交、合并、新分支、强推、删除分支和缺失对象的结果符合规则；所有未处理提交都有记录。对应 A-01、A-02、A-06、A-12、A-13。

### S03：准备正确版本的源码与差异

**需求**：审查提交 A 时读取 A 的源码，不能误用后续已经修复的版本。

**目标**：每个提交都有独立的 current、parent、diff 和元数据。

工作内容：

- 从 Git 对象导出快照，不在开发者工作区执行 checkout、reset 或 clean；不执行工作树过滤器或目标仓库脚本。
- 非根提交使用第一父提交比较，关闭 external diff 和 textconv，保留重命名信息和准确行号。
- 校验导出路径及链接，拒绝绝对路径、目录逃逸和危险链接；根提交 parent 为空，不得引用不存在的父版本。
- 记录二进制、子模块、LFS 指针及超大文件的覆盖限制。
- 对快照、diff、提交说明等模型输入统一过滤受限内容，不能只过滤文件却把密钥从其他输入送给模型。过滤不保证识别所有秘密。

**验收**：A 引入问题、B 修复问题时，审查 A 仍读取 A；重命名、删除、空格和 Unicode 路径映射正确；限制有记录。对应 A-03、A-06、A-08。

### S04：实现只读工具

**需求**：Pi 能主动搜索调用关系和读取相关文件，同时只能访问允许的快照。

**目标**：提供以下三个自定义工具，不开放任意 Shell、写文件或业务网络请求。

| 工具 | 功能 |
| --- | --- |
| `review_read` | 读取指定版本、相对路径和行范围，返回真实行号 |
| `review_search` | 在指定版本与允许目录搜索固定字符串，返回文件和行号 |
| `review_list` | 列出允许查看的目录和文件 |

工作内容：只允许 current、parent 两个根目录；校验实际路径和链接，防止符号链接或 Windows junction 越界；读取、搜索和列表使用同一过滤策略；设置大小、条数及时间上限，超限返回 `truncated`。凭据、控制目录、通知配置与结果目录不得暴露给工具。

**验收**：`../`、绝对路径、链接和过滤绕过均被拒绝；仓库中的“执行命令、发送密钥”等指令不能改变工具权限。工具访问限制不等同于完整操作系统沙箱。对应 A-08。

### S05：接入 Pi 审查

**需求**：按用户规则追踪业务链路，输出有依据的发现，而不是只对 diff 作一次问答。

**目标**：每个提交启动独立会话，Pi 可以按需调用只读工具，返回一个结构化结果。

工作内容：

- `REVIEW.md` 固定审查方法、证据要求、输出格式和边界；提交说明与源码作为数据输入，不能改写规则或发送目标。
- 禁用目标仓库自动加载的配置、扩展、skills、上下文及 MCP；仅显式加载受信任的工具扩展。实际暴露工具集合必须验证。
- Python 管理 Pi 子进程、输出与总超时；终止时回收子进程。限流或临时服务错误最多重试 2 次，计入预算；认证失败、模型不存在不重试。
- 仅报告本次提交引入或明显恶化的缺陷；排除旧问题、纯风格意见与无依据猜测。缺少证据时明确说明，不伪造运行结果。
- 首版使用 print 模式读取最终文本；若使用 JSONL 事件模式，须另做事件解析，不能直接当作业务报告。

**验收**：真实模型至少完成一个已知缺陷与一个上游已有校验的反例，人工复核证据；超时与认证失败明确失败；独立提交不串会话。模型超时可能已经计费，重试不承诺无额外费用。对应 A-04、A-05、A-07、A-08。

### S06：校验并生成报告

**需求**：报告既可阅读，也能由程序判定是否完成；每个发现可定位到对应提交的代码。

**目标**：保存 `review.json`、`review.md`、`execution.json` 和批次 `manifest.json`。

工作内容：

- 模型返回摘要、发现、待验证项和覆盖限制；SHA、任务标识、版本、时间、链接和通知状态由程序填充或核对。
- 使用 JSON Schema 校验单个 JSON 对象、必填字段、枚举和长度上限；不靠正则从混杂文字中截取任意大括号。
- 校验证据文件及行号真实有效；变更锚点关联本次 diff。新增用 current/right，删除用 parent/left，重命名使用对应版本路径；相关证据允许位于未修改文件。
- 输出或引用错误最多纠正 1 次，计入单提交总预算；仍无效则 `failed/invalid_output`，不能丢弃问题后变成“无发现”。

| 结果 | 判定要求 |
| --- | --- |
| `completed + issues_found` | 按范围完成，且至少有一个有效发现 |
| `completed + no_findings` | 完成审查且没有阻止判断的关键覆盖缺口；不代表代码绝对正确 |
| `incomplete + inconclusive` | 关键范围未覆盖；保留已经校验的发现并展示缺口 |
| `failed` | 准备、模型或输出失败，未形成有效审查 |
| `skipped` | 删除分支等不适用情况；复用报告保留原报告状态，执行记录另记 `reused` |

**验收**：无效路径、行号、锚点、根提交 parent 引用均能拒绝；超时、空输出、关键文件未读不能变为 `no_findings`。程序只能验证引用与格式，业务结论仍需人工复核。对应 A-04～A-08。

### S07：保存、复用与补跑

**需求**：报告可追溯，重跑能识别已有结果；补发通知不重复调用模型。

**目标**：在 artifacts 保留期内按审查键查找可复用的完整报告。

工作内容：

- 审查键包含仓库、commit、比较基准、协议版本、控制版本、规则摘要、审查配置摘要及 Pi 版本；模型与供应商属于配置摘要。规则若依赖分支，也纳入对应语义。
- 分页查询未过期 artifacts，下载并核验清单、键与完成状态；同名 artifact 不直接代表成功，Actions cache 不用于强幂等。
- 每批清单列出预期、已处理、失败和未处理 SHA；保留独立尝试记录。
- 查询或下载失败时默认停止自动复用判断并报告存储错误，不能当作“没审过”静默重复调用；如允许重复，须显式配置。
- 分开提供重审、失败补跑、历史范围补审和仅补发通知入口。

**验收**：相同键可复用；规则或模型变化后不误用；过期后允许重审；重复尝试可识别。首版默认保留 30 天，不承诺保留期外去重、并发原子去重或通知恰好一次。对应 A-11、A-13。

### S08：飞书摘要与补发

**需求**：每个批次收到一条可读摘要，并能查看完整报告。

**目标**：发送结果明确区分成功、失败与不确定，不影响已保存的审查结论。

工作内容：

- 顺序为：完成报告 → 上传报告 artifact → 发送摘要 → 保存并上传通知回执。报告上传失败不发送“完整成功”。
- 摘要包含批次号、仓库、分支、提交链接、逐提交状态、P0/P1/P2 数量、少量重点发现和 Actions 运行链接；不把工具过程群发，不声称 artifact 链接永久有效。
- 默认无发现也通知，可配置仅问题或失败通知；长消息明确截断展示，完整报告不截断；私有仓库报告仍要求访问权限。
- Webhook 和签名密钥仅供通知步骤使用；按飞书实际签名与消息限制实现，处理转义、HTTP 状态、响应解析及业务成功码。HTTP 200 不等于发送成功。
- 业务明确拒绝记 `failed`；超时、回执丢失或状态不明记 `unknown`，不自动重发。人工确认后才使用仅补发入口。

**验收**：模拟成功、200+业务错误、超时、无效响应及发送后回执保存失败；真实测试群中验证一条摘要和签名。仅补发不调用模型。真实投递需要明确的目标与授权，本次整理不发送消息。对应 A-09、A-10、A-11。

### S09：GitHub Actions 自动触发

**需求**：push 自动启动，连续推送不能因并发配置静默丢失。

**目标**：通过 `plan → review → notify` 三类作业完成分批审查、归档与通知。

工作内容：

- `push` 使用确认后的分支；`workflow_dispatch` 支持单提交、base/head、强制重审和仅通知补发。
- 默认每批 10 个提交，批内顺序执行，matrix 最多并行 2 批；每次 push 保留独立运行。
- 不用同名 concurrency group 充当可靠 FIFO 队列；严格跨运行排队需要后续持久队列，首版不承诺。
- checkout 准备必要历史、不保留凭据；固定控制 SHA、依赖锁及已审核的官方 Action SHA；不执行待审查快照的安装、构建或测试脚本。
- 最小只读权限访问源码及 artifacts；模型步骤不拿飞书密钥，通知步骤不拿模型 Key。作业间通过 artifacts 传递报告，大 JSON 不塞入输出变量。
- 保存已经生成的失败摘要与报告；有正式发现不等于执行失败，基础设施故障和未覆盖范围则明确显示。硬中断可能无法保存终态，后续不能推断为成功。

**验收**：测试分支单提交、多提交、连续 push 均有执行记录；报告版本正确，分批与未处理清单准确；失败报告可查。批次作业初始超时约 90 分钟，依据实测调整。对应 A-01、A-02、A-12～A-14。

### S10：真实验收与使用说明

**需求**：证明完整链路可用，能够启用、补跑、停用及排查问题。

**目标**：按需求文档 A-01～A-14 逐项记录结果和未覆盖限制。

依次验证：本地固定提交真实模型审查 → 测试分支 push → 指定飞书测试群投递 → 重审及仅补发 → 目标分支连续 push 试点。真实发送以具体授权为准。

验收还需覆盖：受限路径及恶意指令、A 引入/B 修复、Git 文件变化、大批量上限、通知结果不明、输出无效、重试和预算。记录命中、漏报、误报与人工复核；少量案例不换算成普遍准确率承诺。

**验收**：每项有实际结果；明确区分静态检查、模拟接口、真实模型、真实 GitHub 运行和真实飞书投递。未执行保持“待验证”。不执行目标项目构建或 Maven 来替代静态审查。

交付说明至少包含配置、首次启用、历史补审、重审、仅补发、停用及常见错误。停用工作流不会自动停止已经运行的任务，需要单独处理。

## 4. 实施时使用的约定

以下为分工和接口约定。存储、Git 范围与事件、批次审查和通知入口均已实现，运行与验收状态见第 1 节和第 7 节。

### 4.1 文件分工


| 位置 | 用途 |
| --- | --- |
| `.github/workflows/ai-review.yml` | 自动与手动入口、分批作业和归档 |
| `review/REVIEW.md`、`config.example.json` | 审查规则与普通配置 |
| `review/review_push.py`、`git_source.py` | 执行编排、范围、Git 快照和差异 |
| `review/review-tools.ts`、`pi_runner.py` | 受信任只读工具、Pi 子进程及预算 |
| `review/report.py`、`schemas/review-v1.json` | 输出校验、报告及摘要生成 |
| `review/review_one.py` | 已过滤单提交的模型调用、一次输出纠正及正式报告入口 |
| `review/storage.py`、`schemas/artifact-v1.json` | 本地报告归档、GitHub artifacts 只读查询及包校验；上传由 Actions 执行 |
| `review/review_batch.py` | 对已过滤输入按计划复用、强制重审或失败补跑 |
| `review/feishu_summary.py` | 从已保存正式批次生成完整汇总、飞书富文本消息文件及可读预览；不发送请求 |
| `review/notify_feishu.py` | 通知及仅补发，尚未实现 |
| `review/requirements.txt`、`package.json`、`package-lock.json` | 控制程序的依赖及准确版本 |
| `review/tests/fixtures/` | Git 历史与人工标注样例 |

运行文件使用独立临时路径 `run/<run-id>/<commit-sha>/`：`input/current/`、`input/parent/`、`input/commit.diff`、`input/metadata.json`、`request.md`、`result/`。控制代码、目标快照与结果分离，快照不参与依赖安装。

### 4.2 默认配置

| 配置 | 初始约定 |
| --- | --- |
| 监听分支、供应商、模型 | 必须明确配置；`main` 不是已确认分支 |
| 单提交总超时 | 480 秒，包含模型重试及输出纠正 |
| 批大小 / 批并行度 / 单次 push 上限 | 10 / 2 / 50，超限列出待处理范围 |
| 单次文件读取上限 | 262144 字节，超限标记截断；关键证据受影响则审查未完成 |
| 新分支策略 | 默认 `require_base`；`all_reachable` 必须显式选择 |
| artifact 保留期 | 建议 30 天，实际以平台及仓库限制为准 |
| 通知策略 | 默认 `all`，可改仅问题或失败 |
| 时间 | 保存 UTC；展示标明时区，默认 Asia/Shanghai |

凭据：模型 Key 使用选定供应商在 Pi 中的实际变量名；飞书使用 `FEISHU_WEBHOOK_URL`，启用签名再配置 `FEISHU_SIGNING_SECRET`；GitHub 使用最小只读权限 token。密钥不进入配置摘要、报告或日志。

### 4.3 CLI 入口

当前已实现 `review_one.py`，接收**已过滤的单提交输入**，运行模型、最多纠正一次输出并生成正式报告。`report.py` 仅离线校验既有模型任务，不重新调用模型；`storage.py` 仅归档已有正式报告；`review_batch.py` 在调用前查询可复用报告。仓库提交范围与 GitHub 事件入口仍归 S02，下面的 `review_push.py` 示例尚未实现。

服务器已将 Python 依赖安装在独立目录。以下为可运行的离线整理示例，来源控制目录必须对应当时执行模型的版本：

```bash
PYTHONPATH=/root/ai-commit-review/runtime/s06-python-packages \
python3 /root/ai-commit-review/control/s06-v3/report.py \
  --repository zhaozhixuan-code/zzx-ai-code \
  --control-directory /root/ai-commit-review/control/s05-v2 \
  --attempt /root/ai-commit-review/results/s05-v2/commit-59e2ef0-ur64pk9g \
  --output-root /root/ai-commit-review/results/s06
```

该示例整理已有超时任务，生成失败报告，CLI 返回 1 是预期结果。`review_one.py` 使用同一依赖目录，参数为 `--input`、`--output-root`、`--repository`，可选 `--branch`、`--run-url`、`--timeout` 和 `--model`；模型 Key 从环境取得。生成目录包含报告三件套及批次清单。

S07 批次入口接收可信计划，不能把 GitHub 事件正文直接当作计划。输入格式如下；SHA 及路径必须替换为实际完整值，最多 50 个预期提交，尚未准备输入的 SHA 仍列在 `expected_commits` 中：

```json
{
  "schema_version": "review-plan.v1",
  "repository": "zhaozhixuan-code/zzx-ai-code",
  "branch": null,
  "expected_commits": ["完整提交SHA"],
  "inputs": [{
    "commit_sha": "完整提交SHA",
    "base_sha": "完整第一父提交SHA，根提交为null",
    "input_directory": "/absolute/path/to/filtered-input"
  }]
}
```

以下命令会在无有效复用报告时调用模型；本轮验收没有对业务提交执行这些模型命令。默认本地查询，追加 `--github-artifacts` 才查询 GitHub，读取 token 的变量为 `GITHUB_TOKEN`：

```bash
export PYTHONPATH=/root/ai-commit-review/runtime/s06-python-packages

python3 /root/ai-commit-review/control/s07-v2/review_batch.py \
  --plan /absolute/path/to/plan.json \
  --output-root /root/ai-commit-review/results/s07 \
  --store-root /root/ai-commit-review/artifact-store
```

在同一入口追加 `--force-review` 绕过复用并保留新旧尝试；追加 `--retry-failed /absolute/path/to/previous/manifest.json` 只选择失败、未完成及未处理提交，两种模式互斥。`--retention-days` 默认 30、范围 1～90；查询错误默认停止，只有显式 `--allow-duplicate-on-storage-error` 才允许继续模型审查，并记录原因。报告生成后归档失败时，审查结论不被改写，存储错误单独记录。

不调用模型的归档入口为 `storage.py --report-directory ... --store-root ... --stage-root ...`；输出本地 ZIP 和供后续 `upload-artifact` 使用的四文件目录，**不会上传 GitHub**。`load_saved_batch()` 可核验并读取既有批次，供 S08 仅补发使用；正式通知和补发使用 `notify_feishu.py`，操作见文末 S08 发送器部署记录。历史范围的枚举仍需 S02，不属于这个计划执行入口。

S08 已实现仅生成摘要的入口；以下命令读取服务器上的历史真实报告，不启动模型、不读取机器人凭据、不发送飞书：

```bash
PYTHONPATH=/root/ai-commit-review/runtime/s06-python-packages \
python3 /root/ai-commit-review/control/s08-summary-v1/feishu_summary.py \
  --manifest /root/ai-commit-review/results/s06/report-batch-1y58bups/manifest.json \
  --output-root /root/ai-commit-review/results/s08 \
  --source-note '本批次含两个未推送到 GitHub 的受控样例和一次真实业务提交的超时记录；样例链接不能作为远端可访问报告。'
```

输出独立目录内包含 `summary.json`（完整汇总）、`message.json`（未签名的紧凑富文本消息）、`summary.txt`（可读预览）及 `preparation.json`（来源摘要、展示省略数量及准备状态）。默认 `--policy all`；可选 `issues_or_failures` 或 `failures_only`，后者同样包括未完成、未处理和基础设施错误。这些策略只计算 `should_notify`，此入口均不发送。当前未核验 GitHub 归档，所以 `send_ready=false`，不得把摘要生成成功当作报告已经上传或通知成功。

以下为后续范围、事件与通知入口约定，尚未实现：

```bash
# 单提交，只审查、不通知
python review/review_push.py --repo /path/to/repo --commit <SHA> --no-notify

# Actions push 事件
python review/review_push.py --event "$GITHUB_EVENT_PATH" --no-notify

# 指定范围，可用于分批历史补审
python review/review_push.py --repo /path/to/repo --base <SHA> --head <SHA> --no-notify

# 读取已有批次清单，仅发送通知
python review/notify_feishu.py --manifest /path/to/manifest.json
```

`--force-review` 仅强制重审，`--force-notify` 仅控制明确补发，两种操作不能绑在同一个开关中。

Pi 参数以锁定版本验证结果为准。官方当前 CLI 文档支持以下启动形式；实施前须验证工具暴露、资源禁用与输出行为：

```bash
pi --print --no-session \
  --no-extensions --no-skills --no-prompt-templates \
  --no-context-files --no-approve --no-mcp \
  --extension /trusted/control/review-tools.ts \
  --tools review_read,review_search,review_list \
  --provider "$PI_PROVIDER" --model "$PI_MODEL" \
  --append-system-prompt /trusted/control/REVIEW.md \
  @/run/request.md
```

Python 以参数数组启动，不将这些示例拼成含不可信输入的 Shell 命令。`--tools` 本身不保证禁用 MCP，因此要同时禁用对应资源并检查最终工具集合。`--mode json` 输出 JSONL 事件，不等同于 `review.v1` 报告。

### 4.4 报告字段与状态

| 字段组 | 必须保留的信息 |
| --- | --- |
| 标识 | `schema_version=review.v1`、`review_key`、`attempt_id` |
| 审查对象 | `repository`、`branch`、`commit_sha`、`base_sha`（根提交为 null）、提交及运行链接 |
| 可追溯配置 | `controller_revision`、`rules_sha256`、`review_profile_sha256`、`pi_version`、`provider`、`model` |
| 结果 | `review_status`、`verdict`、`summary`、`findings`、`open_questions`、`limitations` |
| 时间 | `started_at`、`finished_at`，UTC ISO 8601 |
| 单条发现 | `id`、P0/P1/P2 严重级别、标题、触发条件、变更锚点、证据、影响、建议、验证方式 |
| 变更锚点 | `revision`、`file`、`line`、`side`；必须关联本次变化 |
| 证据 | `revision`、`file`、`start_line`、`end_line`、`explanation`；文件及行号真实有效 |
| 验证说明 | `kind`、`executed`、`note`；静态阅读不写成已运行验证 |
| 批次清单 | 预期、完成、失败、未完成提交与报告引用 |

审查键计算约定：

```text
review_key = SHA256(
  repository_identity + commit_sha + base_sha_or_empty_tree
  + schema_version + controller_revision + rules_sha256
  + review_profile_sha256 + pi_version
)
```

实现时使用稳定、无歧义的字段编码。相同键不保证模型再次调用会得到完全相同结果。

执行阶段：`pending → preparing → reviewing → validating → finished`，异常可进入 `failed`。最终审查状态与执行阶段分开；通知状态另记 `not_requested/pending/sent/failed/unknown`，通知失败不改写审查结论。硬超时后找不到完成记录只能视为未确认完成。

### 4.5 常见问题排查

| 现象 | 优先查看 |
| --- | --- |
| push 没有触发 | 分支配置、工作流所在分支、Actions 是否启用 |
| 提交缺报告 | plan 清单、上限、分批结果与未处理 SHA |
| 读成最新代码 | 快照 metadata、工具根路径及被审查 SHA |
| 有报告无飞书 | 通知状态、HTTP/业务码、签名与回执 |
| 重复消息或额外费用 | 并发任务、审查键、重试、回执丢失及强制重跑 |
| 未读完关键代码却称无问题 | 应为 `incomplete/inconclusive`，检查结果映射 |

首版 artifacts 不承担永久数据库、可靠队列或事务性通知的职责。只有确实需要多仓库监听、严格排队、长期去重、隔离复现或 PR 门禁时，再设计对应服务与权限。

## 5. 官方文档与版本证据

2026-11-18 已核对 Pi 1.5.2 的官方资料，并在服务器验证该版本加载只读工具扩展。当前安装包为 `@earendil-works/pi-coding-agent@1.5.2`，Node.js 为 22.22.1；项目依赖锁文件与受信任控制 commit 仍属于 S01 待完成项。

| 官方文档 | 已核对的用途 | 仍需验证 |
| --- | --- | --- |
| [Pi 1.5.2 CLI](https://github.com/earendil-works/pi/blob/v1.5.2/packages/coding-agent/docs/cli.md) | print 输出、工具选择、禁用自动资源、显式扩展、禁用 MCP；真实模型会话已验证三个工具及最终 JSON 候选输出 | 完整报告与引用校验归 S06 |
| [Pi 1.5.2 扩展](https://github.com/earendil-works/pi/blob/v1.5.2/packages/coding-agent/docs/extensions.md) | `pi.registerTool()`、`getActiveTools()`、工具调用和执行结束事件；真实样例记录了工具集合、代码读取与调用链搜索 | 少量恶意注释样例不等于完整安全沙箱验证 |
| [Pi 1.5.2 设置](https://github.com/earendil-works/pi/blob/v1.5.2/packages/coding-agent/docs/settings.md) | 独立配置目录、关闭压缩与缓存保温；Agent 重试 maxRetries=2、供应商层 maxRetries=0；父进程统一控制总超时 | 本轮真实模型未触发限流或临时服务错误；不能写成真实重试验证通过 |
| [jsonschema 4.23.0 官方校验说明](https://github.com/python-jsonschema/jsonschema/blob/v4.23.0/docs/validate.rst) | `Draft202012Validator`、`check_schema`、完整对象校验和 format 默认不强制检查；实现使用本地 `$defs`，UTC 时间另由程序核对 | Schema 与引用合法不代表业务判断正确；无需远端获取 schema |
| [GitHub 官方 REST OpenAPI](https://github.com/github/rest-api-description/blob/main/descriptions/api.github.com/api.github.com.json) | 实际获取并核对 artifacts 列表分页、公开仓库只读权限、`expired/expires_at` 与可空 `digest`；下载 API 返回 302，签名链接约一分钟有效，可能返回 410；请求版本为 `2022-11-28` | 列表真实读取成功；分页、下载与错误通过受控响应验证，真实归档上传/下载尚未发生 |
| [飞书自定义机器人官方指南](https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot.md) | 实际读取官方 Markdown；已核对签名的空消息 HMAC-SHA256、秒级时间戳、20 KB 请求体上限、`post/zh_cn/content` 富文本结构、文本和链接节点，以及 HTTP 与业务 `code` 两层判断 | 真实带签名文本连接已收到；本轮只生成富文本文件，富文本群内渲染、正式发送器及异常回执尚未验证 |
| [GitHub runner v2.338.2 官方发行版](https://github.com/actions/runner/releases/tag/v2.338.2)、[添加 runner](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/add-runners)、[配置服务](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/configure-the-application) | 实际核对官方发行资产、SHA-256、注册 token 一小时有效期及指定用户安装 systemd 服务；服务器安装包摘要与官方值一致，CLI 真实注册成功，服务日志确认连接和等待任务 | runner 接入不等于已上传报告或启用自动审查；工作流、专用用户可访问的审查运行时和报告交接目录仍待后续配置 |

GitHub push、并发和 artifact 上传配置，实施对应步骤时再查阅并记录已核对结论。GitHub artifacts 列表成功不等于上传、下载及跨 Actions 运行复用已经验收；飞书连接成功也不等于报告已归档或摘要投递已完成。

## 6. 验收结果记录

详细场景沿用[需求文档第 7 节](01-requirements.md#7-验收标准)的 A-01～A-14，不在这里重复维护另一套验收编号。

| 验证范围 | 当前结果 | 完成后填写 |
| --- | --- | --- |
| Git 范围、版本隔离 | 部分验证 | 当前普通提交及父提交的快照已准备；根提交、合并、强推、多提交及通用版本隔离测试未执行 |
| 只读工具 | 服务器工具层验证通过 | Linux 定向测试、真实过滤快照读取、Pi 1.5.2 扩展加载通过；证据见下方 S04 记录 |
| 报告校验 | 已完成本阶段验收 | 22 项 Linux 报告/纠正测试通过；真实历史模型输出完成离线校验，已生成 JSON、Markdown、执行记录和批次清单；见 S06 记录 |
| 报告归档与复用 | 真实 GitHub 归档与认证读取已验证 | 20 项 S07 测试通过；2 份完成报告本地复用；Actions 真实上传和下载，以及服务器签名链接下载均核对四份报告文件；服务器自动认证待接入 |
| 模型审查质量 | 受控样例验证通过；业务试跑未完成 | 真实 DeepSeek 命中越权缺陷，正确排除上游校验反例；59e2ef0 试跑触发 480 秒超时；不据两个样例计算普遍准确率 |
| 飞书模拟与补发 | 未执行 | HTTP/业务错误、超时、不确定回执和仅补发结果 |
| GitHub 测试分支 | 未执行 | 运行链接、提交范围、分批及 artifact |
| 真实模型 + GitHub + 飞书完整链路 | 未执行 | 目标与范围、运行及消息记录、遗留问题 |

后续每次完成开发或验证，先更新进度总表，再将必要证据填入本节。文档整理不改变任何功能的实际完成状态。

### S04 验证记录：2026-11-18

- 代码：[只读工具](../../review/review-tools.ts)、[工具定向测试](../../review/tests/review-tools.test.mjs)。工具只接受 `current`、`parent` 与过滤清单内路径；读取时校验普通文件、真实路径、256KiB 上限及 SHA-256；搜索、读取和列表均有结果限额或截断标记。
- 服务器部署：`/root/ai-commit-review/control/review-tools.ts`。证据：`/root/ai-commit-review/control/s04-verification-nosbxl19/summary.json`；该目录同时保存测试 stdout/stderr、Pi help 加载结果和真实输入预检结果。服务器进度另存于 `/root/ai-commit-review/PROGRESS.md`。
- 工具源码 SHA-256：`a8f9c79447c8741e964612da1bc7a3a7b843c8645e1bab4f86be181bf33fea46`，本机交付文件与服务器部署文件一致。
- Linux 定向检查通过：三个工具的注册与其他工具拦截、正确版本、LF 行号、路径逃逸、绝对路径、白名单外文件、目录范围、分页、截断、搜索结果上限、内容篡改及符号链接拦截。本机 Windows 的符号链接创建受权限限制，服务器 Linux 补充验证通过。
- 使用真实过滤输入 `runs/59e2ef0-Zy7ijz/input-zxvs8ben` 验证：当前版本 `ScreenshotDriverFactory.java` 为 145 行，父版本 `ScreenshotServiceImpl.java` 为 103 行；搜索返回受限结果；父版本不存在的新文件、越界路径与已过滤 `application.yml` 均拒绝读取。
- 当前提交：`59e2ef02f7a1335c55ff7cdb2daf5e0d17954114`；第一父提交：`e25e41ec48add190d4ab7ad6e92a5ab87314554f`。本次验证不执行待审查仓库代码，不调用真实模型；实际模型审查、仓库提示注入行为及报告有效性仍需 S05～S06 验证。

### S05 验证记录：2026-11-18

- 代码：[审查规则](../../review/REVIEW.md)、[Pi 执行器](../../review/pi_runner.py)、[执行器测试](../../review/tests/test_pi_runner.py)、[受控 Git 样例](../../review/tests/build_model_cases.py)。服务器控制代码位于 `/root/ai-commit-review/control/s05-v2/`，使用 Pi 1.5.2、DeepSeek `deepseek-v4-pro`。不执行待审查项目代码、构建或 Maven。
- 执行器校验过滤输入清单、源码摘要及重建 diff 的一致性；每次调用使用新建配置、工作目录和无历史会话。仅向 Pi 传入模型 Key 等必要环境，不继承 GitHub、飞书或其他业务凭据；禁用自动资源与内置工具，核对实际工具集合并记录工具事件。
- Linux 模拟检查：`control/s05-v1/runner-test.log` 中 9 项测试全部通过；包含认证失败不重启、诊断脱敏、无效 JSON、未初始化或未读源码、未知工具、diff 篡改、独立配置，以及 1.5 秒超时后终止进程组。`tools-test.log` 中路径、白名单、摘要、限额及符号链接检查通过。v1 与 v2 的执行器、工具和上述测试源码摘要相同。
- 真实模型缺陷样例：`results/s05-v2/ownership-defect-x8r8h02n/`，耗时 29.162 秒，13 次工具调用、0 次工具错误。人工核对：模型命中移除买家查询条件造成的越权读取，锚点为 `current/src/demo/OrderService.java` 第 7 行；读取了父版本、Controller、仓储实现和返回记录，给出的触发条件与静态证据符合人工标注。
- 真实模型反例：`results/s05-v2/upstream-validation-9a4mdnpw/`，耗时 33.174 秒，8 次工具调用、0 次工具错误。模型读取父子版本，搜索其他调用方并确认 Controller 校验 `size` 为 1～1000，正式发现为空；未把删除重复校验误报为可达除零缺陷。两个小样例不代表普遍命中率或完整业务验收。
- 两个样例均含要求执行 Shell、创建标记文件及读取凭据的恶意源码注释。实际活动工具集合恰为 `review_list/review_read/review_search`，没有未知工具尝试；执行前后 `/tmp/s05-injection-marker` 均不存在。此结果只说明所测样例被忽略，不等同于完整操作系统沙箱。
- 首次 v1 缺陷调用保留在 `results/s05/ownership-defect-b3d104b_/`：模型添加未允许的顶层 `validation_note`，执行器明确记录 `failed/invalid_model_output`。随后明确规则只允许五个顶层字段，并补全样例仓储实现，再以 v2 新任务验证；没有丢弃问题并改写为“无发现”，尚未实现 S06 的自动输出纠正。
- 所有成功调用仅为 `awaiting_report_validation` 候选结果，`report_validation=not_performed`；完整 JSON Schema、引用、变更锚点与最终结论状态归 S06。真实限流或服务故障本轮未触发；模拟认证测试不能替代供应商真实错误的端到端验证。
- 指定提交 `59e2ef0` 的真实试跑耗时 480.086 秒，36 次工具调用、0 次工具错误、0 次重试，触发总超时并记为 `failed/timeout`；Pi 返回码为 143，确认没有该任务残留进程。结果目录为 `results/s05-v2/commit-59e2ef0-ur64pk9g/`，没有生成 `model-result.json`，不能解释为“未发现问题”。配置过滤造成的覆盖限制仍需保留；本轮未自动延长预算或重复调用，真实业务审查仍待后续预算评估和补跑。
- 验收汇总：`/root/ai-commit-review/control/s05-v2/S05-status.json`，包含控制代码摘要、样例执行记录、人工复核结果、实际试跑终态和检查结果；已核对与本机交付源码一致。所有本轮保存的模型任务文件未检出已知模型 Key，注入标记文件不存在。S05 完成的是接入及约定样例验收，正式业务报告和完整链路验收尚未完成；下一步为 S06，本轮不继续通知或自动触发。

### S06 验证记录：2026-11-18

- 实现：[报告与引用校验](../../review/report.py)、[JSON Schema](../../review/schemas/review-v1.json)、[单提交执行入口](../../review/review_one.py)、[定向测试](../../review/tests/test_report.py)。服务器部署为 `/root/ai-commit-review/control/s06-v3/`，Python 依赖安装于 `/root/ai-commit-review/runtime/s06-python-packages/`；[依赖清单](../../review/requirements.txt)固定了 jsonschema 4.23.0 及 5 个传递依赖。未修改业务代码，未执行 Maven。
- 协议：只解析完整单个 JSON 对象，拒绝重复字段、NaN、围栏及混杂文字；Schema 限制字段、类型、枚举、文本长度和数组大小。SHA、任务、版本、链接、时间、审查键和状态由控制程序填写；手动审查未指定分支或 Actions 运行链接时为 null，未实际启动 Pi 时版本可为 null。
- 源码核对：复用过滤输入的哈希和 diff 一致性检查；按原始 LF 行及相同 diff 匹配策略校验证据范围、当前/父版本与变更锚点。新增用 current/right，删除用 parent/left，重命名对应路径按各自版本校验，未修改文件可作为证据，根提交禁止 parent 引用。
- 读取范围边界：初版仅核对文件曾读取，服务器复现“只读第 1 行却引用第 2 行仍被接受”，记录保存在 `/tmp/s06-report-test-g7agsda5/job-oxemj4da`。最终版将成功调用参数与固定快照对应，只有整个请求窗口确定未触发工具的单行/字节截断时才认可行范围；引用超出范围即拒绝。窗口可能截断时保守视为未核验，不猜测实际返回了哪些行。
- 状态：模型标记未完成、输入有过滤/跳过项，或保留变更行缺少完整读取记录时，整体为 `incomplete/inconclusive`，保留有效发现。输出无效或执行失败为 `failed/inconclusive`。不得通过丢弃某条无效发现让其余空数组变成 `no_findings`；关键缺口不能因模型声称完成而被忽略。
- 输出纠正：新入口最多启动一次纠正，预检、两轮模型与校验共享总预算，逐次保留原始目录与反馈。认证及超时不作为输出错误重试；第二轮仍无效、减少发现、替换有效发现 ID、把未完成提升为完成，或首轮无法解析而第二轮仅给空数组，均保守拒绝。该机制通过受控假 Pi 验证，本轮未新增真实模型调用，未触发供应商的真实纠正或限流流程。
- Linux 验证：`control/s06-v3/report-test.log` 中 **22 项全部通过**，覆盖协议、读取范围、CRLF/Unicode 路径、删除/重命名、根提交、覆盖缺口、空发现条件、超时、凭据缺失、纠正次数/总预算与批次未处理列表。执行器另有 **9 项通过**，日志 `control/s06-v2/runner-test.log`，已核对相关源码与 v3 相同。工具边界与 Linux 符号链接检查日志为 `control/s06-v1/tools-test.log`，工具及测试源码摘要同样未变化。
- 离线整理 S05 真实输出：批次 `/root/ai-commit-review/results/s06/report-batch-1y58bups/`。越权样例为 `completed/issues_found`，1 个 P1 发现；上游校验反例为 `completed/no_findings`，0 个发现；真实提交 `59e2ef0` 为 `failed/inconclusive`、错误 `timeout`，仍无有效审查结论。每项都有 `review.json`、`review.md`、`execution.json`，批次有 `manifest.json`，原始 S05 记录保留。
- 两个样例来自独立受控 Git 仓库，没有推送 GitHub；报告只验证固定 SHA、路径编码与行号链接的生成，未验证这些样例链接可在 GitHub 打开，说明见批次 `README.txt`。离线报告 `finished_at` 是本轮校验结束时间，原模型起止时间、耗时与尝试记录仍保存在 `execution.json`。
- 验收汇总与 11 个控制文件摘要：`/root/ai-commit-review/control/s06-v3/S06-status.json`。已下载核对本地源码与服务器完全一致，报告文件未检出常见 sk 形态 Key。本阶段只验证格式、来源、引用、状态和保存结果，不证明业务结论必然正确，也未完成 S02～S03 的全部 Git 边界或 S10 完整链路验收。
- 本轮停在 S06。下一步 S07 为保存、复用与补跑；GitHub artifacts、Actions 自动触发和飞书投递均未实现，真实业务提交的超时仍需后续预算评估及补跑。

### S07 验证记录：2026-11-18

- 实现：[存储及读取适配器](../../review/storage.py)、[归档协议](../../review/schemas/artifact-v1.json)、[批次执行入口](../../review/review_batch.py)、[定向测试](../../review/tests/test_storage.py)。服务器部署 `/root/ai-commit-review/control/s07-v2/`，沿用 S06 独立 Python 依赖。控制版本纳入存储、批次及归档协议源码摘要；旧 S06 报告保留原审查键，不冒充当前控制版本的缓存命中。
- 调用前根据过滤输入、仓库、SHA、比较基准、分支、模型、规则、控制源码及预算计算审查键。仅复用同键、未过期、`completed` 且通过归档校验的报告；`failed/incomplete` 保留归档但不能视为审查完成。复用不改 `review.json/review.md`、原始尝试 ID 或审查终态，新的执行记录另写 `reused`、执行 ID、来源及 `model_calls=0`。
- 本地归档按完整审查键及尝试 ID 独立保存，默认 30 天；过期只忽略，不删除旧文件。包只允许 `artifact.json`、`review.json`、`review.md`、`execution.json`，验证大小、摘要、身份、终态及 Markdown 一致性，不解压不可信路径，不包含快照或模型原始会话。
- 默认查询错误停止调用，显式放行才可重复审查；初始化存储目录失败也会保存失败清单及剩余 SHA。归档失败与审查结论分开记录。强制重审保留两次归档；失败补跑只选择失败、未完成和未处理 SHA，原批次保持不变；读取既有报告不启动模型。历史范围枚举依赖 S02，真正的通知补发归 S08。
- Linux 验证：`control/s07-v2/S07-tests.log` 中 **20 项全部通过**；含实际 `run_one` + 受控假 Pi 的第二次执行命中缓存且总启动次数为 1。报告回归 **22 项通过**，执行器回归 **9 项通过**，日志分别为 `S06-report-regression.log`、`S05-runner-regression.log`。GitHub 模拟覆盖分页、过期、302 下载、凭据不转发、403/410/500、错误正文、摘要和内部键不符；这些不是真实 GitHub 下载验证。
- 真实历史报告核对：`/root/ai-commit-review/results/storage-check-rboeen4l/summary.json`。S06 三份报告均完成 ZIP 保存及字节一致性核对；越权样例与上游校验反例可按原键读取，另存的复用执行均为零模型调用。真实业务提交 `59e2ef0` 仍是失败报告，验证了不可复用为已完成；模拟到第 31 天后，三份归档均被忽略且原文件仍存在。本轮新增真实模型调用为 0。
- GitHub 真实只读查询由新适配器完成：公开仓库 `zhaozhixuan-code/zzx-ai-code` 列表读取成功、artifact 数量为 0。尚无可下载对象，也未上传报告；不能宣布 GitHub 报告存储或跨运行复用已打通。所需上传目录已具备，真实上传、下载和平台保留期需 S09 Actions 接入后验收，S07 总状态因此为“待验证”。
- 汇总及 13 个控制/测试文件摘要：`/root/ai-commit-review/control/s07-v2/S07-status.json`。本轮停在 S07，未执行 Maven、Git 提交/推送、飞书消息或 Actions 自动触发。下一步可实现 S08 飞书摘要与仅补发的模拟流程，真实发送仍需目标配置及前置条件满足。

### S08 配置记录：2026-11-18

- 用户已创建目标群“赵志轩, 赵钦圣”的自定义机器人，并确认开启签名校验。按本轮要求直接配置服务器，不使用模拟接口或伪造发送回执。
- 配置保存在服务器 `/root/ai-commit-review/secrets/feishu.json`，只包含目标名称、Webhook 与签名密钥；文档和代码不记录凭据值。已确认 JSON 回读一致、Webhook 格式合法、签名密钥非空，文件为 root 所有且权限 `0600`，父目录权限 `0700`。
- 本次完成的是服务器凭据配置，没有向飞书发送消息，也没有验证签名是否被飞书接受；通知代码、真实连接验证与报告投递仍未完成。下一小步为目标群的一条真实连接验证消息；正式报告通知仍依赖 S07 的真实 GitHub 归档，后续需要配置 GitHub 时及时告知用户。

### S08 真实连接验证：2026-11-18

- 已从服务器向“赵志轩, 赵钦圣”发送 1 条带签名的真实消息，标题为“AI 代码审查｜飞书连接验证”；北京时间 2026-11-18 22:27:31，验证编号 `8442ae64f3334a5da24bb5a887645979`。
- 飞书返回 HTTP `200`、业务 `code=0`，回执状态 `sent`；实际请求 1 次、自动重试 0 次。用户随后贴出群内消息，目标群接收已确认，编号与服务器回执一致。
- 回执保存在 `/root/ai-commit-review/results/s08/connection-check-_ppp972s/receipt.json`，同目录 `message.json` 保存不含签名的消息正文；未输出或把机器人凭据写入文档。本小步使用真实飞书接口，没有执行模拟发送。
- 连接验证完成不等于报告通知已完成。本轮停在此小步；正式摘要、仅补发入口、报告与回执的 GitHub 归档仍待实现和验证，GitHub 自动触发尚未启用。

### S08 摘要生成验证：2026-11-18

- 实现：[摘要生成入口](../../review/feishu_summary.py)，已部署到 `/root/ai-commit-review/control/s08-summary-v1/`。复用 S07 的正式报告读取及完整性检查，另核对批次的预期、已处理、未处理及失败集合。保留有效发现与原审查状态；只有执行失败记录而没有正式报告的条目不能被提升为“无发现”。此入口不读取机器人配置、不调用模型、不发送 HTTP。
- 生成完整汇总、未签名 `post` 消息和可读预览。模型文字只进入普通文本节点，提交与源码链接由仓库、固定 SHA 和锚点生成；重点最多展开 3 个，未签名消息限制为 18,000 个 UTF-8 字节。超限时标明展示省略数量，原报告及完整汇总不被截断。未核验归档时明确显示没有线上报告入口，`send_ready=false`，不伪造 Actions 链接。
- 使用原批次 `results/s06/report-batch-1y58bups/manifest.json` 的三份真实历史输出，实际生成结果为：预期/已处理 3、完成 2、失败 1，P0=0、P1=1、P2=0；`59e2ef0` 保持 `failed/inconclusive/timeout`。来源说明明确两个受控样例没有推送 GitHub，不把它们的链接当作远端可访问报告。
- 消息体实际为 **2,378 字节**，4 个输出文件保存于服务器 `/root/ai-commit-review/results/s08/feishu-summary-661z1kam/`。核对消息与汇总摘要、3 个提交及 1 个重点发现；原始 10 个清单/报告文件前后 SHA-256 一致。用相同真实批次核对三种策略均应通知，未伪造单提交数据或模拟 HTTP 响应；无问题批次的策略过滤分支仍未验证。
- 验证汇总位于 `/root/ai-commit-review/control/s08-summary-v1/S08-summary-status.json`。本轮新增 HTTP 请求、真实模型调用和群消息均为 0；未运行此前的假 Pi/模拟接口测试。长消息实际截断、无问题批次的条件过滤、富文本群内渲染、发送失败回执与仅补发仍待后续验证。
- 本小步完成摘要生成，S08 整体保持“进行中”。正式报告投递前还需 GitHub 实际归档及发送器；需要用户配置 GitHub 时单独明确告知，不使用模拟结果替代接入。

### S09 runner 接入验证：2026-11-18

- 用户已从目标仓库 Settings → Actions → Runners 提供临时注册命令。本轮完成专用服务器 runner 接入，尚未创建工作流或启用 push 自动审查，也没有发送新飞书消息。
- 安装 GitHub 官方 `actions/runner` **2.338.2**，Linux x64 安装包大小 `228071396` 字节；本机下载和服务器传输后的 SHA-256 均为 `baebf204307bac6193480852732f0aba2ae97b6c5597c244f4d5558390147c40`，与官方发行资产及发行说明一致。服务器初次直连下载过慢的局部文件未用于安装，未删除历史文件。
- 安装目录 `/opt/ai-commit-review-runner`，以专用系统用户 `ai-review-runner`（UID 998）运行；runner 名称 `zzx-ai-review`、自定义标签 `ai-commit-review`，使用 `--no-default-labels`，后续专用工作流应匹配该标签。注册没有使用 `--replace`，未替换其他 runner。
- 真实注册日志确认 `Connected to GitHub`、`Runner successfully added` 和 `Settings Saved`，注册 agent ID 为 2。配置文件使用 .NET 写出的 UTF-8 BOM，核对时按 `utf-8-sig` 读取，没有因此重复注册。临时 token 未写入项目或进度文档。
- systemd 单元为 `actions.runner.zhaozhixuan-code-zzx-ai-code.zzx-ai-review.service`，已验证 `User=ai-review-runner`、`ActiveState=active`、`SubState=running`、`UnitFileState=enabled`。日志在 UTC 14:59:46（北京时间 22:59:46）确认 `Listening for Jobs`，截至本轮核对没有任务执行记录。专用用户实际读取 root 飞书配置的权限检查被拒绝。
- GitHub 公开工作流列表真实返回数量为 0；另一次队列查询遇到匿名 API 限流，未将它记为“队列为空”。runner 的实际连接与空闲判断依据注册结果和服务日志。本轮未执行模拟接口或假 Pi 测试，未触发模型审查或消息发送。
- 服务器证据目录 `/root/ai-commit-review/control/s09-runner-v1/`：`status.json`、脱敏 `registration.log`、服务安装/启动日志及 `connection-evidence.log`。关闭 SSH 后由 systemd 继续维持 GitHub 任务监听；审查和通知仍设计为每次触发的一次性脚本，不轮询报告目录。
- 本小步完成 runner 接入，S09 整体为“进行中”。下一步准备仅手动触发的真实报告上传工作流，并验证 GitHub artifact 上传/下载；S07 真实归档和 S08 正式发送仍未完成。Pi、Python 依赖和既有报告目前位于 root 私有目录，尚未向新用户开放；后续需配置专用运行时和只读报告交接目录，不能把 runner 在线等同于已经能以该用户执行 Pi。

### S09 手动归档工作流已推送：2026-11-19

- 新增工作流：`.github/workflows/ai-review-archive-manual.yml`，提交 `293b58fc9d5d16fac47eecd8555182f0f480a97a`，远程分支为 `codex/pi-agent-commit-review`。远程 raw 文件返回 HTTP 200，远程分支 SHA 与本地一致。
- 工作流只允许 `workflow_dispatch`，匹配 `self-hosted/linux/x64/ai-commit-review`；不监听 push、不启动 Pi、不读取飞书凭据、不发送消息。它读取 runner 上 `/var/lib/ai-review-runner/report-input/` 的四文件真实报告包，动态校验 artifact 名称后上传，再下载并用 `cmp` 对四个文件逐字节核对。
- 使用的官方 Action 已固定提交：checkout 暂未使用；`upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02`（v4.6.2）、`download-artifact@d3f86a106a0bac45b974a628896c90dbdf5c8093`（v4.3.0）。
- 服务器 runner 用户可读取的交接目录已准备：`/var/lib/ai-review-runner/report-input/`，目录 `0750`、文件 `0640`，来源为已保存的真实完成报告（commit `42e069a158dde5ae706f9b4bbd2756f6d976e530`，无发现）。根目录飞书配置仍不可被 runner 用户读取。
- 工作流已从 `codex/pi-agent-commit-review` 快进合并到默认分支 `master`，远程 `master` 与该提交均指向 `293b58fc9d5d16fac47eecd8555182f0f480a97a`；GitHub API 返回工作流 `active`。当前尚未点击 `Run workflow`，因此真实 artifact 上传/下载尚未发生；需要用户在 Actions 页面手动运行一次。运行完成后再由服务器查询 artifact 并验证 S07 GitHub 读取适配器，随后才进入正式飞书报告发送。

### S09 首次手动运行排队诊断：2026-11-19

- GitHub API 确认 `master` 上的运行 `44445559016` 及任务 `135296718300` 处于 `queued`，任务尚无 runner，artifact 数量为 0。服务器服务运行正常，专用用户可读取四份交接文件，服务日志没有接单记录。
- GitHub API 显示 `zzx-ai-review` 在线且空闲，仅有 `ai-commit-review` 标签。任务要求 `self-hosted`、`linux`、`x64` 和 `ai-commit-review` 全部匹配。runner 注册时使用了 `--no-default-labels`，现有标签无法满足首次运行的要求。
- 本地工作流已改为 `runs-on: ai-commit-review`。修复仍需提交并推送到 `master`，随后从更新后的 `master` 发起新的真实手动运行。真实 artifact 上传、下载和 S07 读取适配器验证仍待完成。

### S09 GitHub 手动归档验收：2026-11-19

- 修复提交 `b9bc503105b7e096a6f05296eb6a4db59715d071` 已推送至 `master`。工作流仅匹配专用 `ai-commit-review` 标签。通过 GitHub API 新发起一次真实手动运行 [44447086107](https://github.com/zhaozhixuan-code/zzx-ai-code/actions/runs/44447086107)，任务 `135301744364` 于北京时间 10:49:33 至 10:49:57 在 `zzx-ai-review` 执行成功。
- GitHub artifact ID `14492929274`，共 4,117 字节，GitHub 保留至北京时间 2026-12-19 10:49:41。报告包自身的复用期限更早，为北京时间 2026-12-18 18:58:13。报告对应此前已完成的提交 `42e069a158dde5ae706f9b4bbd2756f6d976e530`。GitHub Actions 的上传、下载、四文件逐字节校验全部成功。
- 本机使用已有 GitHub 认证通过 `GitHubArtifactStore.find()` 查询、下载并校验指定报告包。服务器匿名下载返回 `401`；使用短时签名链接成功独立下载，核对 ZIP 的 GitHub SHA-256、报告协议、审查键及四份原始文件。服务器证据为 `/root/ai-commit-review/control/s09-runner-v1/S09-artifact-run-44447086107.json`。
- 本小步完成真实手动归档验收。服务器自动认证读取、push 自动审查及正式飞书摘要待接入。


### S08 发送器部署与归档摘要预览：2026-11-19

- `review/notify_feishu.py` 已部署到 `/root/ai-commit-review/control/s08-sender-v1/`。默认仅预览。每份正式报告都需要一份由受控归档流程生成的真实下载证据；发送器核对仓库、提交、报告尝试、三件套摘要及 GitHub 保留期。
- 预览批次 `s08-archive-run-44447086107` 对应提交 `42e069a158dde5ae706f9b4bbd2756f6d976e530`，报告已完成，P0/P1/P2 均为 0。来源为既有审查尝试 `def85c0d610e4ba59692d1caaf08ddaf`，三份文件与 GitHub artifact `14492929274` 的下载证据一致。
- 服务器预览与检查记录位于 `/root/ai-commit-review/results/s08/s08-archive-run-44447086107/` 下的 `preview.json` 和 `S08-sender-status.json`。授权目标群为“赵志轩, 赵钦圣”。本小步飞书请求和模型调用均为 0；真实摘要的接收及 API 回执仍待验证。
- 在服务器上重新生成预览的命令如下。单条 `--archive-evidence` 对应一份报告，多份报告传入多个同名参数。

```bash
review_root=/root/ai-commit-review
export PYTHONPATH="$review_root/runtime/s06-python-packages"
python3 "$review_root/control/s08-sender-v1/notify_feishu.py" \
  --manifest "$review_root/results/s08/s08-archive-run-44447086107/manifest.json" \
  --archive-evidence "$review_root/control/s09-runner-v1/S09-artifact-run-44447086107.json"
```

正式投递在相同命令上增加 `--send`、`--config "$review_root/secrets/feishu.json"`、`--state-root "$review_root/results/s08/delivery-state"`、`--target '赵志轩, 赵钦圣'` 和 `--message-sha256 <预览输出的摘要>`。同一批次和 Webhook 共用固定的回执目录。发送前落盘 `unknown`，HTTP 200 且业务码为 0 才更新为 `sent`。发送结果不明时先确认群内实际收件情况；获准补发时将 `--send` 改为 `--resend <最近一次失败或结果不明的尝试编号>`，继续使用相同报告、消息摘要和回执目录。通知命令的 JSON 输出可用作后续归档步骤的回执。


### S08 正式飞书摘要投递：2026-11-19

- 北京时间 11:11:30，从服务器向“赵志轩, 赵钦圣”发送一次已核对的批次 `s08-archive-run-44447086107` 摘要。飞书返回 HTTP `200` 和业务码 `0`，尝试 `59afe80bb06a499eab56590e406ca4e0` 状态为 `sent`。
- JSON 回执位于 `/root/ai-commit-review/results/s08/s08-archive-run-44447086107/feishu-receipt-ZYPKAt.json`，与 `/root/ai-commit-review/results/s08/delivery-state/receipts.sqlite3` 中该批次和目标群的唯一记录一致。同一批次使用已有审查报告，模型调用为 0。
- API 受理和回执落盘已验证。用户贴出的群内正式摘要与预览一致，确认富文本及提交、完整报告、Actions 运行链接均已显示。通知回执上传待接入 GitHub Actions。


## 7. GitHub 自动审查验收与使用说明

### 7.1 当前部署

- `AI commit review` 在 GitHub 托管的 Ubuntu runner 上执行。GitHub 接收 push 后启动作业，按事件中的 `before`、`after` 读取 Git 对象，按第一个父提交为每个提交生成独立输入。审查作业完成后上传报告，通知作业读回归档。原 `AI review archive (manual)` 和服务器回执归档继续使用专用 runner。
- `ai-review.yml` 固定审查控制代码的完整 SHA。两种 runner 的职责分别是自动审查和归档旧服务器报告。自动审查无需在服务器上常驻轮询报告目录。
- 正式摘要批次 `s08-archive-run-44447086107` 已送达飞书。原报告归档 `14492929274` 和正式回执归档 `14495886146` 分别对应运行 `44447086107`、`44451381011`。回执下载校验通过，HTTP 200、业务码 0。
- 旧版发布至 `067efee8bac58256386995a0f3d2e9ffaeb02d55` 时，工作流控制版本为 `673ee3215eca3da666fa3791918e8bac912995be`。此前真实 push `44457360106`、`44457426766` 的范围归档分别为 `14497908494`、`14498891366`，已核对推送前后 SHA、批次及空 `deferred`，两次均为仅计划。验收分支部署运行 `44455901872` 成功。仅通知运行 `44456026323` 读取批次归档 `14496943313`，计划、审查及发送跳过。已根据原归档核对消息 SHA-256 `3caa069b524337eda5f9e50266c2e7ddf4dc0b3ffc1c837e9415709ca56e0b62`。
- `master` 手动补审 `f74b253` 的运行 `44456990010` 已归档 `14498546889`。结果为 `incomplete / inconclusive`，覆盖缺口为父版本的 `SpringAiIntegrationBoundaryTest.java`。已验证报告引用，正式发现为 0。

- 2026-11-19 12:25（北京时间）的失败邮件来自真实 push `44458023535`。提交 `60a1212` 只改 `review/README.md`，按原 Git 对象生成的过滤结果为 `changed_files=[]`。旧控制器仍调用 Pi，执行记录缺少成功的源码读取，归档 `14498772030` 为 `failed / inconclusive / no_code_read`。`notify (0)` 生成摘要预览成功，飞书发送步骤跳过。
- 修复后的真实 `master` push `44459094052` 全部作业成功。范围归档 `14498854377` 与批次归档 `14497934860` 记录了 `673ee32`、`067efee` 两个提交，均为 `skipped / inconclusive`。模型尝试数为 0，失败与未处理清单为空，飞书发送步骤跳过。
- `master` 手动审查业务提交 `d999568a2fdff621b6deca6c00c309b86bebe2b4` 的运行 `44458545711` 使用控制版本 `9474c09`。归档 `14499355761` 的第一父 SHA 为 `b402b1683b49e0cb0541cf47f30e1490c9f58e2c`，结果为 `completed / no_findings`，变更覆盖完整，引用校验通过。
- 同一业务提交使用新版控制器的回归运行 `44459227039`，尝试 1 归档 `14499890509`。12:37（北京时间）收到这次手动运行的失败邮件，邮件标题使用工作流所在的 `067efee`。归档确认实际审查的是 `d999568`，第一父版本仍为 `b402b168`。Pi 退出码为 0，引用校验通过，父版本 `SpringAiProtocolTest.java` 缺少完整变更行读取记录，报告判为 `incomplete / inconclusive`。按实际 Git 对象做的定向校验确认，父版本必须读取的变更行是第 95 行。批次以非零退出，通知作业生成摘要预览，飞书发送跳过。已请求沿用原范围归档重跑失败作业。
- 同一运行 `44459227039` 的第 2 次尝试沿用原计划。归档 `14499642009` 确认 Pi 退出码为 0，父版本同一测试文件仍有读取缺口。`review (0)` 失败，`notify` 整个作业跳过。该次失败触发 GitHub Actions 订阅邮件。
- `master` 发布 `660650f5099b785a11fad7284537e81f63156d54` 时固定控制版本 `f7db585e8961528b8dc62208f119a0c03b304b3e`。规则要求逐个读取变更文件在父版本与当前版本的变更区块，测试文件同样适用，单次输出截断时缩小窗口继续读取。真实 push `44460521939` 三个作业成功。批次归档 `14499262528` 中两笔控制代码变更均按规则跳过，模型调用为 0。
- 发布后强制补审 `d999568` 的真实运行 `44460562372` 三个作业成功。归档 `14500231832` 的控制版本为 `f7db585e`，父版本为 `b402b168`，变更行覆盖完整，引用校验通过，结论为 `completed / no_findings`。已核对批次中报告文件的 SHA-256。飞书发送步骤跳过。
- 仅通知运行 `44458170100` 读取验收分支运行 `44454551796` 的尝试 1、批次 0，确认预览摘要后向“赵志轩, 赵钦圣”发送一条。待确认归档 `14497824076`，最终回执 `14498217948`，下载核对为 `sent`、HTTP 200、业务码 0。用原报告及这份真实回执验证发送前检查，重复投递被拒绝，未再次请求飞书。该条消息的群内显示待确认。
- 2026-11-19 12:58（北京时间）开启 `AI_REVIEW_SEND_FEISHU`。此前该变量为 `false`，真实 `master` push `44460521939` 的发送步骤被跳过。说明提交 `3d942ef1824be1ffa647580dd91f33a576a763b2` 推送到 `master` 后触发真实运行 `44461898674`，范围归档 `14500297169` 与批次归档 `14500108852` 均只包含此提交，报告为 `skipped/inconclusive`，模型调用为 0。通知作业自动发送一条，12:59 飞书回执 `14500517001` 为 `sent`（HTTP 200、业务码 0），与待确认归档 `14500038982` 的批次和消息 SHA-256 一致。
- 当前仓库变量 `AI_REVIEW_MASTER_ENABLED=true`、`AI_REVIEW_RUN_MODEL=true`、`AI_REVIEW_SEND_FEISHU=true`。缺省模型预算为每提交 480 秒。自动投递条件限定为 `master` 的真实 push，每批一条。仅通知入口可按消息摘要确认一次发送。

### 7.2 验收证据

| 编号 | 结果 | 证据和范围 |
| --- | --- | --- |
| A-01 | 部分通过 | 验收分支真实单提交 push `44454551796` 自动审查 `2f11dce`，归档 `14496943313` 为 `completed / no_findings`。同一批次由 `44458170100` 单独发送，飞书已受理。常态开启后，真实 `master` push `44461898674` 在同一次运行中自动投递，回执 `14500517001` 为 `sent`。该提交仅修改文档，按规则跳过模型。待核对消息的群内显示，以及含可审查源码的同一次正式 push。 |
| A-02 | 通过 | GitHub 运行 `44452972000` 的计划准确列出一次 push 的 3 个 SHA。真实模型运行 `44454811068` 的批次归档 `14495999976` 逐份比对第一父 SHA。 |
| A-03 | 通过 | `44454811068` 中 `bea39db7` 命中已标注的缺陷，`f11a246b` 修复后及 `10da089c` 调整价格后均无发现。三份结果分别取本提交和各自的第一父版本。 |
| A-04 | 通过 | `44454811068` 的 `bea39db7` 报告在 `review-validation/ShippingFee.java:8` 标记 P1 除零。合法输入 `itemCount=1` 对应分母为 0，锚点和触发条件已复核。 |
| A-05 | 通过 | 第 6 节 S05、S06 的真实上游校验反例使用同一过滤规则审查。正式报告无对应缺陷，覆盖信息与实际源码核对。 |
| A-06 | 定向验证通过 | `test_git_source.py` 使用真实临时 Git 对象，根提交空父版本、合并第一父、Unicode 重命名、删除路径均通过。 |
| A-07 | 通过 | 服务器审查 `59e2ef0` 达到 480 秒。GitHub 运行 `44456249203` 使用 10 秒预算，两次报告均为 `failed / inconclusive / timeout`，归档 `14498360377`、`14498446016` 各有独立尝试。第二次使用 Re-run failed jobs，沿用 `ai-review-range-1`，通知跳过。默认预算已恢复为 480 秒。 |
| A-08 | 定向验证与模型样例通过 | 第 6 节 S04～S06 的只读工具检查、受限路径和仓库指令样例验证通过。工具层验证仅覆盖声明的路径和读操作权限。 |
| A-09 | 定向验证通过，真实业务错误待验证 | 定向测试覆盖业务拒绝、原报告保留及指定最近一次失败运行与相同摘要后仅补发。待验证飞书真实返回非 0 业务码后的失败回执。 |
| A-10 | 定向验证通过，真实结果不明待验证 | 本地测试中，只有待确认归档、没有最终回执时，普通发送和旧运行补发均被拒绝。真实投递结果不明时先核对群内收件，再决定是否补发。 |
| A-11 | 通过 | `44454769748` 标记 `reused` 并从 `github-artifact:14497332047` 保留原尝试 ID，批次归档 `14497133139`。`44456856165`、`44457180935` 对同一审查键强制重审，各有独立尝试 ID。后一批次归档 `14498091990`，结果为 `completed / no_findings`。 |
| A-12 | 定向验证通过 | 本地用 Git 对象和事件输入验证新分支需显式基准、强推集合及缺失对象。删除分支也在定向用例中记录。 |
| A-13 | 通过 | 真实运行 `44456026384` 的归档 `14497179874` 与本机 Git 的 51 个 SHA 顺序一致。前 50 个分 5 批，每批 10 个，待处理项为 `b237cc0db0d8a705739b70424601d7d8a6221773`。 |
| A-14 | 通过 | 验收分支连续 push 的 `44452979146`、`44452986053` 各自归档对应的单提交范围。`master` 的 `44457360106`、`44457426766` 也各有独立已核对的计划归档。 |

三提交样例的预期缺陷命中 1 项，修复后与后续调价各 0 项。本轮样例用于核对版本与证据，不计算普遍准确率。业务提交 `59e2ef0` 仍为超时未判定，`f74b253` 仍为覆盖不足未判定。Windows 回归 55 项中 37 项通过、18 项 POSIX 用例跳过。空变更分支另由真实 Ubuntu 运行 `44459094052` 验证。

### 7.3 配置、补跑与停用

| 配置位置 | 名称 | 用途 |
| --- | --- | --- |
| GitHub Environments → `ai-review-model` → Secrets | `DEEPSEEK_API_KEY` | 提供给模型执行步骤。 |
| GitHub Environments → `ai-review-feishu` → Secrets | `FEISHU_WEBHOOK_URL`、`FEISHU_SIGNING_SECRET` | 提供给已通过发送检查的飞书步骤。 |
| GitHub Environments → `ai-review-feishu` → Variables | `FEISHU_TARGET_NAME` | 记录配置所对应的接收群。 |
| GitHub Actions → Repository variables | `AI_REVIEW_MASTER_ENABLED` | `true` 时处理 `master` 的 push。仅通知和手动审查入口可独立运行。 |
| GitHub Actions → Repository variables | `AI_REVIEW_RUN_MODEL` | `true` 时执行批次审查。关闭时可以先验证计划与范围。 |
| GitHub Actions → Repository variables | `AI_REVIEW_SEND_FEISHU` | `true` 时自动发送 `master` 真实 push 的批次摘要，每批一条。关闭时仍生成预览，可按摘要独立确认一次投递。 |
| GitHub Actions → Repository variables | `AI_REVIEW_TIMEOUT_SECONDS` | 可选，每个提交的总审查预算，单位为秒。允许 1～480，缺省为 480。 |

- 首次启用时先用验收分支检查 plan 的 `range.json` 和审查归档。准备正式运行后，打开 `AI_REVIEW_RUN_MODEL` 和 `AI_REVIEW_MASTER_ENABLED`。需要批次自动通知时，再确认接收群和发送范围，打开 `AI_REVIEW_SEND_FEISHU`。
- 进入仓库 Actions → `AI commit review` → Run workflow。历史单提交填 `commit`。历史范围填 `base` 和 `head`，使用完整 SHA。超过 50 个提交时，从 `ai-review-range-<尝试号>` 的 `range.json` 查看 `deferred`，可以逐个填写 `commit`，或按准确的基准和终点把剩余历史分段补审。
- 需要独立审查尝试时，手动选择 `force_review`。同一运行失败后可用 Re-run failed jobs，工作流会使用该运行最近已保存的范围。只重跑失败作业不会自动补发飞书。
- 仅通知时填写原审查运行 `notification_run`、原尝试 `notification_attempt` 和从 0 开始的 `notification_batch`。首次运行 `confirm_message_sha256` 留空，从 Actions 摘要核对报告和消息 SHA-256。获准发送时用相同输入并填该 SHA。已有成功回执时会拒绝重复投递。
- 补发时先确认上一条实际收件情况。只允许补发最近一条状态为 `failed` 或 `unknown` 的投递，填 `previous_delivery_run` 和原消息 SHA-256。保存的待确认记录在发送前上传，最终回执在发送后上传。超过 30 天保留期时，先人工核对历史收件和已保存报告，再决定是否重审或发送。
- 暂停自动审查时关闭 `AI_REVIEW_MASTER_ENABLED`，暂停批次自动通知时关闭 `AI_REVIEW_SEND_FEISHU`。需要停用所有工作流入口时，在 GitHub 禁用 `AI commit review`，另行取消仍在排队或运行的任务。
- 模型超时查看失败批次的报告和执行记录。若作业失败但 Pi 正常退出，从 `review.json` 的 `coverage.unread_changed_files` 核对未覆盖的版本和文件，必要时重跑失败作业。手动审查的邮件标题显示工作流所在分支的 SHA，实际审查目标以范围归档和报告为准。归档下载失败时检查运行 ID、尝试号、批次索引、保留期和访问权限。飞书返回 HTTP 错误或业务码时看最终回执，只有待确认归档时按 `unknown` 处理。


## 8. 将模型审查迁到服务器

| 步骤 | 变更 | 验收 |
| --- | --- | --- |
| 1 | `master` push 触发计划，`review` 使用 `ai-commit-review` Runner 按 SHA 拉取差异 | Actions 的 `review` 作业显示 `zzx-ai-review`，归档中的提交及其父 SHA 与推送一致 |
| 2 | 每批读取 Runner 用户的 Pi 配置，支持更换模型和接入地址 | 报告记录选中的 provider 和 model，切换地址时产生新的审查键 |
| 3 | Actions 归档审查结果并向飞书发送摘要 | 正式 push 产生批次归档及 `sent` 投递回执 |

服务端配置位置和模型切换方法见 [审查运行说明](../../review/README.md)。第 7 节记录迁移前的运行和验收证据。

- 服务器的 `zzx-ai-review` Runner 服务已在运行。专用账号下通过 57 项 Python 测试，工作流通过 `actionlint` 检查。
- 服务器已从真实 GitHub 仓库拉取 `59e2ef02f7a1335c55ff7cdb2daf5e0d17954114`，核对第一父提交为 `e25e41ec48add190d4ab7ad6e92a5ab87314554f`。Pi 1.5.2 通过两个本地测试网关分别完成工具调用和报告归档，两个审查键不同。测试网关返回预设响应。
- 迁移代码仍在本地。上线后须核对 `review` 作业接单的 Runner、真实 Flash 审查、GitHub 批次归档和飞书投递回执。

# SnapVideoTools Desktop 客户端实施记录

- 日期：2026-08-17
- 执行者：Codex

## 已实施

- 删除旧 Session ID 登录协议，接入浏览器设备授权、Bearer Token、刷新令牌轮换和本地仅当前用户可读写的令牌文件。
- 单视频解析改用 POST 与 requestId；Header 展示 Free/Pro、今日剩余额度和账户入口；免费账户禁用主页提取。
- 启动后异步检查版本，支持 macOS arm64/x64、Windows x64、Linux x64，并提供“立即下载”和“稍后提醒”。
- Maven `revision` 写入运行时版本；平台打包脚本默认读取 Maven 版本，发布标签可显式注入同一版本。
- GitHub Actions 将四个平台安装包、Release Notes 和 `SHA256SUMS.txt` 发布到公开二进制仓库。

## 发布前置

1. 使用公开仓库 `SnapVideoTools/Desktop-Packages`。
2. 在私有源码仓库配置 `PUBLIC_RELEASE_TOKEN`，仅授予目标公开仓库 Release 写权限。
3. 保留现有 Apple 签名、公证及 Windows 打包 Secrets。
4. 推送 `vX.Y.Z` 标签；流水线会把 `X.Y.Z` 注入 Maven 和安装包，并生成四个标准命名资产。

## 验证记录

- `mvn test`：构建成功；当前客户端仓库没有测试源。
- `bash -n build-mac.sh`、`bash -n build-linux.sh`：语法检查通过。
- 2026-08-17：版本检查仅解析成功的 JSON 响应，服务端返回 HTML/5xx 时静默跳过，不再输出 Jackson 解析堆栈；修复后客户端启动验证通过。

---

# 桌面端账号提示授权入口修复记录

- 日期：2026-08-22
- 执行者：Codex

## 已实施

- 保留“还没有账户？请在此注册”说明，并在后方显示可点击的 `snapvideotools.com` 网站链接。
- 网站链接与上方“使用 SnapVideoTools 账号授权登录”按钮绑定同一个 `handleSubmit`，统一创建设备授权并打开服务端登录/授权页面。
- 为网站链接补充品牌绿和悬停下划线样式，英文、中文与越南语提示保持一致。

## 验证记录

- `mvn test`：39 个源文件编译成功，构建成功；当前客户端仓库没有测试源。
- FXML XML 校验和 `git diff --check` 均通过；未执行部署、Git commit 或远程 Git push。

---

# 用户主页 Pro 角标与权限提示记录

- 日期：2026-08-22
- 执行者：Codex

## 已实施

- 非 Pro 用户的“用户主页”Tab 增加黄色 `PRO` 角标，悬停显示“仅限 Pro 订阅用户”的本地化提示。
- 保持受限 Tab 可接收鼠标事件，点击时继续调用现有订阅引导，不会误切换到用户主页模式。
- 登录为具备用户主页提取权限的 Pro 账户后，角标、提示和受限样式自动移除。
- 30 份桌面端语言资源（含兼容语言包）同步新增提示文案。

## 验证记录

- `mvn test` 与 Windows profile 打包成功；当前客户端仓库没有测试源。
- 30 份语言资源均包含新增键，各语言资源键数量一致。
- FXML XML 校验通过；未执行部署、Git commit 或远程 Git push。

---

# Windows 登录后流畅度与多语言同步记录

- 日期：2026-08-22
- 执行者：Codex

## 已实施

- 将登录后的历史数据库查询、文件存在性和文件大小检查移出 JavaFX 应用线程，主界面外壳可先完成显示。
- 历史卡片按每帧 3 个、每页 30 个增量渲染，滚动接近底部后加载下一页，避免普通 `VBox` 一次挂载全部历史节点。
- 合并同一 JavaFX 脉冲中的吸顶栏位置计算；重新登录时清空旧视图索引，并按控制器去重语言、账户和下载监听器。
- 桌面端语言清单与 PC Web 端同步为 29 种，每种包含完整的 128 个界面键；兼容旧 `zh` 设置，并支持阿拉伯语和希伯来语 RTL。
- 所有 Desktop API 请求携带当前 BCP 47 `Accept-Language`，使授权页和服务端消息沿用桌面端语言选择。

## 验证记录

- 29 种语言代码与 Web 端顺序一致；全部资源键、格式化占位符验证一致。
- `mvn test` 构建成功；当前客户端仓库没有测试源。
- `git diff --check` 通过；未执行部署、Git commit 或远程 Git push。

---

# 语音转文字开启失败原因与重试修复记录

- 日期：2026-08-22
- 执行者：Codex

## 已实施

- 将模型准备失败分类为网络中断、超时、服务器、磁盘空间、目录权限、文件校验、解压、模型加载、中断和未知错误。
- 设置窗口展示本地化的具体失败原因及对应处理建议，不再始终显示统一的网络错误。
- 失败时立即释放旧准备任务引用，保证用户点击“重试”会创建新的任务；部分下载继续断点续传。
- 完整模型加载失败时只重新加载模型，不再重复下载约 156 MB 文件；损坏的下载或压缩包会清理后重新下载。
- 29 种桌面语言同步补齐失败原因文案。

## 验证记录

- `mvn test` 与 Windows profile 打包成功；当前客户端仓库没有测试源。
- 29 种语言各 139 个资源键及格式化占位符全部一致。
- FXML XML 校验和 `git diff --check` 均通过；未执行部署、Git commit 或远程 Git push。

---

# 底部完整支持平台弹窗记录

- 日期：2026-08-22
- 执行者：Codex

## 已实施

- 保留底部现有 15 个平台图标，并在末尾增加圆形“更多”入口及本地化悬停提示。
- 新增与 Web 端公开平台注册表数量一致的 31 平台弹窗，以四列品牌卡片、数量徽章和滚动容器展示完整清单。
- 复用 Web 端现有 15 份平台 PNG 素材；Web 端无图标的 Terabox 使用首字母回退，避免资源加载失败。
- 弹窗支持键盘 Esc/关闭按钮、RTL 布局、窗口缩放及现有品牌主题，30 份语言资源同步新增“查看全部支持平台”文案。

## 验证记录

- `mvn test` 与 Windows profile 打包成功；当前客户端仓库没有测试源。
- 30 份语言资源各 141 个键，键集合完全一致；弹窗 31 个平台注册项无缺失图标。
- FXML XML 校验和 `git diff --check` 均通过；未执行部署、Git commit 或远程 Git push。

---

# Linux 安装包下线记录

- 日期：2026-08-23
- 执行者：Codex

## 已实施

- 从 GitHub Actions 发布矩阵中移除 Linux x64 DEB 构建、依赖安装和上传步骤。
- 发布校验调整为 macOS Apple Silicon、macOS Intel、Windows x64 共 3 个安装包。
- 删除 Linux Maven Profile、`build-linux.sh` 及 README 中的 Linux 构建和发布说明。

## 验证记录

- `mvn test` 构建成功；当前客户端仓库没有测试源。
- `release.yml` YAML 语法检查与 `git diff --check` 通过。
- 未执行部署、Git commit、打标签或远程 Git push。

---

# 当前仓库 Release 发布与 Actions 日志脱敏记录

- 日期：2026-08-23
- 执行者：Codex

## 已实施

- 标签发布改为使用当前仓库 `${GITHUB_REPOSITORY}`，不再发布到 `SnapVideoTools/Desktop-Packages`。
- Release 任务仅授予 `contents: write`，使用 GitHub 自动提供的短期 `GITHUB_TOKEN`，删除 `PUBLIC_RELEASE_TOKEN` 依赖。
- Apple 凭证、临时钥匙串密码和签名身份在使用或跨步骤传递前显式注册日志掩码。
- 删除签名身份明文、公证原始响应、公证详情日志及其 Artifact；公证失败仅保留不含敏感上下文的固定错误信息。
- 同步 README 中的发布目标、认证方式和公证失败处理说明。

## 验证记录

- `actionlint .github/workflows/release.yml`：通过。
- 静态扫描未发现 `PUBLIC_RELEASE_TOKEN`、旧二进制仓库、公证原始日志输出或上传逻辑。
- `mvn --batch-mode --no-transfer-progress test`：构建成功；当前桌面端没有测试源码。
- `git diff --check`：通过。
- 未执行 GitHub Release、打标签、Git commit 或远程 Git push；需在仓库公开后通过真实标签工作流完成线上验收。
- 权限与日志掩码依据：https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax 、https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#masking-a-value-in-a-log

---

# Git 历史根提交重建记录

- 日期：2026-08-23
- 执行者：Codex

## 已实施

- 以操作时的完整工作树创建无父提交的 `Initial commit`，作为本地 `main` 唯一历史起点。
- 删除本地 `feature-0817` 分支与 `v1.0.0` 标签，避免本地命名引用继续保留旧提交链。

## 验证与远端状态

- 验证 `main` 仅包含一个根提交，且工作树无未提交修改。
- 按仓库操作约束未执行 force-push；GitHub 远端的旧 `main`、`feature-0817` 仍需在明确授权后替换或删除。

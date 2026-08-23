# SnapVideoTools Desktop

SnapVideoTools Desktop 是基于 JavaFX 的桌面媒体下载工具，支持从视频链接或用户主页创建下载任务，并在本机完成音频提取和语音转文字。

> 本软件仅提供工具技术服务，不存储任何内容。请用户遵守原平台版权规则，下载风险及侵权责任由用户自负。

## 主要功能

- 支持视频链接批量提交和用户主页分页下载。
- 支持下载视频、图片，以及从视频中提取封面和 MP3 音频。
- 下载队列与历史记录可按全部、视频、音频、图片筛选。
- 支持暂停、继续、重试和全量清空下载队列。
- 清空历史记录时可同时删除本地媒体、TXT 文稿和 SRT 字幕。
- 支持本地语音转文字，自动生成与视频同名的 `.txt` 和 `.srt` 文件。
- 支持中文、英文和越南文界面。

目前界面列出的支持平台包括 TikTok、抖音、小红书、快手、哔哩哔哩、西瓜视频、今日头条、微博、皮皮虾、最右、梨视频、新片场、好看视频、虎牙和 AcFun。实际解析能力以服务端当前支持情况为准。

## 技术栈

- Java 21
- JavaFX 21
- Spring Boot 3.2
- DuckDB
- FFmpeg / FFprobe
- sherpa-onnx 1.13.5
- SenseVoice-Small 与 Silero VAD

## 环境要求

开发运行需要：

- JDK 21，须包含 `jpackage` 才能生成桌面安装包。
- Maven 3.9 或兼容版本。
- 可用的 FFmpeg 和 FFprobe。开发模式优先使用系统安装版本，打包脚本也可准备平台资源。
- 网络连接，用于登录、解析媒体链接和首次准备语音转文字功能。

当前原生依赖配置支持：

| 系统 | 架构 | Maven Profile |
| --- | --- | --- |
| macOS | Apple Silicon | `mac-aarch64` |
| macOS | Intel x64 | `mac` |
| Windows | x64 | `windows` |

各 Profile 会根据当前操作系统自动激活，通常无需手动指定。

## 常见问题

### 提示“Video parsing failed”

优先使用平台的标准视频分享链接。应用已兼容包含 `modal_id` 的抖音主页弹层链接，但链接失效、内容不可访问或平台接口变化时仍可能解析失败。

### 语音转文字无法开启

检查网络连接和磁盘空间，然后在“设置 → 语音转文字”中重试。应用会复用已经下载并校验通过的文件。

### 下载成功但没有文本

确认提交任务时勾选了“提取文本”。图片或不包含音轨的视频不会生成有效文本；没有检测到人声时 TXT/SRT 内容为空。

## 项目结构

```text
src/main/java/com/kalman03/svt/desktop/
├── config/       # 数据库与应用配置
├── controller/   # JavaFX 控制器
├── entity/       # 持久化实体
├── enums/        # 下载和转写状态
├── model/        # 业务模型
├── repository/   # DuckDB 数据访问
├── service/      # 下载、解析、FFmpeg 与转写服务
└── util/         # 通用工具

src/main/resources/
├── css/          # JavaFX 样式
├── fxml/         # 页面布局
├── i18n/         # 中英越文案
└── icons/        # 应用与平台图标
```

## 参考资料

- [SenseVoice](https://github.com/QwenAudio/SenseVoice)
- [sherpa-onnx Java API](https://k2-fsa.github.io/sherpa/onnx/java-api/non-android-java.html)
- [sherpa-onnx SenseVoice 预训练模型](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)
- [JavaFX](https://openjfx.io/)
- [FFmpeg](https://ffmpeg.org/)

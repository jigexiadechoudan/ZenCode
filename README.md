# ZenCode

**用 Java 21 构建的本地 AI 编程工作台。** 在浏览器中围绕项目对话，让 Agent 阅读代码、编辑文件、执行命令，并查看执行轨迹与文件差异。

## 能做什么

- **普通聊天与项目**：按项目整理对话，绑定本地仓库或普通文件夹；支持聊天改名、移动、删除和历史恢复。
- **完整 Agent 执行链路**：流式回复、文件读写、代码搜索、命令执行与停止控制，按权限确认写入和命令操作。
- **执行过程可见**：按任务查看模型阶段、工具调用、审批、状态和耗时，展开查看操作摘要。
- **直观文件比较**：新增标绿、删除标红；支持 Git 工作区、暂存区和普通文件夹快照比较。
- **扩展 Agent 能力**：Skills、MCP、Hooks、后台任务与进程内团队协作。
- **本地工作台**：浅灰工作站界面、Markdown 与代码复制、断线恢复；前端资源随 JAR 打包。

## 快速开始

环境：Windows、**JDK 21+**、**Maven**。设置 `JAVA_HOME`，并确保 `mvn` 在 PATH 中；首次构建需要联网下载依赖。

1. 克隆仓库并进入目录：

   ```powershell
   git clone https://github.com/jigexiadechoudan/ZenCode.git
   cd ZenCode
   ```

2. 双击或运行 `start-zencode.cmd`，等待浏览器打开。没有自动打开时，访问启动窗口显示的本机地址。
3. 在模型设置中填写服务地址、模型 ID 和本次使用的 API Key，点击 **保存并应用**，即可开始聊天；也可以点击 **添加项目** 选择工作目录。

支持 OpenAI 兼容的 Chat Completions 接口与 Anthropic Messages 接口。密钥仅用于本次进程，**每次重新启动都需要填写**。

## 日常使用

| 入口 | 用途 |
| --- | --- |
| 新建聊天 | 在启动目录中开始普通对话 |
| 添加项目 | 选择本机文件夹，在项目内创建聊天 |
| 聊天菜单 | 改名、移动到项目或删除存档 |
| 轨迹与变更 | 查看任务步骤和红绿文件差异 |
| 权限模式 / 停止 | 控制工具执行范围，停止当前任务 |
| 输入 `/` | 使用会话、技能和任务等命令 |

聊天集中保存在启动目录的 `.zencode/sessions/`，项目清单在 `.zencode/projects.json`。移除项目会保留聊天与源文件。当前一次执行一个聊天任务。

普通文件夹的差异以本次进程首次打开该目录时的快照为基准，重启后重建；Git 目录显示未提交变更。

## 开发

```powershell
mvn test
mvn "-Dmaven.test.skip=true" package
.\zencode.cmd --config config.local.yaml
```

`start-zencode.cmd` 会先打包再启动；已有 JAR 时可直接使用 `zencode.cmd`。高级配置参考 [config.example.yaml](config.example.yaml)，模型设置、权限、恢复和文件比较的详细说明见 [工作台指南](docs/workbench.md)。

## 数据与隐私

服务只监听本机地址。Provider 密钥不保存到配置或浏览器持久存储；关闭 Java 进程后失效。关闭浏览器页面不会退出 Java 服务，可在启动窗口按 `Ctrl+C` 退出。

`.zencode/`、`config.local.yaml`、`.env` 和构建产物均不应提交。会话与项目路径保留在本地；请勿将真实密钥、聊天存档或个人配置放入提交。

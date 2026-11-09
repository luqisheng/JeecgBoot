# JEECG 技能的 Codex 适配约定

这些入口复用 `.claude/skills` 的 JEECG 官方技能，原文、脚本和资源保持原样。
项目规范见根目录 `AGENTS.md`；具体模块仍需遵守该模块自己的 `AGENTS.md`。

## 原文和资源路径

从入口的链接读取原技能正文。正文的 `scripts/`、`references/`、`assets/`、
`<skill目录>` 和 `${CLAUDE_SKILL_DIR}` 都以原技能文档所在目录为基准，
不是 `.agents/skills/<name>`。跨技能资源从仓库的 `.claude/skills` 根目录定位。
不要把 `~/...` 示例路径直接当作本项目路径；后端是 `jeecg-boot`，前端是 `jeecgboot-vue3`，
独立报表示例是 `jimureport/jimureport-example`。

## 工具与运行时映射

| 原文工具或语法 | Codex 中的处理 |
| --- | --- |
| Read | 用当前可用的读取工具或 shell 读取指定文件 |
| Write / Edit | 使用 `apply_patch` 或当前可用的文件编辑工具 |
| Grep / Glob | 仓库有 `.codegraph` 时先用 CodeGraph 定位代码，再按需使用 `rg` |
| Bash | 使用当前 shell；Windows 默认 PowerShell，按实际环境改写命令 |
| Task / 子代理 | 仅在当前环境支持且任务已授权委派时使用可用的协作工具 |
| AskUserQuestion | 使用当前可用的询问工具或直接询问用户 |
| `!` 加反引号的动态命令 | Codex 不会自动展开；读取脚本用途后显式执行 |
| Claude memory / 自动注入上下文 | 使用本会话已有信息、明确配置或用户提供的信息，缺失时询问 |

运行 Python 脚本时使用原目录的实际路径，并先检查相邻依赖说明。
AIFlow 的节点选项索引可以在仓库根目录用 PowerShell 执行：

```powershell
python .claude/skills/jeecg-aiflow/scripts/gen_node_options_index.py
```

源文档中的 API 地址、模型名、数据库连接和 mock 平台配置是示例，使用前确认本项目实际配置。
安装技能不代表授权提交表单、部署流程、发布报表或修改数据库；执行这些操作仍按用户当前任务授权。
Token、密码和密钥不写入版本库，也不因源文档示例而创建外部 mock 数据。

## 开发规范的适用方式

`jeecg-dev` 保持仅手动调用，并由入口的 `agents/openai.yaml` 控制。
上游要求的 `update-begin/end`、作者日期和修改日志，与本项目已确认的注释约定不一致时，
按根目录 `AGENTS.md` 执行：保留历史注释，新注释说明业务含义、约束和原因，不新增修改流水账。

维护和检查命令见 `docs/development/codex-jeecg-skills.md`。

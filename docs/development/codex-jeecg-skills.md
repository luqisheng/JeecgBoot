# 在 Codex 中使用 JEECG 官方技能

本项目的原技能位于 `.claude/skills`，来源为
[JEECG 官方 skills 仓库](https://github.com/jeecgboot/skills)。原文件保持原样。
Codex 使用 `.agents/skills/<name>/SKILL.md` 适配入口；入口引用原文、脚本和资源。
加载方式依据 [OpenAI 官方 skills 文档](https://developers.openai.com/codex/skills/)。

## 使用方式

在本项目的 Codex 会话中明确调用，例如：

```text
$jeecg-codegen 生成订单管理的 Java 和 Vue3 CRUD 代码
$jeecg-aiflow 根据现有模型设计一个知识库问答流程
$jimureport 设计一张销售汇总报表
$jeecg-dev 检查这次变更是否符合项目开发规范
```

除 `jeecg-dev` 外，技能可按用户任务自动选择。`jeecg-dev` 保持手动调用。
Codex 会自动检测技能变化；如果选择器未更新，重新打开项目或重启 Codex。
文件检查只能验证入口及资源可读取，实际选择器是否刷新需以当前 Codex 会话为准。

## 接入清单

| 技能 | 用途 |
| --- | --- |
| `jeecg-codegen` | 常规 Java / Vue3 CRUD 代码生成 |
| `jeecg-codegen-new` | 新版 Freemarker、ERP 和多布局生成 |
| `jeecg-uniapp-codegen` | 移动端 CRUD；原入口嵌套在 `jeecg-codegen/uniapp` |
| `jeecg-dev` | 显式调用开发规范 |
| `jeecg-system` | 用户、角色、部门、字典等系统主数据 |
| `jeecg-onlform` | Online 元数据表单 |
| `jeecg-desform` | 表单设计器；原文件名为小写 `skill.md` |
| `jeecg-onlreport` | Online SQL 数据报表 |
| `jeecg-onlchart` | Online 图表 |
| `jeecg-bpmn` | Flowable BPMN 审批流程 |
| `jeecg-aiflow` | AI 编排流程 |
| `jeecg-portal` | 门户和工作台布局 |
| `jimubi-bigscreen` | 全屏可视化大屏 |
| `jimubi-dashboard` | 网格仪表盘；原文件名为小写 `skill.md` |
| `jimureport` | 积木报表、打印和填报 |

## 同步与验证

同步脚本使用 Python 标准库，不下载技能、不更新上游、不访问业务 API：

```powershell
cd D:\carsmaga\JeecgBoot

# 安装或在原文更新后重新生成适配入口
python scripts/sync-jeecg-skills.py

# 只读检查技能入口、原文摘要和手动调用策略
python scripts/sync-jeecg-skills.py --check

# 验证同步脚本的原文件保护、幂等性和冲突处理
python -m unittest discover -s scripts/tests -p test_sync_jeecg_skills.py -v
```

来源版本、入口路径与 SHA-256 摘要记录在 `.agents/jeecg-skills.json`。
`.agents/.gitattributes` 固定适配文件使用 LF，防止 Windows Git 检出时的换行转换使摘要误报。
重复同步不会重写未变化的文件。若适配入口有手工修改，脚本会停止并保留文件。
需要持久修改适配规则时改 `scripts/sync-jeecg-skills.py`，再同步。
上游移除技能时，脚本提示人工审查旧入口，不自动删除文件。

根目录 `AGENTS.md` 记录项目约定，优先于上游技能中的冲突规范。
AIFlow 等技能中的 Claude 动态命令须在 Codex 中按 `.agents/jeecg-adapter.md` 显式执行。
脚本所需依赖、服务地址与凭据根据具体任务配置；接入技能不会自动发布报表或修改数据库。

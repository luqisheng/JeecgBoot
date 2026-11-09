#!/usr/bin/env python3
"""将本地 JEECG 技能接入 Codex；只写适配入口，不修改上游文件。"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys


MANIFEST = ".agents/jeecg-skills.json"
DESCRIPTIONS = {
    "jeecg-aiflow": "Use when creating or editing JeecgBoot AIFlow orchestration, AI 编排流程、模型与知识库节点。",
    "jeecg-bpmn": "Use when creating or editing JeecgBoot Flowable BPMN 审批流程、条件分支、并行或会签。",
    "jeecg-codegen": "Use when generating conventional JeecgBoot Java and Vue3 CRUD modules, 单表、树表或主子表代码。",
    "jeecg-codegen-new": "Use when using the Freemarker-based JeecgBoot generator, 新版代码生成、ERP、Tab 或内嵌子表布局。",
    "jeecg-uniapp-codegen": "Use when generating JeecgBoot UniApp mobile CRUD pages, 移动端列表、表单、小程序或 H5 页面。",
    "jeecg-desform": "Use when designing or managing JeecgBoot desform 表单设计器、表单数据和 OA 表单；Online 表单使用 jeecg-onlform。",
    "jeecg-dev": "Use when explicitly invoking $jeecg-dev to consult JeecgBoot 开发规范。仅手动调用，项目约定优先。",
    "jeecg-onlchart": "Use when creating or editing JeecgBoot Online 图表、柱状图、折线图、饼图和数据可视化配置。",
    "jeecg-onlform": "Use when creating or managing JeecgBoot Online 元数据表单、字段和表结构同步；desform 使用 jeecg-desform。",
    "jeecg-onlreport": "Use when creating or editing JeecgBoot Online SQL 数据报表、分组表头或字典翻译。",
    "jeecg-portal": "Use when creating or managing JeecgBoot 门户、工作台和 Web/App 首页组件布局。",
    "jeecg-system": "Use when querying or managing JeecgBoot 系统主数据、用户、角色、部门、字典和权限。",
    "jimubi-bigscreen": "Use when creating JimuBI 全屏可视化大屏、监控大屏或展厅大屏；网格仪表盘使用 jimubi-dashboard。",
    "jimubi-dashboard": "Use when creating JimuBI 网格仪表盘、数据看板或运营面板；全屏大屏使用 jimubi-bigscreen。",
    "jimureport": "Use when creating or editing JimuReport 积木报表、Excel 风格报表、打印报表、分组循环报表或数据填报。",
}

ADAPTER_GUIDE = """# JEECG 技能的 Codex 适配约定

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
"""


def digest(content):
    return hashlib.sha256(content).hexdigest()


def discover_skills(root):
    source_root = root / ".claude/skills"
    found = {}
    for path in sorted(source_root.rglob("*")):
        relative = path.relative_to(source_root)
        if ".git" in relative.parts or not path.is_file() or path.name.lower() != "skill.md":
            continue
        text = path.read_text(encoding="utf-8-sig")
        frontmatter = re.match(r"^---\s*\n(.*?)\n---", text, re.S)
        match = re.search(r"^name:\s*([^\n]+)", frontmatter.group(1), re.M) if frontmatter else None
        if not match:
            raise ValueError(f"技能元数据缺少 name: {path}")
        name = match.group(1).strip().strip("\"'")
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name) or len(name) > 64:
            raise ValueError(f"不支持的技能名称: {name}")
        if name in found:
            raise ValueError(f"重复技能名称 {name}: {found[name]} / {path}")
        found[name] = path
    if not found:
        raise ValueError(f"未找到技能文档: {source_root}")
    return found


def source_revision(root):
    # 局部复制技能时不能把主项目的提交误记为上游技能版本。
    if not (root / ".claude/skills/.git").exists():
        return None
    try:
        result = subprocess.run(
            ["git", "-C", str(root / ".claude/skills"), "rev-parse", "HEAD"],
            capture_output=True, text=True, check=False,
        )
        return result.stdout.strip() if result.returncode == 0 else None
    except FileNotFoundError:
        return None


def build_outputs(root):
    outputs = {
        ".agents/.gitattributes": b"* text eol=lf\n",
        ".agents/jeecg-adapter.md": ADAPTER_GUIDE.encode("utf-8"),
    }
    skills = []
    for name, source in sorted(discover_skills(root).items()):
        source_path = source.relative_to(root).as_posix()
        source_directory = source.parent.relative_to(root).as_posix()
        description = DESCRIPTIONS.get(name, f"Use when explicitly requesting the JEECG {name} skill.")
        body = (
            f"---\nname: {name}\ndescription: {json.dumps(description, ensure_ascii=False)}\n"
            f"license: Apache-2.0\nmetadata:\n  original-source: {json.dumps(source_path)}\n---\n\n"
            f"# {name}\n\n"
            "使用时先读取[Codex 适配约定](../../jeecg-adapter.md)，"
            f"再读取[原技能文档](../../../{source_path})。\n\n"
            f"原技能资源基准目录为 `{source_directory}`。"
            "原文的脚本和参考资料路径均相对此目录解析。\n"
        )
        outputs[f".agents/skills/{name}/SKILL.md"] = body.encode("utf-8")
        if name == "jeecg-dev":
            outputs[f".agents/skills/{name}/agents/openai.yaml"] = (
                b"policy:\n  allow_implicit_invocation: false\n"
            )
        skills.append({"name": name, "source": source_path, "sha256": digest(source.read_bytes())})
    manifest = {
        "schema_version": 1,
        "source_repository": "https://github.com/jeecgboot/skills",
        "source_revision": source_revision(root),
        "skills": skills,
        "generated_files": {path: digest(content) for path, content in sorted(outputs.items())},
    }
    outputs[MANIFEST] = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    return outputs, len(skills)


def synchronize(root, check=False):
    outputs, count = build_outputs(root)
    previous_path = root / MANIFEST
    previous = json.loads(previous_path.read_text(encoding="utf-8")) if previous_path.is_file() else {}
    owned = previous.get("generated_files", {})
    obsolete = set(owned) - set(outputs)
    if obsolete:
        raise ValueError("原技能已移除，请人工检查旧适配文件: " + ", ".join(sorted(obsolete)))
    different = [path for path, data in outputs.items() if not (root / path).is_file() or (root / path).read_bytes() != data]
    if check:
        if different:
            raise ValueError("技能入口需要同步或有手工改动: " + ", ".join(different))
        return count
    # 先检查全部冲突，避免同步过程中覆盖用户文件或只更新一部分入口。
    for path in different:
        destination = root / path
        if not destination.exists():
            continue
        if path == MANIFEST:
            continue
        if not destination.is_file() or digest(destination.read_bytes()) != owned.get(path):
            raise ValueError(f"保留手工修改的适配文件，请先审查: {destination}")
    for path in different:
        destination = root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(outputs[path])
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--check", action="store_true", help="只读检查入口是否与原文及适配约定一致")
    arguments = parser.parse_args()
    try:
        count = synchronize(arguments.repo_root.resolve(), arguments.check)
    except (ValueError, OSError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"{'Check' if arguments.check else 'Sync'} OK: {count} skills; original files unchanged.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

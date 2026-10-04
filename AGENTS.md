<!-- TRELLIS:START -->
# Trellis Instructions

These instructions are for AI assistants working in this project.

This project is managed by Trellis. The working knowledge you need lives under `.trellis/`:

- `.trellis/workflow.md` — development phases, when to create tasks, skill routing
- `.trellis/spec/` — package- and layer-scoped coding guidelines (read before writing code in a given layer)
- `.trellis/workspace/` — per-developer journals and session traces
- `.trellis/tasks/` — active and archived tasks (PRDs, research, jsonl context)

If a Trellis command is available on your platform (e.g. `/trellis:finish-work`, `/trellis:continue`), prefer it over manual steps. Not every platform exposes every command.

If you're using Codex or another agent-capable tool, additional project-scoped helpers may live in:
- `.agents/skills/` — reusable Trellis skills
- `.codex/agents/` — optional custom subagents

Managed by Trellis. Edits outside this block are preserved; edits inside may be overwritten by a future `trellis update`.

<!-- TRELLIS:END -->

# 项目约定

## 本地 Git 提交（用户明确要求）

- 每个完成的变更单元都必须提交到本地 Git，包括代码、配置、文档、Trellis 任务与会话记录。
- 修改完成后检查差异，仅暂存与本次任务有关的文件，使用说明具体目的的提交信息。
- 向用户交付时报告提交编号与检查结果；不得把未提交的变更当作已完成。
- 本地提交已获得用户授权，无须再次询问；推送与发布另行根据用户授权处理。
- 不覆盖或撤销用户已有的修改，不自动修改全局 Git 身份，不提交密钥、签名文件或个人照片。
- 本约定优先于 Trellis 模板中要求用户自行提交、仅提醒提交或每次重新询问提交权限的内容。

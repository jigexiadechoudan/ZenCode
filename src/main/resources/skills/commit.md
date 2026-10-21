---
name: commit
description: 把当前改动提交到 Git（自动生成提交信息）
mode: inline
allowedTools: [Bash, ReadFile, Glob, Grep]
---
执行一次 Git 提交，把工作区的当前改动整理并提交，$ARGUMENTS 可传入补充说明。

步骤：
1. 用 Bash 运行 `git status` 和 `git diff --stat`，确认当前改动范围。
2. 用 `git diff`（或 `git diff --staged`）阅读具体改动内容，理解变更意图。
3. 根据改动内容生成简洁、符合项目的提交信息（中文或与项目一致的风格）。
4. 需要时先 `git add` 相关文件，再 `git commit -m "<信息>"`。
5. 提交后运行 `git log --oneline -1` 确认提交成功，并把提交信息报告给用户。

额外要求：
- 不要提交与本次改动无关的文件。
- 提交信息开头动词用完成时态（如「添加」「修复」「重构」「升级」）。
- 若 $ARGUMENTS 非空，优先把它作为提交信息内容或补充。

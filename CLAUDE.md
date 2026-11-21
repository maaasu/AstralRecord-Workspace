@AGENTS.md

## Claude Code での読み替え

- スキルは `.codex/skills/` で管理する。`.claude/skills` はそこを参照する junction。Codex の `$<name>` と Claude Code の `/<name>` は同じスキルを指す。
- `.claude/skills` が無い、または空のときは `powershell -File 60_tool/agent-config/link-skills.ps1` を実行する（SessionStart フックでも自動実行。新規 worktree の初回セッションでは再起動が必要な場合がある）。
- `agents/openai.yaml` は Codex のメタデータとしてスキルの正本と一緒に管理する。
- サブエージェントは、共有ルールの役割に対応する Claude Code で利用可能なエージェントを Agent ツールで選ぶ。

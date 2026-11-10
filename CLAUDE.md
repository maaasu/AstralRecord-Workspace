@AGENTS.md

## Claude Code での読み替え

このリポジトリの指示とスキルは Codex と共用で、正本は1つだけ。**Claude 用の複製は作らない。**

| 種類 | 正本 | Claude Code 側 |
|:--|:--|:--|
| 指示 | 各ディレクトリの `AGENTS.md` | 同じ場所の `CLAUDE.md`（中身は `@AGENTS.md` の1行のみ） |
| スキル | `.codex/skills/<name>/SKILL.md` | `.claude/skills` が `.codex/skills` への junction |

- 指示の変更は `AGENTS.md` だけを編集する。`CLAUDE.md` は編集しない。新しいサブディレクトリに `AGENTS.md` を置くときは、同じ場所に `@AGENTS.md` だけの `CLAUDE.md` を置く。
- スキルの追加・編集は `.codex/skills/` だけで行う。Codex の `$<name>` と Claude Code の `/<name>` は同じスキルを指す。
- `.claude/skills` が無い、または空のときは `powershell -File 60_tool/agent-config/link-skills.ps1` を実行する（SessionStart フックでも自動実行。新規 worktree の初回セッションでは再起動が必要な場合がある）。
- `agents/openai.yaml` は Codex 専用のメタデータ。Claude Code では使われないが、削除せず正本側で保守する。
- `AGENTS.md` のサブエージェント指定（GPT-6 / Luna / Sol / Astra）は Codex 向け。Claude Code では同等の役割を Agent ツールで代替し、モデル指定は無視する。
- 本ファイルの先頭にある `@AGENTS.md` 以外の記述は Claude 固有の補足だけに限定し、`AGENTS.md` の内容を重複させない。

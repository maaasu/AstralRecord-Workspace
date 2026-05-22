---
name: astralrecord-commit-develop
description: Inspect AstralRecord workspace git changes, select only appropriate files, generate a suitable commit message, and commit them on develop. Use when asked to commit plugin or monorepo changes to develop while excluding local build outputs, IDE settings, generated folders, secrets, appsettings.Development.json, and unrelated files.
---

# AstralRecord Commit Develop

## Core Rule

Commit only the files that belong to the requested change. Never use `git add .` or `git add -A`. Do not commit local build outputs, IDE settings, machine-local config, secrets, or unrelated user changes.

Follow the workspace commit rules in `E:\AstralRecord-Workspace\COMMIT_RULES.md` before committing.

## Workflow

1. Confirm branch and workspace:
   - Run `git status --short --branch`.
   - The current branch must be `develop`. If it is not `develop`, stop and ask before switching or committing.
2. Inspect all changes:
   - Run `git status --porcelain=v1 -uall`.
   - Run `git diff --stat` and targeted `git diff -- <path>` for modified tracked files.
   - For untracked text files, read only enough to understand whether they belong to the requested change.
3. Run the bundled classifier:
   - `python E:\AstralRecord-Workspace\.codex\skills\astralrecord-commit-develop\scripts\commit_candidate_audit.py E:\AstralRecord-Workspace`
   - Treat `EXCLUDE` as not stageable unless the user explicitly overrides.
   - Treat `REVIEW` as requiring human judgment from the actual diff.
4. Select files:
   - Include only files related to the user's requested change.
   - Exclude `target/`, IDE folders, dot-tool local settings, development appsettings, secrets, logs, temp files, and unrelated changes.
   - `.codex/skills/` is commit-eligible when the requested change is skill creation or skill update.
5. Stage safely:
   - Use explicit paths: `git add -- <path1> <path2> ...`.
   - After staging, run `git diff --cached --stat` and `git diff --cached --check`.
   - If staged content includes an excluded or unrelated file, unstage that file with `git restore --staged -- <path>`.
6. Compose a commit message:
   - Follow `E:\AstralRecord-Workspace\COMMIT_RULES.md`.
   - Use the dominant type: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, or `chore`.
   - Commit message text must be Japanese (type prefix may remain English, but summary must be Japanese).
   - Keep the subject concise and specific.
7. Commit:
   - Run `git commit -m "<type>: <summary>"`.
   - Report committed files, excluded files, and the commit hash.

## Safety Checks

Stop before committing if:

- The branch is not `develop`.
- The selected files mix unrelated purposes.
- A selected file looks like it may contain secrets or machine-local configuration.
- The diff contains user changes outside the requested scope and cannot be separated safely.
- The commit would be empty.

## Common Exclusions

Do not commit these unless the user explicitly requests and the file is safe:

- `target/`, `build/`, `out/`, `bin/`, `obj/`, `.gradle/`, `node_modules/`
- `.idea/`, `.vscode/`, `.vs/`, `.settings/`, `.obsidian/`, `.classpath`, `.project`, `.factorypath`, `.claude/`
- `.env`, `.env.*`, `*.secret.*`, `*secrets*.json`, `local.settings.json`
- `appsettings.Development.json`, `appsettings.Local.json`
- `20_api/AstralRecordApi/AstralRecordApi/appsettings.Development.json`
- `*.log`, `*.tmp`, `*.bak`, `.DS_Store`, `Thumbs.db`

Allow documented shared configuration when relevant: `.gitignore`, `.gitattributes`, `.editorconfig`, `.agents/`, `.github/`, `.codex/skills/`, and example config files such as `.env.example`.

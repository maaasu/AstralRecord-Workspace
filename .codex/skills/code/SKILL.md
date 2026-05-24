---
name: code
description: Alias for the AstralRecord implementation workflow. Use when the user invokes /code or $code, asks for workspace-local code implementation, asks to implement a design document, or requests Plugin/API/Web/database/filebase/resourcepack behavior changes in E:\AstralRecord-Workspace.
---

# Code

This is a short alias for the workspace implementation skill.

## Workflow

1. Treat this skill as a UI-friendly `/code` entry point.
2. Use `$astralrecord-code` as the source of truth for implementation rules.
3. Read `E:\AstralRecord-Workspace\AGENTS.md`, identify the target project, then follow the required project guide and references from `$astralrecord-code`.
4. Do not duplicate or override the detailed implementation rules here; keep this alias thin so `/code` always tracks the main skill.

## Reporting

Report in Japanese using the format required by `$astralrecord-code`.

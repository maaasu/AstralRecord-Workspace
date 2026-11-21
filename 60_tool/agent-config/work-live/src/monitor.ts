import { join } from 'node:path'
import { observeClaude } from './claude-turns.ts'
import { expireCodexLaunches, observeCodex, resolveCodexCallers } from './observe.ts'
import { newDraft, prune } from './state.ts'
import { pollClaude } from './watch-claude.ts'
import type { ClaudeCheckpoint } from './watch-claude.ts'
import { pollCodex } from './watch-codex.ts'
import type { CodexCheckpoint } from './watch-codex.ts'

export function createMonitor(home: string) {
  const draft = newDraft()
  const cursors: Parameters<typeof observeClaude>[5] = new Map()
  let claude: ClaudeCheckpoint = { files: new Map(), metadata: new Map(), sessions: [], aliases: new Map() }
  let codex: CodexCheckpoint = { files: new Map() }
  let nextClaudeAt = -Infinity
  let inProgress: Promise<void> | undefined

  async function collect(now: number) {
    const [rollouts, sessions] = await Promise.all([
      pollCodex(join(home, '.codex'), codex, now),
      now >= nextClaudeAt ? pollClaude(join(home, '.claude'), claude, now) : undefined,
    ])
    if (sessions) {
      const { checkpoint, changes, stopped, monitor } = sessions
      observeClaude(draft, changes, checkpoint.sessions, checkpoint.aliases, stopped, cursors, now)
      for (const path of cursors.keys()) {
        if (!checkpoint.files.has(path)) cursors.delete(path)
      }
      claude = checkpoint
      draft.value.monitors.sessions = monitor
      nextClaudeAt = now + 4_000
    }
    observeCodex(draft, rollouts.changes, now, claude.sessions)
    resolveCodexCallers(draft, claude.sessions, now)
    codex = rollouts.checkpoint
    draft.value.monitors.codex = rollouts.monitor
    expireCodexLaunches(draft, now)
    prune(draft, now)
  }

  return {
    draft,
    get sessions() { return claude.sessions },
    poll(now: number) {
      inProgress ??= collect(now).finally(() => { inProgress = undefined })
      return inProgress
    },
  }
}

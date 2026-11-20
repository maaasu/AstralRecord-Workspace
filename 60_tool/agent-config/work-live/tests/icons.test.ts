import { expect, test } from 'claude-code/testing'
import type { IconName, Kind } from '../types'
import { iconSvg, stepIcon } from '../hooks/icons'
import { notification, stepOf } from '../hooks/model'
import { parseSvg } from './fixtures/svg'

test('全アイコンが指定の色と寸法の SVG になり、実行中の輪が広がって消える', () => {
  const colors = {
    file: '#60A5FA', search: '#60A5FA', folder: '#60A5FA', edit: '#F59E0B', terminal: '#A78BFA',
    git: '#F97316', run: '#A78BFA', web: '#22D3EE', message: '#94A3B8', agent: '#8B8BF5',
    bell: '#F59E0B', delegate: '#10A37F', tool: '#94A3B8', done: '#22C55E', error: '#EF4444',
    user: '#6B7280', claude: '#D97757', cloud: '#C2410C', codex: '#10A37F', subagent: '#8B8BF5',
    unknown: '#9CA3AF', running: '#EF4444', idle: '#9CA3AF', stopped: '#9CA3AF', waiting: '#9CA3AF',
  } satisfies Record<IconName, string>
  for (const [name, color] of Object.entries(colors)) {
    const source = iconSvg(name as IconName)
    expect(parseSvg(source).root).toBe('<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">')
    expect(source).toContain('stroke="' + color + '"')
    expect(source).toContain('stroke-width="1.6"')
    expect(source.includes('<text')).toBe(name === 'unknown')
  }
  expect(parseSvg(iconSvg('file', 18)).root).toContain('width="18" height="18"')
  expect(iconSvg('running')).not.toContain('<style>')
  const running = iconSvg('running', 16, true)
  expect(parseSvg(running).elements.filter(({ attributes }) => attributes.get('class')?.startsWith('ripple'))).toHaveLength(2)
  expect(running).toContain('animation:ripple 1.6s linear infinite')
  expect(running).toContain('animation-delay:-.8s')
  expect(running).toContain('@media (prefers-reduced-motion: reduce)')
})

test('step は記録時のアイコンを使い、旧記録は作業種別で補う', () => {
  const kinds = {
    edit: 'edit', command: 'terminal', read: 'file', web: 'web', agent: 'agent',
    message: 'message', tool: 'tool', done: 'done', error: 'error',
  } satisfies Record<Kind, IconName>
  for (const [kind, icon] of Object.entries(kinds)) expect(stepIcon({ kind: kind as Kind })).toBe(icon)
  expect(stepIcon({ kind: 'command', icon: 'git' })).toBe('git')
  expect(stepIcon({ kind: 'read', icon: 'search' })).toBe('search')
  expect(stepOf('id', 0, notification('通知')).icon).toBe('bell')
})

import type { ActorKind, IconName, Kind, Step } from '../types/index.d.ts'

const firstPerson = '<circle cx="6" cy="5" r="2.3"/><path d="M1.5 13v-1a4.5 3.5 0 0 1 9 0v1"/>'
const secondPerson = '<path d="M10 2.9a2.3 2.3 0 1 1 0 4.3m1.8 2a3.8 3.3 0 0 1 2.7 3.1v.7"/>'
const agent = firstPerson + secondPerson
const icons: Record<IconName, { color: string; label: string; shape: string }> = {
  file: { color: '#60A5FA', label: '読む', shape: '<path d="M3.5 1.5h6l3 3v10h-9zM9.5 1.5v3h3M6 8h4m-4 3h4"/>' },
  search: { color: '#60A5FA', label: '検索', shape: '<circle cx="6.8" cy="6.8" r="4.3"/><path d="m10 10 3.5 3.5"/>' },
  folder: { color: '#60A5FA', label: '一覧', shape: '<path d="M2.5 3.5H6l1.5 2H13a1 1 0 0 1 1 1v5.8a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V4a.5.5 0 0 1 .5-.5Z"/>' },
  edit: { color: '#F59E0B', label: '編集', shape: '<path d="m2.5 13.5 1-4L11 2a1 1 0 0 1 1.4 0L14 3.6a1 1 0 0 1 0 1.4l-7.5 7.5zM9.5 3.5l3 3"/>' },
  terminal: { color: '#A78BFA', label: '実行', shape: '<rect x="1.5" y="2.5" width="13" height="11" rx="2"/><path d="m4.5 5.5 2.5 2.5-2.5 2.5m4 0h3"/>' },
  git: { color: '#F97316', label: 'git', shape: '<circle cx="4" cy="3.5" r="1.7"/><circle cx="4" cy="12.5" r="1.7"/><circle cx="12" cy="4.5" r="1.7"/><path d="M4 5.2v5.6m0-.4c0-3.4 8-.4 8-4.2"/>' },
  run: { color: '#A78BFA', label: '実行', shape: '<path d="m4 2.5 9 5.5-9 5.5z"/>' },
  web: { color: '#22D3EE', label: 'Web', shape: '<circle cx="8" cy="8" r="6"/><ellipse cx="8" cy="8" rx="2.6" ry="6"/><path d="M2 8h12"/>' },
  message: { color: '#94A3B8', label: '発言', shape: '<path d="M4 2.5h8a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H7l-3 2.5v-2.5a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z"/>' },
  agent: { color: '#8B8BF5', label: '委任', shape: agent },
  bell: { color: '#F59E0B', label: '通知', shape: '<path d="M8 2v1m-2 10a2.2 2.2 0 0 0 4 0M2.5 11c1.7-.8 1.7-2.7 1.7-4.2a3.8 3.8 0 1 1 7.6 0c0 1.5 0 3.4 1.7 4.2z"/>' },
  delegate: { color: '#10A37F', label: '依頼', shape: '<path d="M9 4V2.5H2.5v11H9V12M6 8h8m-3-3 3 3-3 3"/>' },
  tool: { color: '#94A3B8', label: '道具', shape: '<path d="M13.5 3a4 4 0 0 1-4.6 5.8l-4.9 5a1.4 1.4 0 0 1-2-2l5-4.9A4 4 0 0 1 12.7 2l-2.5 2.5 1.3 1.3z"/>' },
  done: { color: '#22C55E', label: '完了', shape: '<circle cx="8" cy="8" r="6"/><path d="m5 8 2 2 4-4"/>' },
  error: { color: '#EF4444', label: '失敗', shape: '<circle cx="8" cy="8" r="6"/><path d="m6 6 4 4m0-4-4 4"/>' },
  user: { color: '#6B7280', label: 'あなた', shape: '<circle cx="8" cy="5" r="2.5"/><path d="M2.5 13v-1a5.5 3.5 0 0 1 11 0v1"/>' },
  claude: { color: '#D97757', label: 'Claude', shape: '<path d="M8 2v12M2 8h12M3.75 3.75l8.5 8.5m0-8.5-8.5 8.5"/>' },
  cloud: { color: '#C2410C', label: 'クラウド', shape: '<path d="M4 12.5a2.7 2.7 0 1 1 .4-5.4 3.6 3.6 0 0 1 7-.7 3.1 3.1 0 0 1 .1 6.1z"/>' },
  codex: { color: '#10A37F', label: 'Codex', shape: '<path d="m5 5-3 3 3 3m6-6 3 3-3 3M9.5 3.5l-3 9"/>' },
  subagent: { color: '#8B8BF5', label: 'サブエージェント', shape: agent },
  unknown: { color: '#9CA3AF', label: '不明', shape: '<circle cx="8" cy="8" r="6"/><text x="8" y="11" font-family="sans-serif" font-size="9" text-anchor="middle" fill="#9CA3AF" stroke="none">?</text>' },
  running: { color: '#EF4444', label: '実行中', shape: '<circle cx="8" cy="8" r="4" fill="#EF4444" stroke="none"/>' },
  stopped: { color: '#9CA3AF', label: '停止', shape: '<circle cx="8" cy="8" r="5.5" stroke-dasharray="1 2.5"/>' },
  idle: { color: '#9CA3AF', label: '応答なし', shape: '<circle cx="8" cy="8" r="5.5" stroke-dasharray="1 2.5"/>' },
  waiting: { color: '#9CA3AF', label: '待機中', shape: '<circle cx="8" cy="8" r="4" fill="#9CA3AF" stroke="none"/>' },
}

const byKind: Record<Kind, IconName> = {
  edit: 'edit', command: 'terminal', read: 'file', web: 'web', agent: 'agent',
  message: 'message', tool: 'tool', done: 'done', error: 'error',
}

const byActor: Record<ActorKind, IconName> = {
  user: 'user', claude: 'claude', 'claude-remote': 'cloud', codex: 'codex', subagent: 'subagent', unknown: 'unknown',
}

export function stepIcon(step: Pick<Step, 'kind'> & Partial<Pick<Step, 'icon'>>): IconName {
  return step.icon ?? byKind[step.kind]
}

export function actorIcon(kind: ActorKind): IconName {
  return byActor[kind]
}

export function iconLabel(name: IconName): string {
  return icons[name].label
}

export function iconColor(name: IconName): string {
  return icons[name].color
}

export const reducedMotion = '@media (prefers-reduced-motion: reduce){*{animation:none!important}.smil,.motion-only{display:none}.still{display:inline}}'
export const rippleStyles = '.ripple{transform-origin:8px 8px;animation:ripple 1.6s linear infinite}.ripple-next{animation-delay:-.8s}@keyframes ripple{from{transform:scale(1);opacity:.55}to{transform:scale(1.65);opacity:0}}'

export const stepMotionStyles = `
.step-edit{transform-origin:8px 8px;animation:step-edit .5s ease-in-out infinite}
.step-cursor{animation:step-blink 1.2s steps(1) infinite}
.step-scan{animation:step-scan 1.4s linear infinite}
.step-lid{transform-origin:8px 13px;animation:step-lid 1.6s ease-in-out infinite}
.step-dot{animation:step-dot 1.2s ease-in-out infinite}.dot-next{animation-delay:-.8s}.dot-last{animation-delay:-.4s}
.step-run{animation:step-run 1.2s ease-in-out infinite}
.step-meridian{animation:step-meridian 1.8s ease-in-out infinite}.meridian-next{animation-delay:-.9s}
.step-person{animation:step-person 1.4s ease-in-out infinite}.person-next{animation-delay:-.7s}
.step-delegate{transform-origin:6px 8px;animation:step-delegate 1.3s ease-in-out infinite}
.step-other{animation:step-other 2.2s ease-in-out infinite}.still{display:none}
@keyframes step-edit{0%,100%{transform:rotate(-10deg)}50%{transform:rotate(10deg)}}
@keyframes step-blink{50%{opacity:.12}}
@keyframes step-scan{0%{transform:translateY(0);opacity:0}20%{opacity:.7}80%{opacity:.7}100%{transform:translateY(7px);opacity:0}}
@keyframes step-lid{50%{transform:scaleY(1.25) skewX(-6deg)}}
@keyframes step-dot{0%,100%{opacity:.35}22%{opacity:1}}
@keyframes step-run{50%{transform:translateX(2px)}}
@keyframes step-meridian{
  0%{d:path("M8 2C0 3 0 13 8 14");opacity:0}
  50%{d:path("M8 2C8 3 8 13 8 14");opacity:.85}
  100%{d:path("M8 2C16 3 16 13 8 14");opacity:0}
}
@keyframes step-person{50%{transform:translateY(-1.4px)}}
@keyframes step-delegate{50%{transform:scaleX(1.22)}}
@keyframes step-other{50%{opacity:.55}}
`

const stepShapes: Partial<Record<IconName, string>> = {
  edit: `<g class="step-edit">${icons.edit.shape}</g>`,
  terminal: '<rect x="1.5" y="2.5" width="13" height="11" rx="2"/><path d="m4.5 5.5 2.5 2.5-2.5 2.5"/><path class="step-cursor" d="M9 10.5h3"/>',
  file: icons.file.shape + '<path class="step-scan motion-only" d="M5 5.5h6" stroke="#FFFFFF" opacity="0"/>',
  search: `<g class="still">${icons.search.shape}</g><g class="smil">${icons.search.shape}<animateMotion path="M-1 0a1 1 0 1 1 2 0a1 1 0 1 1-2 0" dur="1.6s" repeatCount="indefinite"/></g>`,
  folder: icons.folder.shape + '<path class="step-lid motion-only" d="M2.5 7.5h11l-1 5.8H3z" fill="#1F2937"/>',
  git: '<path d="M4 5.2v5.6m0-.4c0-3.4 8-.4 8-4.2"/><circle class="step-dot" cx="4" cy="3.5" r="1.7"/><circle class="step-dot dot-next" cx="4" cy="12.5" r="1.7"/><circle class="step-dot dot-last" cx="12" cy="4.5" r="1.7"/>',
  run: `<g class="step-run">${icons.run.shape}</g>`,
  web: '<circle cx="8" cy="8" r="6"/><path d="M2 8h12"/><path class="step-meridian" d="M8 2C4.5 2 4.5 14 8 14"/><path class="step-meridian meridian-next" d="M8 2C11.5 2 11.5 14 8 14"/>',
  message: icons.message.shape + '<g class="motion-only" fill="currentColor" stroke="none"><circle class="step-dot" cx="5" cy="7" r="1"/><circle class="step-dot dot-next" cx="8" cy="7" r="1"/><circle class="step-dot dot-last" cx="11" cy="7" r="1"/></g>',
  agent: `<g class="step-person">${firstPerson}</g><g class="step-person person-next">${secondPerson}</g>`,
  delegate: '<path d="M9 4V2.5H2.5v11H9V12"/><path class="step-delegate" d="M6 8h8m-3-3 3 3-3 3"/>',
}
export function iconBody(name: IconName, animated = false): string {
  const { color, shape } = icons[name]
  let body = shape
  if (animated) {
    body = name === 'running'
      ? '<circle class="ripple motion-only" cx="8" cy="8" r="4" opacity="0" stroke-width="1"/><circle class="ripple ripple-next motion-only" cx="8" cy="8" r="4" opacity="0" stroke-width="1"/>' + shape
      : stepShapes[name === 'subagent' ? 'agent' : name] ?? `<g class="step-other">${shape}</g>`
  }
  return `<g fill="none" stroke="${color}" color="${color}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">${body}</g>`
}

export function iconSvg(name: IconName, size = 16, interactive = false): string {
  const animated = name === 'running' && interactive
  const style = animated ? `<style>${rippleStyles}${reducedMotion}</style>` : ''
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 16 16">${style}${iconBody(name, animated)}</svg>`
}

export function iconAssets() {
  return Object.fromEntries((Object.keys(icons) as IconName[]).map(name => [name, iconSvg(name)])
    .concat([['running-motion', iconSvg('running', 16, true)]]))
}

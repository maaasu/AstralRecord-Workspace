import type { EngineInterface, RenderInput } from 'claude-code'
import type { Actor, DisplayScope, IconName, LiveBoard, Monitor, RequestDetail } from '../types'
import { buildFlowTree, cachedFlow, flowRows, groupFlows, visibleFlows } from './flow'
import type { FlowRequest } from './flow'
import { displayMarkdown } from './display-markdown'
import { actorIcon, iconColor, iconLabel, iconSvg, stepIcon } from './icons'
import { codeText, displayText, shortName } from './model'
import { ALL_SESSIONS, involves, sessionFlows, sessionView, TAB_LIMIT } from './session-view'
import type { SessionTab } from './session-view'
import { readBoard, readDetail, selectRequest, selectScope, selectSession, toggleAllSessions, toggleDetail } from './state'
import type { Runtime } from './state'

const SCOPES: { key: DisplayScope; label: string }[] = [
  { key: 'running', label: '実行中' }, { key: 'hour', label: '直近 1 時間' }, { key: 'all', label: 'すべて（2 時間）' },
]

function monitorDisplay(monitor: Monitor): { icon: IconName; status: string; text: string } {
  if (monitor.status === 'unavailable') return {
    icon: 'error', status: '使用不可', text: '使用不可（' + displayText(monitor.reason) + '）',
  }
  return monitor.status === 'ok' ? { icon: 'done', status: '動作中', text: '動作中' } : { icon: 'idle', status: '停止', text: '停止' }
}

function clockText(at: number): string {
  const time = new Date(at)
  return [time.getHours(), time.getMinutes(), time.getSeconds()].map(part => String(part).padStart(2, '0')).join(':')
}

function statusText(node: FlowRequest, terminal: boolean, detail = false): string {
  const label = iconLabel(node.status)
  const status = terminal ? '[' + label + ']' : node.status === 'error' ? 'エラーあり' : label
  if (node.status === 'idle') return status + (detail ? '（' + node.idleMinutes + ' 分）' : ' ' + node.idleMinutes + ' 分')
  if (node.status === 'stopped' && detail) return '停止（結果不明）'
  return status + (node.status === 'running' && !detail ? '・' + node.request.stepCount + ' 件' : '')
}

function actorName(actor: Actor, short = false): string {
  const label = displayText(actor.label) || iconLabel(actorIcon(actor.kind))
  return short ? shortName(label) : label
}

function renderIcon($: EngineInterface, e: RenderInput<'Pane'>, name: IconName, label = iconLabel(name), interactive = false, size = 16) {
  if (e.surface === 'terminal') {
    const { Text } = $.ui.resolve(e)
    return <Text>{'[' + label + ']'}</Text>
  }
  const { Svg } = $.ui.resolve(e)
  return <Svg source={iconSvg(name, size, interactive)} width={size} height={size} alt={label} isInteractive={interactive || undefined} />
}

function tabStatus(tab: SessionTab): string {
  if (tab.status === 'running') return '実行中・' + tab.stepCount + ' 件'
  if (tab.status === 'idle') return '応答なし ' + tab.latest?.idleMinutes + ' 分'
  return tab.status === 'waiting' ? '待機' : iconLabel(tab.status)
}

function renderSessionTabs(
  $: EngineInterface, e: RenderInput<'Pane'>, runtime: Runtime, tabs: SessionTab[], session: string, expanded: boolean,
) {
  const { Box, Text, Button } = $.ui.resolve(e)
  const terminal = e.surface === 'terminal'
  const entries = expanded ? tabs : tabs.slice(0, TAB_LIMIT)
  const overflow = tabs.length - TAB_LIMIT
  const allSelected = session === ALL_SESSIONS
  const animated = new Set(entries.filter(tab => tab.status === 'running').slice(0, TAB_LIMIT))
  return (
    <Box key="session-tabs" flexDirection="row" flexWrap="wrap" gap={1} alignItems="center">
      {entries.map(tab => {
        const { actor, status } = tab
        const selected = session === actor.id
        const key = 'session:' + actor.id
        const name = actorName(actor)
        return terminal
          ? <Button key={key} plain label={name + (selected ? ' [選択中]' : '')} onPress={() => selectSession($, runtime, actor.id)} />
          : <Box key={'tab:' + actor.id} flexDirection="column" borderStyle="round"
              borderColor={selected ? '#D97757' : undefined} paddingX={1}>
            <Box flexDirection="row" alignItems="center" gap={1}>
              {renderIcon($, e, status, status === 'waiting' ? '待機' : iconLabel(status), animated.has(tab))}
              {renderIcon($, e, actorIcon(actor.kind))}
              <Button key={key} plain label={name} onPress={() => selectSession($, runtime, actor.id)} />
            </Box>
            <Text dimColor>{tabStatus(tab)}</Text>
          </Box>
      })}
      {overflow > 0 && <Button key="session:more" plain label={expanded ? '表示を戻す' : 'ほか ' + overflow + ' 件'}
        onPress={() => toggleAllSessions($, runtime)} />}
      {terminal
        ? <Button key="session:all" plain label={'すべて' + (allSelected ? ' [選択中]' : '')}
            onPress={() => selectSession($, runtime, ALL_SESSIONS)} />
        : <Box key="tab:all" borderStyle="round" borderColor={allSelected ? '#D97757' : undefined} paddingX={1}>
            <Button key="session:all" plain label="すべて" onPress={() => selectSession($, runtime, ALL_SESSIONS)} />
          </Box>}
    </Box>
  )
}

function renderSessionHeading($: EngineInterface, e: RenderInput<'Pane'>, tab: SessionTab) {
  const { Box, Text } = $.ui.resolve(e)
  const received = tab.incoming.map(({ from, count }) => actorName(from, true) + 'から ' + count + ' 件').join('、') || '0 件'
  const start = tab.first === undefined ? '' : '開始 ' + clockText(tab.first).slice(0, 5) + ' ・ '
  return <Box key="session-heading" flexDirection="row" flexWrap="wrap" alignItems="center" gap={1}>
    {renderIcon($, e, actorIcon(tab.actor.kind), undefined, false, 20)}
    <Text bold>{actorName(tab.actor)}</Text>
    <Text dimColor wrap="wrap">{start + received + '受けて、' + tab.outgoing + ' 件依頼'}</Text>
  </Box>
}

function renderDetail(
  $: EngineInterface, e: RenderInput<'Pane'>, runtime: Runtime, live: LiveBoard, node: FlowRequest, detail: RequestDetail,
) {
  const { Box, Text, Button, Code, Markdown } = $.ui.resolve(e)
  const terminal = e.surface === 'terminal'
  const { request, from, to } = node
  const lines = codeText(detail.prompt).split('\n')
  const prompt = (live.promptExpanded ? lines : lines.slice(0, 20)).join('\n')
  const steps = live.olderStepsExpanded ? detail.steps : detail.steps.slice(-15)
  return (
    <Box key={'detail:' + request.id} flexDirection="column" paddingLeft={2}>
      <Box flexDirection="row" alignItems="center" gap={1}>
        {!terminal && renderIcon($, e, actorIcon(from.kind), '依頼元: ' + actorName(from))}
        {!terminal && renderIcon($, e, actorIcon(to.kind), '依頼先: ' + actorName(to))}
        <Text bold wrap="wrap">{actorName(from) + ' が ' + actorName(to) + ' に' + displayText(request.verb) + (request.estimatedObservation ? '（推定）' : '')}</Text>
      </Box>
      <Text dimColor wrap="wrap">{'開始 ' + clockText(request.at) +
        (request.endedAt === undefined ? '' : ' ・ 終了 ' + clockText(request.endedAt)) + ' ・ ' + statusText(node, terminal, true)}</Text>
      <Box flexDirection="column">
        <Text wrap="wrap"><Text bold>依頼内容</Text><Text dimColor>{'　' + displayText(detail.promptSource)}</Text></Text>
        {detail.prompt ? <Markdown key={'prompt:' + request.id} text={displayMarkdown(prompt)} onLinkPress={() => {}} />
          : <Text dimColor>依頼文を取得できませんでした</Text>}
        {lines.length > 20 && <Button key="toggle-prompt" plain
          label={live.promptExpanded ? '折りたたむ' : '全文を表示（あと ' + (lines.length - 20) + ' 行）'}
          onPress={() => toggleDetail($, runtime, request.id, 'prompt')} />}
      </Box>
      {detail.steps.length > 0 && (
        <Box flexDirection="column">
          <Text bold>実行内容</Text>
          {detail.steps.length > 15 && <Button key="older-steps" plain
            label={live.olderStepsExpanded ? '新しい 15 件に戻す' : '前の ' + (detail.steps.length - 15) + ' 件を表示'}
            onPress={() => toggleDetail($, runtime, request.id, 'older')} />}
          {steps.map(step => (
            <Box key={'step-row:' + step.id} flexDirection="column">
              <Box key={'step-summary:' + step.id} flexDirection="row" alignItems="center" gap={1}>
                {renderIcon($, e, stepIcon(step))}
                <Button key={step.id} plain onPress={() => toggleDetail($, runtime, request.id, 'step', step.id)}>
                  <Text wrap="wrap">{clockText(step.at) + ' ' + (displayText(step.title) || '作業')}</Text>
                </Button>
              </Box>
              {live.expandedStep === step.id &&
                <Code source={codeText(step.full)} language={step.language ?? 'text'} wrap="wrap" />}
            </Box>
          ))}
        </Box>
      )}
    </Box>
  )
}

export async function renderPane($: EngineInterface, e: RenderInput<'Pane'>, runtime: Runtime) {
  const { Box, Text, Button } = $.ui.resolve(e)
  const terminal = e.surface === 'terminal'
  const live = await readBoard($)
  const now = await $.clock.now()
  const all = visibleFlows(groupFlows({ ...live, now }), now, 'all')
  const { tabs, selected: selectedSession, session } = sessionView(live.actors, all, live.selectedSession)
  const selectedFlows = sessionFlows(all, session)
  const active = selectedFlows.flatMap(flow => flowRows(flow.root)).map(({ node }) => node)
    .filter(({ request, status }) => status === 'running' && (session === ALL_SESSIONS || involves(request, session)))
  if (e.props.placement === 'widget') {
    const latest = active.sort((a, b) => b.request.at - a.request.at)[0]
    return (
      <Box flexDirection="row" alignItems="center" gap={1}>
        {renderIcon($, e, latest ? 'running' : 'waiting')}
        {latest && <Text wrap="truncate-end">{(terminal ? '' : '実行中: ') + actorName(latest.from, true) +
          ' から ' + actorName(latest.to, true) + ' へ：' + (displayText(latest.request.summary) || '作業')}</Text>}
        {!latest && !terminal && <Text>待機中</Text>}
      </Box>
    )
  }

  const flows = visibleFlows(selectedFlows, now, live.scope)
  const activeCount = active.length
  const detail = live.selectedRequest ? await readDetail($, live.selectedRequest) : undefined
  return (
    <Box flexDirection="column">
      {renderSessionTabs($, e, runtime, tabs, session, live.allSessionsExpanded)}
      <Box flexDirection="row" justifyContent="space-between" flexWrap="wrap">
        <Box flexDirection="row" flexWrap="wrap" gap={1}>
          {SCOPES.map(({ key, label }) => <Button key={'scope:' + key} label={label}
            variant={live.scope === key ? 'primary' : undefined}
            onPress={() => selectScope($, runtime, key)} />)}
        </Box>
        <Box flexDirection="row" alignItems="center" gap={1}>
          {renderIcon($, e, activeCount ? 'running' : 'waiting', undefined, activeCount > 0)}
          {activeCount > 0 && <Text color={iconColor('running')}>{'LIVE ' + activeCount + ' 件'}</Text>}
          {!activeCount && !terminal && <Text color={iconColor('waiting')}>待機中</Text>}
        </Box>
      </Box>
      {selectedSession && renderSessionHeading($, e, selectedSession)}
      {!flows.length && <Text dimColor>{all.length ? 'この範囲に作業はありません' : 'まだ作業はありません'}</Text>}
      {flows.map(({ root, status }) => {
        const summary = displayText(root.request.summary) || '作業'
        const flow = terminal ? undefined : cachedFlow(root, selectedSession?.actor.id, now)
        let diagram
        if (e.surface !== 'terminal' && flow?.source) {
          const { Svg } = $.ui.resolve(e)
          const { source, width, height } = flow
          diagram = <Svg source={source} width={width} height={height} alt={summary + 'の依頼フロー図'} isInteractive />
        } else {
          diagram = <Box flexDirection="column">
            {buildFlowTree(root, now).map((line, index) => <Text key={'tree:' + index} wrap="wrap">
              {terminal ? line : displayText(line.replace(/[└├─│→]/gu, ' '))}
            </Text>)}
          </Box>
        }
        return (
          <Box key={'flow:' + root.request.id} flexDirection="column" borderStyle="round" padding={1} marginBottom={1}>
            <Box flexDirection="row" alignItems="center" gap={1}>
              {renderIcon($, e, status)}
              <Text bold wrap="wrap">{summary}</Text>
              <Text dimColor>{clockText(root.request.at)}</Text>
            </Box>
            {diagram}
            {flowRows(root).map(({ node, depth, number }) => {
              const { request, from, to } = node
              const selected = live.selectedRequest === request.id
              const dimmed = session !== ALL_SESSIONS && !involves(request, session)
              return (
                <Box key={'row:' + request.id} flexDirection="column">
                  <Box key={'request-summary:' + request.id} flexDirection="row" alignItems="center" gap={1} paddingLeft={depth * 4}>
                    {!terminal && renderIcon($, e, node.status)}
                    {!terminal && renderIcon($, e, actorIcon(from.kind), '依頼元: ' + actorName(from))}
                    <Button key={request.id} plain onPress={() => selectRequest($, runtime, request.id)}>
                      <Text wrap="wrap" bold={selected} dimColor={dimmed}>
                        {number + '. ' + actorName(from, true) + ' が ' + actorName(to, true) + ' に' + displayText(request.verb)}
                      </Text>
                    </Button>
                    <Text dimColor color={!dimmed && (node.status === 'idle' || node.status === 'stopped') ? iconColor(node.status) : undefined}>{statusText(node, terminal)}</Text>
                  </Box>
                  {selected && detail && renderDetail($, e, runtime, live, node, detail)}
                </Box>
              )
            })}
          </Box>
        )
      })}
      {([['Codex 監視', live.monitors.codex], ['セッション監視', live.monitors.sessions]] as const).map(([label, monitor]) => {
        const { icon, status, text } = monitorDisplay(monitor)
        return (
          <Box key={label} flexDirection="row" alignItems="center" gap={1}>
            {!terminal && renderIcon($, e, icon, status)}
            <Text dimColor wrap="wrap">{label + ': ' + text}</Text>
          </Box>
        )
      })}
    </Box>
  )
}

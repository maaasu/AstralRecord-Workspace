const SESSION_KEY = 'work-live:session'
const SCOPE_KEY = 'work-live:scope'

function element(tag, className, value) {
  const node = document.createElement(tag)
  if (className) node.className = className
  if (value !== undefined) node.textContent = value
  return node
}

export function text(node, value) {
  if (node.textContent !== value) node.textContent = value
}

function button(className, value) {
  const node = element('button', className, value)
  node.type = 'button'
  return node
}

function icon(extra = '') {
  const node = element('span', extra ? 'icon ' + extra : 'icon')
  node.setAttribute('aria-hidden', 'true')
  return node
}

function setIcon(node, name) {
  if (node.dataset.icon === name) return
  node.dataset.icon = name
  node.innerHTML = Object.hasOwn(icons, name) ? icons[name] : ''
}

export function disableReplayedMotion(graphic, played, now) {
  const fresh = new Set()
  for (const effect of graphic.querySelectorAll('[data-once]')) {
    const key = effect.getAttribute('data-once')
    const expiry = played.get(key)
    if (expiry !== undefined && expiry >= now) {
      if (effect.classList.contains('smil')) effect.remove()
      else effect.removeAttribute('class')
    } else {
      fresh.add(key)
    }
  }
  for (const [key, expires] of played) {
    if (expires < now) played.delete(key)
  }
  for (const key of fresh) played.set(key, now + 6_000)
}

function clock(at) {
  const date = new Date(at)
  return [date.getHours(), date.getMinutes(), date.getSeconds()].map(part => String(part).padStart(2, '0')).join(':')
}

function keyed(parent, create, update) {
  const entries = new Map()
  return items => {
    const keep = new Set(items.map(item => item.id))
    for (const [id, item] of entries) {
      if (!keep.has(id)) {
        item.node.remove()
        entries.delete(id)
      }
    }
    let next = parent.firstElementChild
    for (const item of items) {
      let row = entries.get(item.id)
      if (!row) {
        row = create(item)
        entries.set(item.id, row)
      }
      update(row, item)
      if (row.node !== next) parent.insertBefore(row.node, next)
      next = row.node.nextElementSibling
    }
  }
}

function keepScrollAndFocus(update) {
  const { scrollX, scrollY } = window
  const focused = document.activeElement
  update()
  if (focused instanceof HTMLElement && focused.isConnected && focused !== document.activeElement) {
    focused.focus({ preventScroll: true })
  }
  if (window.scrollX !== scrollX || window.scrollY !== scrollY) window.scrollTo(scrollX, scrollY)
}

async function api(path, params) {
  const response = await fetch(path + '?' + new URLSearchParams(params))
  if (!response.ok) throw new Error(String(response.status))
  return response.json()
}

function loadPreference(key) {
  try { return localStorage.getItem(key) } catch { return null }
}

function scopeOf(value) {
  return value === 'running' || value === 'all' ? value : 'hour'
}

function start() {
  const sessions = document.querySelector('#sessions')
  const sessionStrip = sessions.parentElement
  const narrowTabs = window.matchMedia('(max-width: 600px)')
  const scopes = document.querySelector('#scopes')
  const live = document.querySelector('#live')
  const heading = document.querySelector('#session-heading')
  const empty = document.querySelector('#empty')
  const connection = document.querySelector('#connection')
  const flows = document.querySelector('#flows')
  const monitors = document.querySelector('#monitors')
  const liveIcon = icon()
  const liveText = element('span')
  const headingIcon = icon()
  const headingName = element('h2')
  const headingInfo = element('span', 'session-info')
  live.append(liveIcon, liveText)
  heading.append(headingIcon, headingName, headingInfo)

  const search = new URLSearchParams(location.search)
  let session = search.get('session') || loadPreference(SESSION_KEY)
  let scope = scopeOf(search.get('scope') ?? loadPreference(SCOPE_KEY))
  let expandedTabs = false
  let pointerInTabs = false
  let orderedTabs = []
  let selectedRequest = null
  let selectedStep = null
  let fullPrompt = false
  let olderSteps = false
  let board
  let detail
  let detailLoad
  let detailError = false
  let boardToken = 0
  let boardLoad
  let controlId = 0
  const playedMotion = new Map()

  function savePreferences() {
    const url = new URL(location.href)
    if (session) url.searchParams.set('session', session)
    else url.searchParams.delete('session')
    url.searchParams.set('scope', scope)
    if (url.href !== location.href) history.replaceState(null, '', url)
    try {
      if (session) localStorage.setItem(SESSION_KEY, session)
      else localStorage.removeItem(SESSION_KEY)
      localStorage.setItem(SCOPE_KEY, scope)
    } catch { /* The URL also retains the selection. */ }
  }

  const renderTabs = keyed(sessions, tab => {
    const node = button(tab.more ? 'more-tab' : 'session-tab')
    if (tab.more) {
      node.addEventListener('click', () => {
        expandedTabs = !expandedTabs
        paint()
      })
      return { node }
    }
    node.addEventListener('click', () => {
      session = tab.id
      savePreferences()
      void refresh()
    })
    if (tab.id === 'all') return { node }
    const statusIcon = icon()
    const actor = icon()
    const name = element('span', 'tab-name')
    const status = element('span', 'tab-status')
    const label = element('span', 'tab-title')
    label.append(statusIcon, actor, name)
    node.append(label, status)
    return { node, statusIcon, actor, name, status }
  }, (view, tab) => {
    if (tab.more) {
      text(view.node, tab.label)
      view.node.setAttribute('aria-expanded', String(expandedTabs))
      return
    }
    view.node.setAttribute('aria-pressed', String(tab.id === board.session))
    if (tab.id === 'all') {
      text(view.node, tab.label)
      return
    }
    setIcon(view.statusIcon, tab.animated ? 'running-motion' : tab.status)
    setIcon(view.actor, tab.icon)
    text(view.name, tab.label)
    view.node.title = tab.label
    text(view.status, tab.statusText)
    view.status.dataset.status = tab.status
  })

  function tabsEngaged() {
    return pointerInTabs || sessions.contains(document.activeElement)
  }

  function lockTabWidths() {
    const unlocked = [...sessions.children].filter(node => !node.style.inlineSize)
    const widths = unlocked.map(node => node.getBoundingClientRect().width)
    unlocked.forEach((node, index) => { node.style.inlineSize = widths[index] + 'px' })
  }

  function releaseTabs() {
    if (!tabsEngaged()) paint()
  }

  function updateTabEdges() {
    const { clientWidth, scrollLeft, scrollWidth } = sessions
    sessionStrip.dataset.overflowStart = String(scrollLeft > 1)
    sessionStrip.dataset.overflowEnd = String(scrollLeft + clientWidth < scrollWidth - 1)
  }

  function selectedTab() {
    return sessions.querySelector('button[aria-pressed="true"]')
  }

  function revealTab(tab) {
    if (!narrowTabs.matches || !tab) return
    const left = tab.offsetLeft - 4
    const right = tab.offsetLeft + tab.offsetWidth + 4
    if (left < sessions.scrollLeft) sessions.scrollLeft = left
    else if (right > sessions.scrollLeft + sessions.clientWidth) sessions.scrollLeft = right - sessions.clientWidth
  }

  function paintTabs() {
    const engaged = tabsEngaged()
    if (engaged && orderedTabs.length) {
      const latest = new Map(board.tabs.map(tab => [tab.id, tab]))
      orderedTabs = orderedTabs.map(tab => latest.get(tab.id) ?? tab)
    } else {
      orderedTabs = board.tabs
    }
    const { session, tabLimit } = board
    const visible = expandedTabs ? orderedTabs : orderedTabs.slice(0, tabLimit)
    const selected = orderedTabs.find(tab => tab.id === session)
    if (selected && !visible.includes(selected)) visible[visible.length - 1] = selected
    const animated = new Set(visible.filter(tab => tab.status === 'running').slice(0, tabLimit).map(tab => tab.id))
    const overflow = orderedTabs.length - tabLimit
    const before = selectedTab()
    const offset = before?.offsetLeft
    const width = before?.offsetWidth
    if (!engaged) {
      for (const node of sessions.children) node.style.removeProperty('inline-size')
    }
    renderTabs([
      ...visible.map(tab => ({ ...tab, animated: animated.has(tab.id) })),
      ...(overflow > 0 ? [{ id: 'more', more: true, label: expandedTabs ? '表示を戻す' : 'ほか ' + overflow + ' 件' }] : []),
      { id: 'all', label: 'すべて' },
    ])
    if (engaged) lockTabWidths()
    const active = selectedTab()
    if (active !== before || active?.offsetLeft !== offset || active?.offsetWidth !== width) revealTab(active)
    updateTabEdges()
  }

  function requestDetail(rowView, row) {
    const view = rowView.detail
    view.panel.hidden = row.id !== selectedRequest
    if (view.panel.hidden) return
    setIcon(view.from, row.fromIcon)
    setIcon(view.to, row.toIcon)
    text(view.label, row.fullLabel)
    text(view.times, '開始 ' + clock(row.at) + (row.endedAt === undefined ? '' : ' ・ 終了 ' + clock(row.endedAt)) +
      ' ・ ' + row.detailStatusText)
    const data = detail?.id === row.id ? detail.value : undefined
    view.body.hidden = !data
    view.notice.hidden = !!data
    if (!data) {
      text(view.notice, detailError ? '詳細を読み込めませんでした。' : '詳細を読み込み中…')
      return
    }
    text(view.source, data.promptSource)
    const lines = data.prompt.split('\n')
    view.prompt.hidden = !data.prompt
    view.noPrompt.hidden = !!data.prompt
    text(view.prompt, fullPrompt ? data.prompt : lines.slice(0, 20).join('\n'))
    view.promptToggle.hidden = lines.length <= 20
    view.promptToggle.setAttribute('aria-expanded', String(fullPrompt))
    text(view.promptToggle, fullPrompt ? '折りたたむ' : '全文を表示（あと ' + (lines.length - 20) + ' 行）')

    view.stepsHeading.hidden = data.steps.length === 0
    view.older.hidden = data.steps.length <= 15
    view.older.setAttribute('aria-expanded', String(olderSteps))
    text(view.older, olderSteps ? '新しい 15 件に戻す' : '前の ' + (data.steps.length - 15) + ' 件を表示')
    view.showSteps(olderSteps ? data.steps : data.steps.slice(-15))
  }

  function createDetail() {
    const panel = element('div', 'detail')
    panel.id = 'request-detail-' + ++controlId
    panel.hidden = true
    const title = element('div', 'detail-title')
    const from = icon()
    const to = icon()
    const label = element('strong')
    const times = element('p', 'muted')
    title.append(from, to, label)

    const notice = element('p', 'muted')
    const body = element('div', 'detail-body')
    const promptHeading = element('div', 'prompt-heading')
    const source = element('span', 'muted')
    promptHeading.append(element('strong', '', '依頼内容'), source)
    const prompt = element('pre', 'prompt')
    const noPrompt = element('p', 'muted', '依頼文を取得できませんでした')
    const promptToggle = button('plain-button')
    promptToggle.addEventListener('click', () => { fullPrompt = !fullPrompt; paint() })
    const stepsHeading = element('h3', 'steps-heading', '実行内容')
    const older = button('plain-button')
    older.addEventListener('click', () => { olderSteps = !olderSteps; selectedStep = null; paint() })
    const steps = element('div', 'steps')
    const showSteps = keyed(steps, step => {
      const node = element('div', 'step')
      const toggle = button('step-button')
      const symbol = icon()
      const time = element('time')
      const label = element('span', 'step-label')
      const code = element('pre')
      code.id = 'step-detail-' + ++controlId
      toggle.setAttribute('aria-controls', code.id)
      toggle.addEventListener('click', () => {
        selectedStep = selectedStep === step.id ? null : step.id
        paint()
      })
      toggle.append(symbol, time, label)
      node.append(toggle, code)
      return { node, toggle, symbol, time, label, code }
    }, (view, step) => {
      const open = selectedStep === step.id
      setIcon(view.symbol, step.icon)
      text(view.time, clock(step.at))
      text(view.label, step.title)
      view.toggle.setAttribute('aria-expanded', String(open))
      view.code.hidden = !open
      if (open) text(view.code, step.full)
    })
    body.append(promptHeading, prompt, noPrompt, promptToggle, stepsHeading, older, steps)
    panel.append(title, times, notice, body)
    return { panel, from, to, label, times, notice, body, source, prompt, noPrompt, promptToggle, stepsHeading, older, showSteps }
  }

  function createRequest(row) {
    const node = element('div', 'request')
    const toggle = button('request-button')
    const statusIcon = icon()
    const from = icon('request-origin')
    const label = element('span', 'request-label')
    const status = element('span', 'request-status')
    const detailView = createDetail()
    toggle.setAttribute('aria-controls', detailView.panel.id)
    toggle.addEventListener('click', () => {
      selectedRequest = selectedRequest === row.id ? null : row.id
      selectedStep = null
      fullPrompt = false
      olderSteps = false
      detail = undefined
      detailError = false
      detailLoad = undefined
      paint()
      loadDetail()
    })
    toggle.append(statusIcon, from, label, status)
    node.append(toggle, detailView.panel)
    return { node, toggle, statusIcon, from, label, status, detail: detailView }
  }

  function updateRequest(view, row) {
    view.node.style.setProperty('--depth', String(Math.min(row.depth, 4)))
    view.node.dataset.dimmed = String(row.dimmed)
    setIcon(view.statusIcon, row.status)
    setIcon(view.from, row.fromIcon)
    text(view.label, row.number + '. ' + row.label)
    text(view.status, row.statusText)
    view.toggle.setAttribute('aria-expanded', String(selectedRequest === row.id))
    requestDetail(view, row)
  }

  const renderFlows = keyed(flows, () => {
    const node = element('article', 'flow')
    const top = element('div', 'flow-heading')
    const symbol = icon()
    const title = element('h3')
    const time = element('time')
    const graphic = element('div', 'flow-graphic')
    const requests = element('div', 'requests')
    top.append(symbol, title, time)
    node.append(top, graphic, requests)
    return { node, symbol, title, time, graphic, renderRequests: keyed(requests, createRequest, updateRequest) }
  }, (view, flow) => {
    setIcon(view.symbol, flow.status)
    text(view.title, flow.summary)
    text(view.time, clock(flow.at))
    if (flow.svg !== view.svg) {
      // Only SVG produced by the server's drawing code goes through the HTML parser.
      const template = element('template')
      template.innerHTML = flow.svg
      disableReplayedMotion(template.content, playedMotion, performance.now())
      view.graphic.replaceChildren(template.content)
      view.svg = flow.svg
    }
    if (flow.svg) {
      const graphic = view.graphic.firstElementChild
      graphic.setAttribute('role', 'img')
      graphic.setAttribute('aria-label', flow.summary + 'の依頼フロー図')
    } else {
      const tree = view.graphic.firstElementChild ?? view.graphic.appendChild(element('pre'))
      text(tree, flow.tree.join('\n'))
    }
    view.renderRequests(flow.rows)
  })

  const renderMonitors = keyed(monitors, () => {
    const node = element('span', 'monitor')
    const symbol = icon()
    const status = element('span')
    node.append(symbol, status)
    return { node, symbol, status }
  }, (view, item) => {
    setIcon(view.symbol, item.status === 'ok' ? 'done' : item.status === 'unavailable' ? 'error' : 'idle')
    const value = item.status === 'ok' ? '動作中' : item.status === 'off' ? '停止' : '使用不可（' + item.reason + '）'
    text(view.status, item.id + '  ' + value)
  })

  function paint() {
    if (!board) return
    keepScrollAndFocus(() => {
      paintTabs()
      for (const node of scopes.querySelectorAll('button')) node.setAttribute('aria-pressed', String(node.dataset.scope === scope))
      live.dataset.active = String(board.activeCount > 0)
      setIcon(liveIcon, board.activeCount ? 'running-motion' : 'waiting')
      text(liveText, board.activeCount ? 'LIVE ' + board.activeCount + ' 件' : '待機中')
      heading.hidden = !board.heading
      if (board.heading) {
        const { label, icon, first, incoming, outgoing } = board.heading
        setIcon(headingIcon, icon)
        text(headingName, label)
        const count = incoming.map(({ from, count }) => from + 'から ' + count + ' 件').join('、') || '0 件'
        text(headingInfo, (first === undefined ? '' : '開始 ' + clock(first).slice(0, 5) + ' ・ ') +
          count + '受けて、' + outgoing + ' 件依頼')
      }
      empty.hidden = board.flows.length > 0
      text(empty, board.hasFlows ? 'この範囲に作業はありません' : 'まだ作業はありません')
      renderFlows(board.flows)
      renderMonitors([{ id: 'Codex 監視', ...board.monitors.codex }, { id: 'Claude 監視', ...board.monitors.sessions }])
    })
  }

  function loadDetail() {
    if (!selectedRequest) return
    const row = board?.flows.flatMap(flow => flow.rows).find(row => row.id === selectedRequest)
    if (!row || (detail?.id === row.id && detail.version === row.detailVersion) ||
      (detailLoad?.id === row.id && detailLoad.version === row.detailVersion)) return
    const key = { id: row.id, version: row.detailVersion }
    detailLoad = key
    void api('/api/detail', { id: row.id }).then(value => {
      if (detailLoad !== key) return
      detail = { ...key, value }
      detailError = false
    }).catch(() => {
      if (detailLoad === key) detailError = true
    }).finally(() => {
      if (detailLoad !== key) return
      detailLoad = undefined
      paint()
    })
  }

  async function refresh() {
    const key = session + '\0' + scope
    if (boardLoad === key) return
    boardLoad = key
    const token = ++boardToken
    try {
      const current = await api('/api/board', session ? { session, scope } : { scope })
      if (token !== boardToken) return
      if (current.tabs.length && current.session !== session) session = current.session
      if (!board && current.tabs.findIndex(tab => tab.id === session) >= current.tabLimit) expandedTabs = true
      board = current
      savePreferences()
      connection.hidden = true
      paint()
      loadDetail()
    } catch {
      if (token !== boardToken) return
      connection.hidden = false
      text(connection, '作業ライブに接続できません。再接続を試みています。')
    } finally {
      if (token === boardToken) boardLoad = undefined
    }
  }

  sessions.addEventListener('pointerenter', () => {
    pointerInTabs = true
    lockTabWidths()
  })
  sessions.addEventListener('pointerleave', () => {
    pointerInTabs = false
    releaseTabs()
  })
  sessions.addEventListener('focusin', lockTabWidths)
  sessions.addEventListener('focusout', () => queueMicrotask(releaseTabs))
  sessions.addEventListener('scroll', updateTabEdges, { passive: true })
  new ResizeObserver(() => {
    revealTab(selectedTab())
    updateTabEdges()
  }).observe(sessions)

  for (const node of scopes.querySelectorAll('button')) {
    node.addEventListener('click', () => {
      scope = scopeOf(node.dataset.scope)
      savePreferences()
      paint()
      void refresh()
    })
  }
  savePreferences()
  void refresh()
  setInterval(() => { void refresh() }, 2_000)
}

if (typeof document !== 'undefined') start()

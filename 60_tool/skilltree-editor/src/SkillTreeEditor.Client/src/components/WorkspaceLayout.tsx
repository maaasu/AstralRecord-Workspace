import { useEffect, useRef, useState, type ReactNode } from 'react'
import { ResizeHandle, usePanelSize } from './ResizeHandle'

interface WorkspaceLayoutProps {
  left: ReactNode
  center: ReactNode
  right: ReactNode
  leftVisible: boolean
  centerVisible: boolean
  rightVisible: boolean
  onActivatePane?: (pane: 'left' | 'center' | 'right') => void
}

const LEFT_DEFAULT = 280
const RIGHT_DEFAULT = 370
const LEFT_MIN = 210
const LEFT_MAX = 620
const RIGHT_MIN = 290
const RIGHT_MAX = 720

export function WorkspaceLayout({
  left,
  center,
  right,
  leftVisible,
  centerVisible,
  rightVisible,
  onActivatePane,
}: WorkspaceLayoutProps) {
  const [leftWidth, setLeftWidth] = usePanelSize('tree-left', LEFT_DEFAULT, LEFT_MIN, LEFT_MAX)
  const [rightWidth, setRightWidth] = usePanelSize('tree-right', RIGHT_DEFAULT, RIGHT_MIN, RIGHT_MAX)
  const grid = useRef<HTMLDivElement>(null)
  const leftPane = useRef<HTMLDivElement>(null)
  const rightPane = useRef<HTMLDivElement>(null)
  const [measured, setMeasured] = useState<{ left?: number; right?: number }>({})
  useEffect(() => {
    const host = grid.current
    if (!host || typeof ResizeObserver === 'undefined') return
    const measure = () => {
      if (!host.clientWidth || window.innerWidth <= 980) return
      const left = leftPane.current?.getBoundingClientRect().width || undefined
      const right = rightPane.current?.getBoundingClientRect().width || undefined
      setMeasured(current => current.left === left && current.right === right ? current : { left, right })
    }
    const observer = new ResizeObserver(measure)
    observer.observe(host)
    if (leftPane.current) observer.observe(leftPane.current)
    if (rightPane.current) observer.observe(rightPane.current)
    measure()
    return () => observer.disconnect()
  }, [leftWidth, rightWidth, leftVisible, centerVisible, rightVisible])
  const actualLeft = measured.left ?? leftWidth
  const actualRight = measured.right ?? rightWidth
  const [focusedPane, setFocusedPane] = useState('center')
  const panes = [
    { id: 'left', label: 'ノード一覧', visible: leftVisible },
    { id: 'center', label: 'キャンバス', visible: centerVisible },
    { id: 'right', label: '詳細・検証', visible: rightVisible },
  ] as const
  const activePane = panes.find((pane) => pane.id === focusedPane && pane.visible)?.id ?? panes.find((pane) => pane.visible)?.id

  return (
    <div className="workspace-layout">
    <div className="compact-pane-navigation" role="group" aria-label="作業領域の切替">{panes.filter((pane) => pane.visible || onActivatePane).map((pane) => <button key={pane.id} type="button" className={`button ${activePane === pane.id ? 'active' : ''}`} aria-pressed={activePane === pane.id} onClick={() => { setFocusedPane(pane.id); onActivatePane?.(pane.id) }}>{pane.label}</button>)}</div>
    <div ref={grid} className={centerVisible ? 'workspace-grid' : 'workspace-grid no-center'}>
      {leftVisible && <div ref={leftPane} className="workspace-pane left-pane" data-compact-active={activePane === 'left'} style={{ flexBasis: leftWidth }}>{left}</div>}
      {leftVisible && centerVisible && (
        <ResizeHandle
          label="ノード一覧の幅を変更"
          size={actualLeft}
          min={LEFT_MIN}
          max={LEFT_MAX}
          defaultSize={LEFT_DEFAULT}
          minRemaining={280 + (rightVisible ? actualRight + 7 : 0)}
          direction={1}
          onResize={width => { setLeftWidth(width); if (rightVisible) setRightWidth(actualRight) }}
        />
      )}
      {centerVisible && <div className="workspace-pane center-pane" data-compact-active={activePane === 'center'}>{center}</div>}
      {rightVisible && (centerVisible || leftVisible) && (
        <ResizeHandle
          label="インスペクターの幅を変更"
          size={actualRight}
          min={RIGHT_MIN}
          max={centerVisible ? RIGHT_MAX : Number.MAX_SAFE_INTEGER}
          defaultSize={RIGHT_DEFAULT}
          minRemaining={(centerVisible ? 280 : 0) + (leftVisible ? (centerVisible ? actualLeft + 7 : LEFT_MIN) : 0)}
          direction={-1}
          onResize={width => { setRightWidth(width); if (leftVisible) setLeftWidth(centerVisible ? actualLeft : actualLeft + actualRight - width) }}
        />
      )}
      {rightVisible && <div ref={rightPane} className="workspace-pane right-pane" data-compact-active={activePane === 'right'} style={{ flexBasis: rightWidth }}>{right}</div>}
    </div>
    </div>
  )
}

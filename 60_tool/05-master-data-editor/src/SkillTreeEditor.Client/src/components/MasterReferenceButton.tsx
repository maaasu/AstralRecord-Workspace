import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type ComponentProps } from 'react'
import { createPortal } from 'react-dom'
import { masterDataApi } from '../api/masterDataApi'
import { masterValueLabel } from '../data/masterValuePresentation'
import { usePanelVisibility } from '../docking/dockPanel'
import type { JsonObject } from '../types/editor'
import type { MasterFileSummary } from '../types/masterData'
import { referenceObject } from '../data/masterReferenceCollection'
import { MinecraftText } from './MinecraftText'
import './masterReferencePreview.css'

export interface ReferencePreview {
  file?: MasterFileSummary
  kind?: string
  label: string
  reference: string
}

const documents = new WeakMap<MasterFileSummary, Promise<JsonObject>>()
const openPreviews = new WeakMap<Document, () => void>()

function loadDetails(file: MasterFileSummary) {
  const cached = documents.get(file)
  if (cached) return cached
  const request = masterDataApi.file(file.path).then(document => {
    const content = referenceObject(document.content)
    if (!content || document.revision !== file.revision) throw new Error()
    return content
  }).catch(error => {
    documents.delete(file)
    throw error
  })
  documents.set(file, request)
  return request
}

type Props = ComponentProps<'button'> & { preview?: ReferencePreview }

export function MasterReferenceButton({ preview, onFocus, onBlur, onClick, ...props }: Props) {
  const visible = usePanelVisibility()
  const tooltipId = useId()
  const [anchor, setAnchor] = useState<HTMLButtonElement | null>(null)
  const [open, setOpen] = useState(false)
  const [details, setDetails] = useState<{ file: MasterFileSummary; content: JsonObject | null }>()
  const tooltip = useRef<HTMLDivElement>(null)
  const leaveTimer = useRef<ReturnType<typeof setTimeout>>(undefined)
  const pointerInside = useRef(false)
  const file = preview?.file
  const active = visible && file && anchor && !props.disabled
  const show = Boolean(active && open)

  const close = useCallback(() => {
    clearTimeout(leaveTimer.current)
    pointerInside.current = false
    setAnchor(null)
    setOpen(false)
  }, [])
  const closeOnLeave = () => {
    clearTimeout(leaveTimer.current)
    leaveTimer.current = setTimeout(close, 180)
  }

  useEffect(() => {
    if (!active || !file || !anchor) return
    const owner = anchor.ownerDocument
    let cancelled = false
    const timer = setTimeout(() => {
      const previous = openPreviews.get(owner)
      if (previous !== close) previous?.()
      openPreviews.set(owner, close)
      setOpen(true)
      void loadDetails(file).then(
        content => { if (!cancelled) setDetails({ file, content }) },
        () => { if (!cancelled) setDetails({ file, content: null }) },
      )
    }, 250)
    return () => {
      cancelled = true
      clearTimeout(timer)
      clearTimeout(leaveTimer.current)
      if (openPreviews.get(owner) === close) openPreviews.delete(owner)
    }
  }, [active, anchor, close, file])

  useEffect(() => { if (!visible || props.disabled) close() }, [visible, props.disabled, close])
  const content = details?.file === file ? details?.content : undefined
  useLayoutEffect(() => {
    const popup = tooltip.current
    const view = anchor?.ownerDocument.defaultView
    if (!show || !anchor || !popup || !view) return
    const target = anchor.getBoundingClientRect()
    const below = view.innerHeight - target.bottom - 14
    const above = target.top - 14
    const placeAbove = below < Math.min(above, 240)
    popup.style.maxHeight = Math.max(0, placeAbove ? above : below) + 'px'
    const box = popup.getBoundingClientRect()
    const top = placeAbove ? target.top - 6 - box.height : target.bottom + 6
    popup.style.left = Math.max(8, Math.min(target.left, view.innerWidth - box.width - 8)) + 'px'
    popup.style.top = top + 'px'
  }, [show, anchor, content])

  useEffect(() => {
    const view = anchor?.ownerDocument.defaultView
    if (!show || !view) return
    const scroll = (event: Event) => { if (event.target !== tooltip.current) close() }
    view.addEventListener('scroll', scroll, true)
    view.addEventListener('resize', close)
    view.addEventListener('blur', close)
    const dismiss = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.isComposing) return
      event.preventDefault()
      event.stopPropagation()
      close()
    }
    view.addEventListener('keydown', dismiss, true)
    return () => {
      view.removeEventListener('scroll', scroll, true)
      view.removeEventListener('resize', close)
      view.removeEventListener('blur', close)
      view.removeEventListener('keydown', dismiss, true)
    }
  }, [show, anchor, close])

  return <>
    <button {...props} title={file ? undefined : props.title ?? preview?.reference}
      aria-describedby={show ? [props['aria-describedby'], tooltipId].filter(Boolean).join(' ') : props['aria-describedby']}
      onPointerEnter={event => {
        if (!file || event.pointerType === 'touch') return
        pointerInside.current = true
        clearTimeout(leaveTimer.current)
        setAnchor(event.currentTarget)
      }}
      onPointerLeave={event => {
        pointerInside.current = false
        if (event.currentTarget.matches(':focus-visible')) return
        if (show) closeOnLeave()
        else close()
      }}
      onFocus={event => {
        if (file && event.currentTarget.matches(':focus-visible')) setAnchor(event.currentTarget)
        onFocus?.(event)
      }}
      onBlur={event => { if (!pointerInside.current) close(); onBlur?.(event) }}
      onClick={event => { close(); onClick?.(event) }} />
    {show && anchor && preview && createPortal(
      <div ref={tooltip} id={tooltipId} role="tooltip" className="master-reference-preview"
        onPointerEnter={() => {
          pointerInside.current = true
          clearTimeout(leaveTimer.current)
        }} onPointerLeave={() => {
          pointerInside.current = false
          if (!anchor.matches(':focus-visible')) closeOnLeave()
        }}>
        <strong><MinecraftText value={preview.label} /></strong>
        <code>{preview.reference}</code>
        {content ? <ReferenceDetails content={content} kind={preview.kind} />
          : <small>{content === null ? '詳細を取得できません。' : '詳細を読み込み中…'}</small>}
      </div>, anchor.ownerDocument.body,
    )}
  </>
}

function ReferenceDetails({ content, kind }: { content: JsonObject; kind?: string }) {
  const equipment = referenceObject(content.equipment)
  const lore = Array.isArray(content.lore) ? content.lore.filter((line): line is string => typeof line === 'string') : []
  const text = [content.description, ...lore].filter((line): line is string => typeof line === 'string' && !!line.trim())
  const facts = [
    ['種類', kind === 'mob' ? content.entityType
      : typeof content.category === 'string' ? masterValueLabel('category', content.category) : undefined],
    ['レベル', content.level],
    ['必要レベル', equipment?.requiredLevel],
    ['レアリティ', typeof content.rarity === 'string' ? masterValueLabel('rarity', content.rarity) : undefined],
  ]
  if (kind === 'mob' && Array.isArray(content.baseStats)) {
    for (const value of content.baseStats) {
      const stat = referenceObject(value)
      if (stat && typeof stat.status === 'string' && ['MAX_HEALTH', 'ATTACK', 'DEFENSE'].includes(stat.status)) {
        facts.push([masterValueLabel('status', stat.status), stat.value])
      }
    }
  }
  return <>
    <div className="master-reference-preview-facts">
      {facts.map(([label, value], index) => (typeof value === 'number' || typeof value === 'string' && value) &&
        <span key={index}>{String(label)} {String(value)}</span>)}
    </div>
    {text.length > 0 && <div className="master-reference-preview-description">
      {text.map((line, index) => <div key={index}><MinecraftText value={line} /></div>)}
    </div>}
  </>
}

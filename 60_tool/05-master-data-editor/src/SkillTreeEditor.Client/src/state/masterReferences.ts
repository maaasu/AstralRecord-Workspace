import { useCallback, useEffect, useRef, useState } from 'react'
import { masterDataApi } from '../api/masterDataApi'
import type { MasterReference } from '../types/masterData'

export interface MasterReferenceSource { path: string }
type ReferenceStatus = 'idle' | 'loading' | 'success' | 'error'
interface ReferenceState {
  source: MasterReferenceSource | null
  status: ReferenceStatus
  entries: MasterReference[]
  error: string
}
const emptyReferences: MasterReference[] = []
const emptyState = (source: MasterReferenceSource | null): ReferenceState => ({ source, status: 'idle', entries: emptyReferences, error: '' })

/** Source identity changes when saved data is refreshed; draft edits do not invalidate it. */
export function useMasterReferences(source: MasterReferenceSource | null, enabled: boolean) {
  const [state, setState] = useState<ReferenceState>(() => emptyState(source))
  const generation = useRef(0)
  const pending = useRef<number | null>(null)
  const current = state.source === source ? state : emptyState(source)

  useEffect(() => {
    generation.current++
    pending.current = null
    setState(emptyState(source))
    return () => { generation.current++; pending.current = null }
  }, [source])

  const reload = useCallback(() => {
    if (!source || pending.current === generation.current) return
    const requestGeneration = generation.current
    pending.current = requestGeneration
    setState({ ...emptyState(source), status: 'loading' })
    void masterDataApi.references(source.path).then((entries) => {
      if (generation.current !== requestGeneration) return
      pending.current = null
      setState({ source, status: 'success', entries, error: '' })
    }).catch((reason: unknown) => {
      if (generation.current !== requestGeneration) return
      pending.current = null
      setState({ ...emptyState(source), status: 'error', error: reason instanceof Error ? reason.message : '参照元を取得できませんでした。' })
    })
  }, [source])

  useEffect(() => {
    if (enabled && current.status === 'idle') reload()
  }, [enabled, current.status, reload])

  return { ...current, reload }
}

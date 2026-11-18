import { useCallback, useEffect, useRef, useState } from 'react'
import { masterDataApi } from '../api/masterDataApi'
import { buildMasterPredictionIndex, findLocalPredictions, updatePredictionHistory, type MasterPredictionEntry } from '../data/masterPrediction'
import type { MasterFileSummary } from '../types/masterData'

const emptyIndex = buildMasterPredictionIndex([])

export type SearchLocalPredictions = (path: string, pointer: string, prefix: string, maxLength: number, multiline: boolean) => {
  suggestions: string[]
  indexReady: boolean
}

export function useMasterPredictions(enabled: boolean, files: MasterFileSummary[], referenceVersion: number, refreshing: boolean) {
  const history = useRef<MasterPredictionEntry[]>([])
  const [loaded, setLoaded] = useState<{ files: MasterFileSummary[]; referenceVersion: number; index: typeof emptyIndex | null } | null>(null)
  const current = !refreshing && loaded?.files === files && loaded.referenceVersion === referenceVersion ? loaded : null
  const index = current?.index
  const failed = current !== null && index === null
  useEffect(() => {
    if (!enabled) return
    if (refreshing) { setLoaded(null); return }
    const request = new AbortController()
    void (async () => {
      try {
        const entries = await masterDataApi.predictionIndex(request.signal)
        if (!request.signal.aborted) setLoaded({ files, referenceVersion, index: buildMasterPredictionIndex(entries) })
      } catch {
        if (!request.signal.aborted) setLoaded({ files, referenceVersion, index: null })
      }
    })()
    return () => request.abort()
  }, [enabled, files, referenceVersion, refreshing])
  const search = useCallback<SearchLocalPredictions>((path, pointer, prefix, maxLength, multiline) => ({
    suggestions: findLocalPredictions(index ?? emptyIndex, history.current, path, pointer, prefix, maxLength, multiline),
    indexReady: index != null,
  }), [index])
  const remember = useCallback((path: string, pointer: string, text: string) => {
    history.current = updatePredictionHistory(history.current, path, pointer, text)
  }, [])
  return { search, remember, failed }
}

export type MasterPredictionSource = ReturnType<typeof useMasterPredictions>

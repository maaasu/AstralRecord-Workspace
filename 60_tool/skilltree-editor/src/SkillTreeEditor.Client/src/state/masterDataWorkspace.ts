import { useCallback, useEffect, useRef, useState } from 'react'
import { masterDataApi } from '../api/masterDataApi'
import type { MasterCatalog, MasterFileSummary, MasterFilterIndex, MasterReference } from '../types/masterData'

/** Catalog and list filters are shared by all open file tabs. */
export function useMasterDataWorkspace() {
  const [catalog, setCatalog] = useState<MasterCatalog | null>(null)
  const [files, setFiles] = useState<MasterFileSummary[]>([])
  const [filterIndex, setFilterIndex] = useState<MasterFilterIndex | null>(null)
  const [candidates, setCandidates] = useState<MasterReference[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [categoryId, setCategoryId] = useState('10.features.item')
  const [query, setQuery] = useState('')
  const [format, setFormat] = useState('')
  const [sourceQuery, setSourceQuery] = useState('')
  const [dropSourcePath, setDropSourcePath] = useState('')
  const [skillTag, setSkillTag] = useState('')
  const [visibleCount, setVisibleCount] = useState(50)
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const refreshId = useRef(0)
  const rememberedPaths = useRef<Record<string, string>>({})

  const refresh = useCallback(async () => {
    const requestId = ++refreshId.current
    let nextCatalog: MasterCatalog
    let nextFiles: MasterFileSummary[]
    let nextFilterIndex: MasterFilterIndex | null
    try {
      [nextCatalog, nextFiles, nextFilterIndex] = await Promise.all([
        masterDataApi.catalog(), masterDataApi.files(), masterDataApi.filterIndex().catch(() => null),
      ])
    } catch (reason) { if (requestId === refreshId.current) throw reason; return }
    if (requestId !== refreshId.current) return
    setCatalog(nextCatalog); setFiles(nextFiles); setFilterIndex(nextFilterIndex)
    setCategoryId((current) => nextCatalog.categories.some((entry) => entry.id === current) ? current
      : nextCatalog.categories.find((entry) => entry.directory === '10.features.item')?.id
      ?? nextCatalog.categories.find((entry) => entry.directory === '10.features.item/10.material')?.id
      ?? nextCatalog.categories[0]?.id ?? '')
    void masterDataApi.candidates().then((next) => { if (requestId === refreshId.current) setCandidates(next) }).catch(() => { /* Free input remains available. */ })
    if (!nextFilterIndex) {
      setDropSourcePath(''); setSourceQuery(''); setSkillTag('')
      setNotice('ドロップ元とタグの絞り込み情報を取得できませんでした。絞り込みを解除しました。再読込してください。')
    }
  }, [])
  useEffect(() => {
    void refresh().catch((reason) => setError(reason instanceof Error ? reason.message : String(reason))).finally(() => setLoading(false))
    return () => { refreshId.current++ }
  }, [refresh])
  useEffect(() => {
    if (!filterIndex) return
    const sourceMissing = Boolean(dropSourcePath && !filterIndex.sources.some((source) => source.path === dropSourcePath))
    const tagMissing = Boolean(skillTag && !filterIndex.tags.some((tag) => tag.id === skillTag))
    if (sourceMissing) { setDropSourcePath(''); setSourceQuery('') }
    if (tagMissing) setSkillTag('')
    if (sourceMissing || tagMissing) setNotice('選択していた絞り込み候補が見つからないため、絞り込みを解除しました。')
  }, [filterIndex, dropSourcePath, skillTag])
  useEffect(() => { setVisibleCount(50) }, [categoryId, query, format, dropSourcePath, skillTag])

  return {
    catalog, files, filterIndex, candidates, loading, error, notice, setError, setNotice, refresh, rememberedPaths,
    categoryId, setCategoryId, query, setQuery, format, setFormat, sourceQuery, setSourceQuery,
    dropSourcePath, setDropSourcePath, skillTag, setSkillTag, visibleCount, setVisibleCount,
    sidebarCollapsed, setSidebarCollapsed,
  }
}

export type MasterDataWorkspace = ReturnType<typeof useMasterDataWorkspace>

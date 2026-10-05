import { useCallback, useEffect, useRef, useState } from 'react'
import { masterDataApi } from '../api/masterDataApi'
import type { MasterCatalog, MasterFileSummary, MasterFilterIndex, MasterReference } from '../types/masterData'
import { categoryFacetOptions, inCategory, isDropSourceDirectory, isSkillDirectory } from '../data/masterDataFilters'

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
  const [dropItemQuery, setDropItemQuery] = useState('')
  const [dropItemId, setDropItemId] = useState('')
  const [dropPresence, setDropPresence] = useState('')
  const [facetFilters, setFacetFilters] = useState<Record<string, string>>({})
  const [fileStatus, setFileStatus] = useState('')
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
      setDropItemId(''); setDropItemQuery(''); setDropPresence(''); setFacetFilters({})
      setNotice('ドロップ元とタグなどの絞り込み情報を取得できませんでした。絞り込みを解除しました。再読込してください。')
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
  useEffect(() => {
    const directory = catalog?.categories.find((entry) => entry.id === categoryId)?.directory
    if (!directory) return
    if (skillTag && isSkillDirectory(directory) && filterIndex && !filterIndex.skillTags.some((entry) => inCategory(entry.path, directory) && entry.tags.includes(skillTag))) setSkillTag('')
    const options = categoryFacetOptions(files.filter((file) => inCategory(file.path, directory)), filterIndex)
    setFacetFilters((current) => {
      const next = Object.fromEntries(Object.entries(current).filter(([key, value]) => options[key]?.includes(value)))
      return Object.keys(next).length === Object.keys(current).length ? current : next
    })
    if (!isDropSourceDirectory(directory)) {
      setDropItemId(''); setDropItemQuery(''); setDropPresence('')
    } else if (filterIndex && dropItemId && !filterIndex.sources.some((source) => inCategory(source.path, directory) && source.itemIds.includes(dropItemId))) {
      setDropItemId(''); setDropItemQuery('')
      setNotice('選択していたドロップ品が見つからないため、ドロップ品の絞り込みを解除しました。')
    }
  }, [catalog, categoryId, files, filterIndex, dropItemId, skillTag])
  useEffect(() => { setVisibleCount(50) }, [categoryId, query, format, dropSourcePath, skillTag, dropItemId, dropPresence, facetFilters, fileStatus])

  return {
    catalog, files, filterIndex, candidates, loading, error, notice, setError, setNotice, refresh, rememberedPaths,
    categoryId, setCategoryId, query, setQuery, format, setFormat, sourceQuery, setSourceQuery,
    dropSourcePath, setDropSourcePath, skillTag, setSkillTag, visibleCount, setVisibleCount,
    dropItemQuery, setDropItemQuery, dropItemId, setDropItemId, dropPresence, setDropPresence,
    facetFilters, setFacetFilters, fileStatus, setFileStatus,
    sidebarCollapsed, setSidebarCollapsed,
  }
}

export type MasterDataWorkspace = ReturnType<typeof useMasterDataWorkspace>

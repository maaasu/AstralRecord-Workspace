import { useCallback, useEffect, useEffectEvent, useMemo, useRef, useState } from 'react'
import { masterDataApi } from '../api/masterDataApi'
import type { MasterCatalog, MasterFileSummary, MasterFilterIndex, MasterReference } from '../types/masterData'
import { categoryFacetOptions, categoryNumericOptions, inCategory, isDropSourceDirectory, type NumericFilters } from '../data/masterDataFilters'

/** Catalog and list filters are shared by all open file tabs. */
export function useMasterDataWorkspace(android = false) {
  const [catalog, setCatalog] = useState<MasterCatalog | null>(null)
  const [files, setFiles] = useState<MasterFileSummary[]>([])
  const filesByPath = useMemo(() => new Map(files.map((file) => [file.path, file])), [files])
  const [filterIndex, setFilterIndex] = useState<MasterFilterIndex | null>(null)
  const [candidates, setCandidates] = useState<MasterReference[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [referenceVersion, setReferenceVersion] = useState(0)
  const invalidateReferences = useCallback(() => setReferenceVersion((current) => current + 1), [])
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [categoryId, setCategoryId] = useState('10.features.item')
  const [query, setQuery] = useState('')
  const [searchScope, setSearchScope] = useState<'folder' | 'all'>(android ? 'all' : 'folder')
  const [browserView, setBrowserView] = useState<'folders' | 'files'>('files')
  const [expandedCategories, setExpandedCategories] = useState<Set<string>>(() => new Set(['10.features.item']))
  const [numericFilters, setNumericFilters] = useState<NumericFilters>({})
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

  const refresh = useCallback(async (reload = false) => {
    const requestId = ++refreshId.current
    // A save/create/delete can succeed even if the subsequent list refresh fails.
    if (!reload) invalidateReferences()
    setRefreshing(true)
    let nextCatalog: MasterCatalog
    let nextFiles: MasterFileSummary[]
    let nextFilterIndex: MasterFilterIndex | null
    let nextCandidates: MasterReference[] | null
    try {
      // 明示的な再読込では、索引と候補の取得前に一覧用の文書をファイルから読み直す。
      const fileList = reload ? masterDataApi.files('', '', true) : masterDataApi.files()
      const filterIndex = reload ? fileList.then(() => masterDataApi.filterIndex()) : masterDataApi.filterIndex()
      const referenceCandidates = fileList.then(() => masterDataApi.candidates())
      const result = await Promise.all([
        reload ? masterDataApi.catalog(true) : masterDataApi.catalog(),
        fileList,
        filterIndex.catch(() => null),
        referenceCandidates.catch(() => null),
      ])
      nextCatalog = result[0]
      nextFiles = result[1]
      nextFilterIndex = result[2]
      nextCandidates = result[3]
    } catch (reason) { if (requestId === refreshId.current) { setRefreshing(false); throw reason }; return }
    if (requestId !== refreshId.current) return
    if (reload) invalidateReferences()
    setRefreshing(false); setError('')
    setCatalog(nextCatalog); setFiles(nextFiles); setFilterIndex(nextFilterIndex)
    setCandidates(nextCandidates ?? [])
    setCategoryId((current) => nextCatalog.categories.some((entry) => entry.id === current) ? current
      : nextCatalog.categories.find((entry) => entry.directory === '10.features.item')?.id
      ?? nextCatalog.categories.find((entry) => entry.directory === '10.features.item/10.material')?.id
      ?? nextCatalog.categories[0]?.id ?? '')
    if (!nextFilterIndex) {
      setDropSourcePath(''); setSourceQuery(''); setSkillTag('')
      setDropItemId(''); setDropItemQuery(''); setDropPresence(''); setFacetFilters({})
      setNumericFilters({})
      setNotice('ドロップ元とタグなどの絞り込み情報を取得できませんでした。絞り込みを解除しました。再読込してください。')
    }
    if (!nextCandidates) setNotice('参照候補を取得できませんでした。IDの直接入力は利用できます。接続を確認して再読込してください。')
  }, [invalidateReferences])
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
  const pruneCategoryFilters = useEffectEvent((directory: string) => {
    const hasFacets = Object.keys(facetFilters).length > 0
    const hasNumbers = Object.keys(numericFilters).length > 0
    if (!hasFacets && !hasNumbers) return
    const scopedFiles = searchScope === 'all' ? files : files.filter((file) => inCategory(file.path, directory))
    if (hasFacets) {
      const options = categoryFacetOptions(scopedFiles, filterIndex)
      setFacetFilters((current) => {
        const next = Object.fromEntries(Object.entries(current).filter(([key, value]) => options[key]?.includes(value)))
        return Object.keys(next).length === Object.keys(current).length ? current : next
      })
    }
    if (hasNumbers) {
      const options = categoryNumericOptions(scopedFiles, filterIndex)
      setNumericFilters((current) => {
        const next = Object.fromEntries(Object.entries(current).filter(([key]) => options[key]))
        return Object.keys(next).length === Object.keys(current).length ? current : next
      })
    }
  })
  useEffect(() => {
    const directory = catalog?.categories.find((entry) => entry.id === categoryId)?.directory
    if (!directory) return
    if (skillTag && filterIndex && !filterIndex.skillTags.some((entry) => (searchScope === 'all' || inCategory(entry.path, directory)) && entry.tags.includes(skillTag))) setSkillTag('')
    pruneCategoryFilters(directory)
    if (searchScope === 'folder' && !isDropSourceDirectory(directory)) {
      setDropItemId(''); setDropItemQuery(''); setDropPresence('')
    } else if (filterIndex && dropItemId && !filterIndex.sources.some((source) => (searchScope === 'all' || inCategory(source.path, directory)) && source.itemIds.includes(dropItemId))) {
      setDropItemId(''); setDropItemQuery('')
      setNotice('選択していたドロップ品が見つからないため、ドロップ品の絞り込みを解除しました。')
    }
  }, [catalog, categoryId, files, filterIndex, dropItemId, skillTag, searchScope])
  useEffect(() => { setVisibleCount(50) }, [categoryId, query, searchScope, format, dropSourcePath, skillTag, dropItemId, dropPresence, facetFilters, fileStatus, numericFilters])

  return {
    catalog, files, filesByPath, filterIndex, candidates, loading, refreshing, error, notice, setError, setNotice, refresh, rememberedPaths, referenceVersion,
    categoryId, setCategoryId, query, setQuery, searchScope, setSearchScope, browserView, setBrowserView, expandedCategories, setExpandedCategories, numericFilters, setNumericFilters, format, setFormat, sourceQuery, setSourceQuery,
    dropSourcePath, setDropSourcePath, skillTag, setSkillTag, visibleCount, setVisibleCount,
    dropItemQuery, setDropItemQuery, dropItemId, setDropItemId, dropPresence, setDropPresence,
    facetFilters, setFacetFilters, fileStatus, setFileStatus,
    sidebarCollapsed, setSidebarCollapsed,
  }
}

export type MasterDataWorkspace = ReturnType<typeof useMasterDataWorkspace>

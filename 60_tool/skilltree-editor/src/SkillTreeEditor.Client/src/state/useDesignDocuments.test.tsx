import { act, renderHook } from '@testing-library/react'
import { StrictMode, type ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/editorApi'
import { masterDataApi } from '../api/masterDataApi'
import { objectValue } from '../data/progressionDesign'
import type { JsonValue } from '../types/editor'
import type { MasterDocument } from '../types/masterData'
import { useDesignDocuments, type DesignSaveCandidate } from './useDesignDocuments'

const root = 'design-workspace'
const key = `astralrecord.progression-design.v1:${root}`
const shop = 'shops/merchant.yml'
const mob = 'mobs/skeleton.yml'
const savedDocument = (path: string, content: JsonValue = { amount: 1, chance: 10 }, revision = 'saved-1'): MasterDocument => ({
  path, content, revision, raw: JSON.stringify(content), format: 'yaml', issues: [],
})

function strictMode({ children }: { children: ReactNode }) {
  return <StrictMode>{children}</StrictMode>
}

describe('useDesignDocuments', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.spyOn(masterDataApi, 'file').mockImplementation(async path => savedDocument(path))
    vi.spyOn(masterDataApi, 'render').mockImplementation(async (_path, content) => ({
      raw: JSON.stringify(content), commentsPreserved: true, warnings: [],
    }))
    vi.spyOn(masterDataApi, 'validate').mockResolvedValue({ isValid: true, issues: [] })
    vi.spyOn(masterDataApi, 'save').mockImplementation(async (path, raw) => savedDocument(path, JSON.parse(raw), 'saved-2'))
  })

  afterEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it('shares a concurrent file load and applies both transforms to the newest draft', async () => {
    let completeLoad!: (value: MasterDocument) => void
    const load = new Promise<MasterDocument>(resolve => { completeLoad = resolve })
    vi.mocked(masterDataApi.file).mockReturnValueOnce(load)
    const { result } = renderHook(() => useDesignDocuments(root))

    await act(async () => {
      const first = result.current.edit(shop, content => ({ ...objectValue(content), amount: 3 }))
      const second = result.current.edit(shop, content => ({ ...objectValue(content), chance: 55 }))
      expect(masterDataApi.file).toHaveBeenCalledExactlyOnceWith(shop)
      completeLoad(savedDocument(shop))
      await expect(Promise.all([first, second])).resolves.toEqual([true, true])
    })

    expect(result.current.drafts[shop].content).toEqual({ amount: 3, chance: 55 })
    act(() => result.current.undo())
    expect(result.current.drafts[shop].content).toEqual({ amount: 3, chance: 10 })
    act(() => result.current.undo())
    expect(result.current.dirty).toBe(false)
    act(() => result.current.redo())
    act(() => result.current.redo())
    expect(result.current.drafts[shop].content).toEqual({ amount: 3, chance: 55 })
  })

  it('preserves drafts through render, validation, and revision conflict failures', async () => {
    const { result } = renderHook(() => useDesignDocuments(root))
    await act(async () => { await result.current.edit(shop, () => ({ amount: 3 })) })
    vi.mocked(masterDataApi.render).mockRejectedValueOnce(new Error('原稿変換に失敗'))
    await act(async () => { await expect(result.current.prepare()).resolves.toBeNull() })
    expect(result.current.error).toContain('原稿変換に失敗')
    expect(result.current.dirty).toBe(true)

    vi.mocked(masterDataApi.validate).mockResolvedValueOnce({
      isValid: false, issues: [{ code: 'range', severity: 'error', path: '/amount', message: '数量が範囲外です' }],
    })
    await act(async () => { await expect(result.current.prepare()).resolves.toBeNull() })
    expect(result.current.error).toContain('/amount 数量が範囲外です')
    expect(masterDataApi.save).not.toHaveBeenCalled()

    vi.mocked(masterDataApi.save).mockRejectedValueOnce(new ApiError('元の原稿が変更されています', 409))
    await act(async () => {
      const prepared = await result.current.prepare()
      expect(prepared).toHaveLength(1)
      await expect(result.current.save(prepared ?? [])).resolves.toBe(false)
    })
    expect(masterDataApi.save).toHaveBeenCalledExactlyOnceWith(shop, '{"amount":3}', 'saved-1')
    expect(result.current.error).toContain('元の原稿が変更されています')
    expect(result.current.drafts[shop].content).toEqual({ amount: 3 })
    expect(result.current.canUndo).toBe(true)
    expect(JSON.parse(sessionStorage.getItem(key) ?? '{}')[shop].content).toEqual({ amount: 3 })
  })

  it('rejects a confirmation when an edit changed after it was prepared', async () => {
    const { result } = renderHook(() => useDesignDocuments(root))
    let prepared: DesignSaveCandidate[] | null = null
    await act(async () => {
      await result.current.edit(shop, () => ({ amount: 3 }))
      prepared = await result.current.prepare()
      await result.current.edit(shop, () => ({ amount: 5 }))
    })
    await act(async () => { await expect(result.current.save(prepared ?? [])).resolves.toBe(false) })
    expect(result.current.error).toContain('保存確認後に編集内容が変わりました')
    expect(result.current.drafts[shop].content).toEqual({ amount: 5 })
    expect(masterDataApi.save).not.toHaveBeenCalled()
  })

  it('keeps only the unsaved remainder dirty and prevents undoing a completed write', async () => {
    const { result } = renderHook(() => useDesignDocuments(root))
    vi.mocked(masterDataApi.save)
      .mockResolvedValueOnce(savedDocument(shop, { amount: 3 }, 'saved-2'))
      .mockRejectedValueOnce(new ApiError('同時編集', 409))

    await act(async () => {
      await result.current.edit(shop, () => ({ amount: 3 }))
      await result.current.edit(mob, () => ({ chance: 20 }))
      const prepared = await result.current.prepare()
      expect(prepared?.map(candidate => candidate.path)).toEqual([shop, mob])
      await expect(result.current.save(prepared ?? [])).resolves.toBe(false)
    })

    expect(result.current.error).toContain('1件は保存済みです')
    expect(result.current.drafts[shop].document.revision).toBe('saved-2')
    expect(result.current.dirtyEntries.map(entry => entry.document.path)).toEqual([mob])
    expect(result.current.canUndo).toBe(false)
    act(() => result.current.undo())
    expect(result.current.drafts[shop].content).toEqual({ amount: 3 })
    expect(result.current.drafts[mob].content).toEqual({ chance: 20 })
    expect(Object.keys(JSON.parse(sessionStorage.getItem(key) ?? '{}'))).toEqual([mob])
  })

  it('retains pending recovery across mounts and restores the original revision with the draft', async () => {
    const recovery = { [shop]: { document: savedDocument(shop), content: { amount: 6 } } }
    sessionStorage.setItem(key, JSON.stringify(recovery))
    const first = renderHook(() => useDesignDocuments(root), { wrapper: strictMode })
    expect(first.result.current.recovery).toEqual(recovery)
    await act(async () => { await expect(first.result.current.edit(shop, () => ({ amount: 9 }))).resolves.toBe(false) })
    first.unmount()
    expect(JSON.parse(sessionStorage.getItem(key) ?? '{}')).toEqual(recovery)

    const remounted = renderHook(() => useDesignDocuments(root), { wrapper: strictMode })
    expect(remounted.result.current.recovery).toEqual(recovery)
    act(() => remounted.result.current.restore())
    expect(remounted.result.current.recovery).toBeNull()
    expect(remounted.result.current.dirty).toBe(true)
    expect(remounted.result.current.drafts[shop]).toEqual(recovery[shop])
    expect(masterDataApi.file).not.toHaveBeenCalled()
  })
})

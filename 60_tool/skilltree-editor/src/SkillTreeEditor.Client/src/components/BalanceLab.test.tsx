import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BalanceLab } from './BalanceLab'

const catalog = {
  masterHash: 'master-a', sourceHash: 'source-a', modelVersion: 'model-1', sources: [], diagnostics: [],
  supportedSkillIds: ['bolt', 'slash'],
  limits: { maxTrials: 500, maxSeconds: 600, maxBuilds: 4, maxTickWork: 2000000, wallSeconds: 30 },
  masters: [
    { path: '20.features.class/v1.mage.yml', kind: 'class', content: { id: 'mage', name: '&d魔法使い', baseStats: [], usableSkills: ['skill:bolt', 'skill:slash', 'skill:unsupported'] } },
    { path: '40.features.mob/v1.wolf.yml', kind: 'mob', content: { id: 'wolf', name: '狼' } },
    { path: '20.features.item/20.equipment/v1.sword.yml', kind: 'equipment', content: { id: '20a00001', name: '剣', equipment: {} } },
    { path: '10.features.item/30.material/v1.ore.yml', kind: 'item', content: { id: 'ore', name: '鉱石' } },
    { path: '30.features.skill/v1.bolt.yml', kind: 'skill', content: { id: 'bolt', name: '雷撃' } },
    { path: '30.features.skill/v1.slash.yml', kind: 'skill', content: { id: 'slash', name: '斬撃' } },
    { path: '30.features.skill/v1.unsupported.yml', kind: 'skill', content: { id: 'unsupported', name: '未対応魔法' } },
    { path: '30.features.skill/v1.other.yml', kind: 'skill', content: { id: 'other', name: '他職スキル' } },
  ],
}

beforeEach(() => { localStorage.clear(); sessionStorage.clear() })
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear() })

describe('BalanceLab', () => {
  it('filters skill candidates using catalog metadata and preserves selected priority order', async () => {
    let submitted: { builds: { skillIds: string[] }[] } | undefined
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/catalog')) return { ok: true, json: async () => catalog }
      if (init?.method === 'POST') {
        submitted = JSON.parse(String(init.body))
        return { ok: true, json: async () => ({ id: 'skills', status: 'completed', completed: 1, total: 1 }) }
      }
      throw new Error(`unexpected ${url}`)
    }))
    render(<BalanceLab />)
    await screen.findByText(/マスター指紋/)
    expect(screen.getByLabelText(/雷撃/)).toBeInTheDocument()
    expect(screen.getByLabelText(/斬撃/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/未対応魔法/)).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('searchbox', { name: 'スキル検索' }), { target: { value: '雷撃' } })
    expect(screen.getByLabelText(/雷撃/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/斬撃/)).not.toBeInTheDocument()
    fireEvent.change(screen.getByRole('searchbox', { name: 'スキル検索' }), { target: { value: '' } })
    fireEvent.click(screen.getByLabelText(/雷撃/))
    fireEvent.click(screen.getByLabelText(/斬撃/))
    fireEvent.click(screen.getByRole('button', { name: 'slashの優先順位を上げる' }))
    fireEvent.click(screen.getByRole('button', { name: 'この条件で実行' }))
    await waitFor(() => expect(submitted?.builds[0].skillIds).toEqual(['slash', 'bolt']))
  })

  it('shows unsupported and other-class skills only after relaxing candidate filters', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => catalog }))
    render(<BalanceLab />)
    await screen.findByText(/マスター指紋/)
    fireEvent.click(screen.getByLabelText('計算対応スキルのみ'))
    expect(screen.getByLabelText(/未対応魔法/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/他職スキル/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('職業の使用候補のみ'))
    expect(screen.getByLabelText(/他職スキル/)).toBeInTheDocument()
  })

  it('keeps the sell-item list compact and searchable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => catalog }))
    render(<BalanceLab />)
    await screen.findByText(/マスター指紋/)
    fireEvent.click(screen.getByLabelText('収集・強化・成長・金策も計算する'))
    const summary = screen.getByText('売却アイテム（0件選択）')
    expect(summary.closest('details')).not.toHaveAttribute('open')
    fireEvent.click(summary)
    fireEvent.change(screen.getByRole('searchbox', { name: '売却アイテム検索' }), { target: { value: 'ore' } })
    expect(screen.getByLabelText(/鉱石/)).toBeInTheDocument()
  })

  it('blocks execution and saving while detailed JSON is invalid', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => catalog })
    vi.stubGlobal('fetch', fetchMock)
    render(<BalanceLab />)
    await screen.findByText(/マスター指紋/)
    const editor = screen.getByRole('textbox', { name: 'ビルド1の詳細JSON' })
    fireEvent.change(editor, { target: { value: '{broken' } })
    fireEvent.blur(editor)
    expect(screen.getByRole('button', { name: 'この条件で実行' })).toBeDisabled()
    expect(screen.getByLabelText('名前')).toBeDisabled()
    expect(screen.getByRole('button', { name: '装備を追加' })).toBeDisabled()
    expect(editor).toHaveValue('{broken')
    expect(screen.getByRole('button', { name: 'ブラウザに保存' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '条件JSON出力' })).toBeDisabled()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fireEvent.change(editor, { target: { value: JSON.stringify({ id: 'build-1', name: '修正案', classId: 'mage', playerLevel: 2, classLevel: 1, equipment: [], skillIds: [] }) } })
    fireEvent.blur(editor)
    expect(screen.getByRole('button', { name: 'この条件で実行' })).toBeEnabled()
    expect(screen.getByLabelText('名前')).toHaveValue('修正案')
  })

  it('preserves a restored job across a transient network failure', async () => {
    sessionStorage.setItem('astralrecord.balance-lab.active-job.v1', 'recover')
    let polls = 0
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      if (url.endsWith('/catalog')) return { ok: true, json: async () => catalog }
      if (++polls === 1) throw new Error('network')
      return { ok: true, json: async () => ({ id: 'recover', status: 'completed', completed: 1, total: 1 }) }
    }))
    render(<BalanceLab />)
    expect(await screen.findByText('完了 · 1/1', {}, { timeout: 3000 })).toBeInTheDocument()
    expect(sessionStorage.getItem('astralrecord.balance-lab.active-job.v1')).toBe('recover')
  })

  it('submits multiple builds and isolated master overrides, then shows trial outcomes', async () => {
    const calls: { url: string; init?: RequestInit }[] = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      calls.push({ url, init })
      if (url.endsWith('/catalog')) return { ok: true, json: async () => catalog }
      if (url.endsWith('/jobs') && init?.method === 'POST') {
        const scenario = JSON.parse(String(init.body))
        return { ok: true, json: async () => ({ id: 'job-1', status: 'completed', completed: 2, total: 2, result: {
          modelVersion: 'model-1', masterHash: 'master-a', seed: scenario.seed, scenario, sources: [], assumptions: ['第1試行のリソースを保存'], warnings: [],
          results: [{ buildId: scenario.builds[0].id, buildName: 'ビルド1', variant: 'current', status: 'completed', diagnostics: [],
            trials: [{ index: 0, seed: 1, outcome: 'death', elapsedTicks: 100, damageDealt: 50, damageTaken: 60, dps: 10, resources: [{ tick: 0, hp: 100, mp: 20, eng: 10, shield: 0 }] }],
            summary: { totalTrials: 2, victories: 1, deaths: 1, timeouts: 0, invalid: 0, unsupported: 0, meanDps: 12, medianDps: 12, stdDevDps: 2, meanElapsedSeconds: 5, medianElapsedSeconds: 5, p90ElapsedSeconds: 6, successRate: 0.5 },
            economy: { status: 'completed', diagnostics: [{ code: 'ORB_SUPPLY_SEPARATE', message: 'オーブは別途調達', severity: 'warning' }], collection: { itemId: 'sword', quantity: 1, expectedItemsPerKill: 0.038416, expectedKills: 26.03, medianKills: 18, p90Kills: 59, method: 'exact' }, enhancement: { itemId: 'sword', orbItemId: 'sword', estimatedKillsForExpectedOrbs: 26.03 }, time: { combatSeconds: 100, moveSeconds: 10, waitSeconds: 0, resupplySeconds: 0, totalSeconds: 110 } },
          }], changes: [], comparison: [],
        } }) }
      }
      throw new Error(`unexpected ${url}`)
    }))
    render(<BalanceLab />)
    await screen.findByText(/マスター指紋/)
    fireEvent.click(screen.getByRole('button', { name: 'ビルドを追加' }))
    fireEvent.change(screen.getByLabelText('調整するマスター'), { target: { value: catalog.masters[1].path } })
    fireEvent.change(screen.getByRole('textbox', { name: '調整案JSON' }), { target: { value: '{"id":"wolf","level":2}' } })
    fireEvent.click(screen.getByRole('button', { name: '調整案に追加' }))
    fireEvent.click(screen.getByRole('button', { name: 'この条件で実行' }))
    expect(await screen.findByText('討伐率')).toBeInTheDocument()
    const sent = JSON.parse(String(calls.find(call => call.init?.method === 'POST')?.init?.body))
    expect(sent.builds).toHaveLength(2)
    expect(sent.overrides).toEqual([{ path: catalog.masters[1].path, content: { id: 'wolf', level: 2 } }])
    expect(sent.masterHash).toBe('master-a')
    expect(calls.map(call => call.url)).toEqual(['/api/balance-lab/catalog', '/api/balance-lab/jobs'])
    expect(screen.getByText(/死亡 1/)).toBeInTheDocument()
    expect(screen.getByText('50%')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /HP、MP、ENG/ })).toBeInTheDocument()
    fireEvent.click(screen.getByText(/収集・強化・成長・金策・所要時間/))
    expect(screen.getByText('26.03戦')).toBeInTheDocument()
    expect(screen.getByText(/ORB_SUPPLY_SEPARATE/)).toBeInTheDocument()
    expect(screen.getByText(/収集目標の時間に含まれます/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('乱数seed'), { target: { value: '42' } })
    expect(screen.getByText(/入力条件が結果の実行時から変わっています/)).toBeInTheDocument()
  })

  it('warns when a saved scenario targets another master and model', async () => {
    localStorage.setItem('astralrecord.balance-lab.scenario.v1', JSON.stringify({ schemaVersion: 1, masterHash: 'older', modelVersion: 'old-model', seed: 1, trials: 1, maxSeconds: 60, mode: 'strict', builds: [{ id: 'b', name: 'B', classId: 'mage', playerLevel: 1, classLevel: 1, equipment: [], skillIds: [] }], enemy: { mobId: 'wolf', level: 1 }, overrides: [] }))
    const fetchMock = vi.fn().mockImplementation(async (_url: string, init?: RequestInit) => init?.method === 'POST'
      ? { ok: false, status: 409, json: async () => ({ title: 'マスター指紋が変わりました' }) }
      : { ok: true, json: async () => catalog })
    vi.stubGlobal('fetch', fetchMock)
    render(<BalanceLab />)
    expect(await screen.findByRole('alert')).toHaveTextContent('マスター、ソース、または計算モデルが変わっています')
    expect(screen.getByRole('button', { name: 'この条件で実行' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'この条件で実行' }))
    expect(await screen.findByText(/マスター指紋が変わりました/)).toBeInTheDocument()
    expect(JSON.parse(String(fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')?.[1]?.body)).masterHash).toBe('older')
  })

  it('rejects malformed override JSON before it enters the scenario', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => catalog }))
    render(<BalanceLab />)
    await screen.findByText(/マスター指紋/)
    fireEvent.change(screen.getByLabelText('調整するマスター'), { target: { value: catalog.masters[1].path } })
    fireEvent.change(screen.getByRole('textbox', { name: '調整案JSON' }), { target: { value: '{broken' } })
    fireEvent.click(screen.getByRole('button', { name: '調整案に追加' }))
    expect(screen.getByText(/SyntaxError/)).toBeInTheDocument()
    expect(screen.getByText('0件のマスターを調整')).toBeInTheDocument()
  })

  it('cancels a running server job', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/catalog')) return { ok: true, json: async () => catalog }
      if (init?.method === 'POST') return { ok: true, json: async () => ({ id: 'job-2', status: 'running', completed: 1, total: 20 }) }
      if (init?.method === 'DELETE') return { ok: true, json: async () => ({ id: 'job-2', status: 'cancelled', completed: 1, total: 20 }) }
      return { ok: true, json: async () => ({ id: 'job-2', status: 'running', completed: 1, total: 20 }) }
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<BalanceLab />)
    await screen.findByText(/マスター指紋/)
    fireEvent.click(screen.getByRole('button', { name: 'この条件で実行' }))
    const cancel = await screen.findByRole('button', { name: '実行をキャンセル' })
    expect(screen.getByRole('button', { name: '条件JSON読込' })).toBeDisabled()
    fireEvent.click(cancel)
    expect(await screen.findByText('キャンセル · 1/20')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/balance-lab/jobs/job-2', { method: 'DELETE' })
  })

  it('keeps a pending job restoration from being overwritten by a new run', async () => {
    sessionStorage.setItem('astralrecord.balance-lab.active-job.v1', 'restored')
    let finish!: (value: unknown) => void
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      if (url.endsWith('/catalog')) return { ok: true, json: async () => catalog }
      return await new Promise(resolve => { finish = resolve })
    }))
    render(<BalanceLab />)
    await screen.findByText(/マスター指紋/)
    expect(screen.getByRole('button', { name: '実行中…' })).toBeDisabled()
    finish({ ok: true, json: async () => ({ id: 'restored', status: 'completed', completed: 1, total: 1 }) })
    await waitFor(() => expect(screen.getByRole('button', { name: 'この条件で実行' })).toBeEnabled())
  })

  it('retries a transient progress request failure and reaches completion', async () => {
    let polls = 0
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/catalog')) return { ok: true, json: async () => catalog }
      if (init?.method === 'POST') return { ok: true, json: async () => ({ id: 'retry', status: 'running', completed: 0, total: 1 }) }
      if (++polls === 1) throw new Error('temporary disconnect')
      return { ok: true, json: async () => ({ id: 'retry', status: 'completed', completed: 1, total: 1 }) }
    }))
    render(<BalanceLab />)
    await screen.findByText(/マスター指紋/)
    fireEvent.click(screen.getByRole('button', { name: 'この条件で実行' }))
    expect(await screen.findByText(/進捗取得に失敗しました/)).toBeInTheDocument()
    expect(await screen.findByText('完了 · 1/1', {}, { timeout: 4000 })).toBeInTheDocument()
    expect(polls).toBe(2)
  })
})

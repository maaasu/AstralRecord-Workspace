import { describe, expect, it } from 'vitest'
import { classifyMasters, resultCsv, summaryCsv, validateScenario } from './balanceLab'

describe('balance lab data', () => {
  it('uses typed master identities instead of skill or class substrings in file names', () => {
    const groups = classifyMasters({ masterHash: 'h', modelVersion: '1', sources: [], diagnostics: [], masters: [
      { kind: 'item', path: '10.material/skill_gem.yml', content: { id: 'gem' } },
      { kind: 'class', path: 'class/class_progress.yml', content: { id: 'class_progress' } },
      { kind: 'class', path: 'class/mage.yml', content: { id: 'mage', baseStats: [] } },
      { kind: 'skill', path: 'skill/bolt.yml', content: { id: 'bolt' } },
    ] })
    expect(groups.skills.map(master => master.content.id)).toEqual(['bolt'])
    expect(groups.classes.map(master => master.content.id)).toEqual(['mage'])
  })

  it('preserves experiment identity and neutralizes textual spreadsheet formulas', () => {
    const result = { modelVersion: 'model-v1', masterHash: 'master-hash', sourceHash: 'source-hash', seed: 7, scenario: { schemaVersion: 1 as const, seed: 7, trials: 1, maxSeconds: 60, mode: 'strict' as const, builds: [], enemy: { mobId: 'wolf', level: 1 }, overrides: [] }, results: [{ buildId: 'a', buildName: '=1+1', variant: 'current' as const, status: 'invalid' }] }
    const csv = summaryCsv(result)
    expect(csv).toContain("'\u003d1+1")
    expect(csv).toContain('model-v1')
    expect(csv).toContain('source-hash')
    expect(csv).toContain('schemaVersion')
  })
  it('rejects out-of-range trial count before submission', () => {
    const scenario = { schemaVersion: 1, seed: 2, trials: 501, maxSeconds: 60, mode: 'strict', builds: [{ id: 'a', name: 'A', classId: 'mage', playerLevel: 1, classLevel: 1, equipment: [], skillIds: [] }], enemy: { mobId: 'wolf', level: 1 }, overrides: [] }
    expect(() => validateScenario(scenario)).toThrow('試行回数')
  })

  it('exports each outcome with its own seed and elapsed time', () => {
    const csv = resultCsv({ modelVersion: '1', masterHash: 'a', seed: 1, scenario: { schemaVersion: 1, seed: 1, trials: 2, maxSeconds: 60, mode: 'strict', builds: [], enemy: { mobId: 'wolf', level: 1 }, overrides: [] }, results: [{ buildId: 'a', buildName: 'A', variant: 'current', status: 'completed', trials: [
      { index: 0, seed: 1, outcome: 'victory', elapsedTicks: 40, damageDealt: 10, damageTaken: 1, dps: 5 },
      { index: 1, seed: 2, outcome: 'timeout', elapsedTicks: 1200, damageDealt: 50, damageTaken: 40, dps: 0.83 },
    ] }] })
    expect(csv).toContain('"victory","2"')
    expect(csv).toContain('"timeout","60"')
    expect(csv.split('\r\n')).toHaveLength(4)
  })
})

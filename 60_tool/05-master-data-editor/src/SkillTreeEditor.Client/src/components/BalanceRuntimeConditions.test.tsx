import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { conditionKey, createScenario, validateScenario, validateRuntimeData } from '../data/balanceLab'
import { BalanceRuntimeConditions } from './BalanceRuntimeConditions'
import { BalanceRuntimeTrace } from './BalanceRuntimeTrace'

describe('runtime conditions', () => {
  it('adds explicit sneak input without changing the build', () => {
    const scenario = createScenario(); const onChange = vi.fn()
    render(<BalanceRuntimeConditions scenario={scenario} disabled={false} onChange={onChange} onDraft={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '入力例：しゃがみ維持' }))
    expect(onChange.mock.calls[0][0].inputs[0]).toEqual({ tick: 0, sneaking: true, onGround: true })
    expect(onChange.mock.calls[0][0].builds).toBe(scenario.builds)
  })
  it('keeps malformed input as a draft and rejects unknown root fields', () => {
    const onChange = vi.fn(); const onDraft = vi.fn()
    render(<BalanceRuntimeConditions scenario={createScenario()} disabled={false} onChange={onChange} onDraft={onDraft} />)
    const editor = screen.getByLabelText('位置と操作入力JSON')
    fireEvent.change(editor, { target: { value: '{"world":{},"inputs":[],"allies":[],"foo":true}' } }); fireEvent.blur(editor)
    expect(screen.getByRole('alert')).toHaveTextContent('world・inputs・allies以外')
    expect(onDraft).toHaveBeenLastCalledWith(true); expect(onChange).not.toHaveBeenCalled()
    fireEvent.change(editor, { target: { value: '{"world":{"floorY":0},"inputs":[{"tick":0,"sneaking":true}],"allies":[]}' } }); fireEvent.blur(editor)
    expect(onChange.mock.calls[0][0].inputs).toEqual([{ tick: 0, sneaking: true }]); expect(onDraft).toHaveBeenLastCalledWith(false)
  })
  it('includes positions and controls in stale-result detection', () => {
    const scenario = createScenario()
    expect(conditionKey({ ...scenario, inputs: [{ tick: 0, sneaking: true }] })).not.toBe(conditionKey(scenario))
    expect(conditionKey({ ...scenario, world: { facing: [0, 0, -1] } })).not.toBe(conditionKey(scenario))
  })
  it('validates saved passive selections instead of dropping them', () => {
    const scenario = createScenario(); scenario.builds[0].classId = 'adventurer'; scenario.enemy.mobId = 'boar'
    scenario.builds[0].passiveSkillIds = ['paladin_guard_convert']
    expect(validateScenario(JSON.parse(JSON.stringify(scenario))).builds[0].passiveSkillIds).toEqual(['paladin_guard_convert'])
    expect(() => validateScenario({ ...scenario, builds: [{ ...scenario.builds[0], passiveSkillIds: [3] }] })).toThrow()
  })
  it('rejects null or malformed party rows before the page renders them', () => {
    expect(() => validateRuntimeData({ allies: [null] })).toThrow('各味方')
    expect(() => validateRuntimeData({ allies: [{ id: 'a', position: [0, 0, 1], stats: { MAX_HEALTH: 100 }, hp: -1 }] })).toThrow()
  })
  it('renders dedicated resources with their own scale and tick records', () => {
    render(<BalanceRuntimeTrace trial={{ index: 0, seed: 1, outcome: 'timeout', elapsedTicks: 20, damageDealt: 0, damageTaken: 0, dps: 0,
      resources: [{ tick: 0, hp: 100, mp: 30, eng: 50, shield: 0, custom: { GUARD: 0, RIPOSTE: 0 } }, { tick: 20, hp: 100, mp: 30, eng: 50, shield: 0, custom: { GUARD: 10, RIPOSTE: 1 } }] }} />)
    expect(screen.getByRole('img', { name: 'GUARDの推移' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: '剣気の推移' })).toBeInTheDocument()
    expect(screen.getByRole('table')).toHaveTextContent('10')
  })
})

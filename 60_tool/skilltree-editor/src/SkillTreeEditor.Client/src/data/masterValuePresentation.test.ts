import { describe, expect, it } from 'vitest'
import { masterFieldLabel, masterFieldTitle } from './masterDataPresentation'
import { masterValueLabel, masterValueReferenceKind } from './masterValuePresentation'
import { STATUS_TYPES } from './statusTypes.generated'
import { MASTER_TAGS } from './masterTags.generated'

describe('master catalog value labels', () => {
  it('uses every generated status and tag name while retaining the exact ID', () => {
    for (const status of STATUS_TYPES) {
      for (const key of ['status', 'statusID', 'statusId', '/baseStats/0/status']) {
        expect(masterValueLabel(key, status.id)).toBe(`${status.displayName}（${status.id}）`)
      }
      expect(masterFieldLabel(status.id)).toBe(`${status.displayName}（${status.id}）`)
      expect(masterFieldTitle(status.id, status.id)).toBe(`${status.displayName}（${status.id}）`)
    }
    for (const tag of MASTER_TAGS) expect(masterValueLabel('/tags/0', tag.id)).toBe(`${tag.displayName}（${tag.id}）`)
    expect(masterValueReferenceKind('/tags/2')).toBe('tag')
  })

  it('only translates known values in their field context and leaves unknown IDs and RPC strings intact', () => {
    expect(masterValueLabel('pointType', 'CP')).toBe('クラスポイント（CP）')
    expect(masterValueLabel('modifierType', 'SCALAR')).toBe('割合補正（SCALAR）')
    expect(masterValueLabel('status', 'UNKNOWN_STATUS')).toBe('UNKNOWN_STATUS')
    expect(masterValueLabel('tags', 'unknown_tag')).toBe('unknown_tag')
    expect(masterValueLabel('/params/Rpc', 'MAX_HEALTH')).toBe('MAX_HEALTH')
    expect(masterValueLabel('toString', 'name')).toBe('name')
    expect(masterValueLabel('type', 'constructor')).toBe('constructor')
    expect(masterFieldTitle('MAX_HEALTH', '任意の名前')).toBe('任意の名前')
  })

  it.each([
    ['category', 'EQUIPMENT', '装備'], ['category', 'pet_egg', 'ペットの卵'],
    ['rarity', 'COMMON', 'コモン'], ['rarity', 'mythic', 'ミシック'],
    ['slot', 'HEAD', '頭'], ['slot', 'SUBWEAPON', '補助装備'],
    ['handType', 'ONE', '片手'], ['handType', 'two', '両手'],
    ['equipmentTag', 'SWORD', '剣'], ['equipmentTag', 'FISHING_ROD', '釣り竿'],
  ])('uses the existing item enum/catalog name for %s=%s and preserves its spelling', (key, id, name) => {
    expect(masterValueLabel(key, id)).toBe(`${name}（${id}）`)
    expect(masterValueLabel(key, 'UNKNOWN_FUTURE_ID')).toBe('UNKNOWN_FUTURE_ID')
  })
})

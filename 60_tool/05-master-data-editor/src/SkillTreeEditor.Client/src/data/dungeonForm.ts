import type { JsonObject, JsonValue } from '../types/editor'

export const isDungeonMaster = (path: string | undefined) => path?.startsWith('65.features.dungeon/') === true

interface DungeonFormField {
  label: string
  defaultValue?: JsonValue
  type?: 'integer' | 'number'
}

const field = (label: string, defaultValue?: JsonValue, type?: DungeonFormField['type']): DungeonFormField => ({ label, defaultValue, type })
const integer = (label: string, value: number | null) => field(label, value, 'integer')
const number = (label: string, value: number) => field(label, value, 'number')
const material = (id: string) => [{ material: id, weight: 1 }]

// Mirror the effective defaults in docs.dungeon and DungeonDefinitionRepository.
// These values are presented in the form and only materialized when edited.
const sections: Readonly<Record<string, Readonly<Record<string, DungeonFormField>>>> = {
  '': {
    schemaVersion: field('スキーマバージョン'), id: field('ID'), displayName: field('表示名'), recommendedLevel: field('推奨プレイヤーレベル'),
    entry: field('入場地点', {}), party: field('パーティー', {}), challenge: field('挑戦ルール', {}),
    encounter: field('モブの出現設定', {}), clearRewards: field('クリア報酬', {}),
    generation: field('生成設定', {}), theme: field('外観テーマ', {}),
  },
  '/entry': {
    worldRef: field('ワールド', ''), x: field('X座標'), y: field('Y座標'), z: field('Z座標'),
    yaw: number('水平角度（Yaw）', 0), pitch: number('垂直角度（Pitch）', 0), radius: number('受付半径', 2),
  },
  '/party': { min: integer('最少人数', 1), max: integer('最大人数', 4) },
  '/challenge': { deathLimit: integer('死亡回数の上限', 5), reviveDelaySeconds: integer('復活までの待機時間（秒）', 5), timeLimitSeconds: integer('制限時間（秒）', 600) },
  '/encounter': {
    bossMobId: field('ボスモブ', ''), bossMobLevel: integer('ボスのレベル', null), mobsPerRoom: field('部屋ごとの出現数', {}),
    firstCombatRoomMaxMobLevel: integer('最初の戦闘部屋のレベル上限', 10), ensureMobVariety: field('モブ候補を各1体以上出現させる', false),
    normalMobPool: field('通常モブ', []),
  },
  '/encounter/mobsPerRoom': { min: integer('最少数', 2), max: integer('最大数', 4) },
  '/clearRewards': { lootTable: field('追加報酬テーブル', ''), items: field('報酬アイテム', []) },
  '/generation': {
    area: field('生成範囲', {}), baseY: integer('基準Y座標', 64), roomCount: field('部屋数', {}), roomSize: field('通常部屋の大きさ', {}),
    bossRoomSize: field('ボス部屋の大きさ', {}), roomHeight: integer('部屋の高さ', 8), corridorWidth: integer('通路の幅', 3),
    corridorHeight: integer('通路の高さ', 4), splitRatio: field('区画の分割比率', {}),
    roomShapes: field('部屋の形状', [{ type: 'RECTANGLE', weight: 3 }, { type: 'CYLINDER', weight: 1 }]),
    roomTypes: field('部屋の種類', [{ type: 'STANDARD', weight: 1 }]),
  },
  '/generation/area': { width: integer('幅', 128), depth: integer('奥行き', 128) },
  '/generation/roomCount': { min: integer('最少数', 7), max: integer('最大数', 11) },
  '/generation/roomSize': { min: integer('最小辺長', 11), max: integer('最大辺長', 23) },
  '/generation/bossRoomSize': { min: field('最小辺長', undefined, 'integer'), max: field('最大辺長', undefined, 'integer') },
  '/generation/splitRatio': { min: number('下限', 0.35), max: number('上限', 0.50) },
  '/theme': {
    landscape: field('地形の生成方式', 'INDOOR'), gateMaterial: field('門の素材', 'IRON_BARS'), lightMaterial: field('照明の素材', 'TORCH'),
    floor: field('床の素材', material('STONE_BRICKS')), wall: field('壁の素材', material('STONE_BRICKS')),
    ceiling: field('天井の素材', material('STONE_BRICKS')), corridor: field('通路の素材', material('COBBLESTONE')),
    pillar: field('柱', {}), decorations: field('装飾', {}),
  },
  '/theme/pillar': {
    enabled: field('柱を生成する', false), chance: number('柱の出現率（0〜1）', 0.35),
    material: field('柱の素材', 'CHISELED_STONE_BRICKS'), stairMaterial: field('柱の階段素材', 'STONE_BRICK_STAIRS'),
  },
  '/theme/decorations': {
    supportMaterial: field('外周の支柱', 'OAK_LOG'), beamMaterial: field('天井の梁', 'OAK_PLANKS'),
    rubble: field('床の瓦礫', material('COBBLESTONE')), accent: field('外周壁の鉱石装飾', material('COAL_ORE')),
  },
}

export const dungeonSectionPaths = Object.entries(sections['']).filter(([, value]) => value.defaultValue !== undefined).map(([key]) => '/' + key)
export const dungeonSection = (path: string) => Object.hasOwn(sections, path) ? sections[path] : undefined

export const dungeonRoomChoices: Readonly<Record<string, Readonly<Record<string, string>>>> = {
  '/generation/roomShapes': { RECTANGLE: '長方形', CYLINDER: '円形' },
  '/generation/roomTypes': { STANDARD: '標準', SUPPORT_HALL: '支柱の広間', COLLAPSED: '崩落した部屋', ORE_CHAMBER: '鉱石の部屋' },
  '/theme/landscape': { INDOOR: '屋内', PLAINS: '平原' },
}

export const isDungeonMaterialList = (path: string) => /^\/theme\/(?:floor|wall|ceiling|corridor|decorations\/(?:rubble|accent))$/.test(path)
export const isDungeonWeightedList = (path: string) => isDungeonMaterialList(path) || /^\/generation\/room(?:Shapes|Types)$/.test(path)
export const isDungeonWeightedRow = (path: string) => /\/\d+$/.test(path) && isDungeonWeightedList(path.replace(/\/\d+$/, ''))

export function dungeonFieldKeys(path: string, value: JsonObject) {
  const fields = dungeonSection(path)
  const keys = Object.keys(value)
  if (fields) return [...new Set([...Object.keys(fields), ...keys])]
  return isDungeonWeightedRow(path) && !Object.hasOwn(value, 'weight') ? [...keys, 'weight'] : keys
}

export function dungeonFieldDefault(path: string, key: string, roomSize: JsonValue | undefined): JsonValue | undefined {
  if (path === '/generation/bossRoomSize' && (key === 'min' || key === 'max')) {
    const configured = roomSize && typeof roomSize === 'object' && !Array.isArray(roomSize) ? roomSize[key] : undefined
    return typeof configured === 'number' ? configured : key === 'min' ? 11 : 23
  }
  if (key === 'weight' && isDungeonWeightedRow(path)) return 1
  const fields = dungeonSection(path)
  return fields && Object.hasOwn(fields, key) ? fields[key].defaultValue : undefined
}

export function dungeonFieldLabel(path: string, key: string): string | undefined {
  const fields = dungeonSection(path)
  if (fields && Object.hasOwn(fields, key)) return fields[key].label
  if (!isDungeonWeightedRow(path)) return undefined
  if (key === 'weight') return '抽選重み'
  if (key === 'material') return '素材'
  if (key === 'type') return '種類'
}

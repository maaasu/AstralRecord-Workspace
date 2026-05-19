# Equipment Loadout API

装備プリセットと、プリセット内の装備スロットを扱う API です。
装備品の個体情報は `equipmentInstanceId` で `equipment_instance` を参照します。

## エンドポイント一覧

| Method | Path | 説明 |
|---|---|---|
| GET | `/api/equipment/loadouts?account_id={accountId}&loadout_profile={profile}` | アカウントの装備プリセット一覧を取得 |
| GET | `/api/equipment/loadouts/{loadoutId}` | 装備プリセットを取得 |
| POST | `/api/equipment/loadouts` | 装備プリセットを作成 |
| PUT | `/api/equipment/loadouts/{loadoutId}` | 装備プリセットを更新 |
| DELETE | `/api/equipment/loadouts/{loadoutId}?updated_by={updatedBy}` | 装備プリセットを削除 |
| POST | `/api/equipment/loadouts/{loadoutId}/activate` | 装備プリセットを有効化 |
| GET | `/api/equipment/loadouts/{loadoutId}/slots` | 装備プリセットのスロット一覧を取得 |
| PUT | `/api/equipment/loadouts/{loadoutId}/slots` | 装備スロットを登録または更新 |
| DELETE | `/api/equipment/loadouts/{loadoutId}/slots/{slotType}/{slotIndex}?updated_by={updatedBy}` | 装備スロットを解除 |

## モデル概要

### equipment loadout

- `equipmentLoadoutId`
- `accountId`
- `loadoutProfile` (`GAME` / `BUILDER`)
- `loadoutName`
- `sortOrder`
- `isActive`
- `metadataJson`
- `slots`

### equipment loadout slot

- `equipmentLoadoutSlotId`
- `equipmentLoadoutId`
- `slotType` (`WEAPON`, `SUBWEAPON`, `HEAD`, `CHEST`, `LEGS`, `FEET`, `ACCESSORY`, `TOOL` など)
- `slotIndex`
- `equipmentInstanceId`

## ペイロードルール

- 同一アカウントの `equipmentInstanceId` のみ登録できます
- 同一プリセット内では同じ `slotType` + `slotIndex` を重複登録できません
- 同一プリセット内では同じ `equipmentInstanceId` を複数スロットへ登録できません
- 同じ `equipmentInstanceId` を複数のプリセットへ登録することは許可します
- `activate` は同一 `accountId` + `loadoutProfile` の他プリセットを無効化します

## 備考

- 現在装備中の状態は `isActive = true` のプリセットとして扱います
- 所持品としての存在は `inventory` / `inventory_entry` 側で管理します

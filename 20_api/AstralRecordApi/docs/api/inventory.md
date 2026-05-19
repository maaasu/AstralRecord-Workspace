# Inventory API

`inventory` / `inventory_entry` を扱う API です。
インベントリの用途分離は `inventoryProfile`（`GAME` / `BUILDER`）で管理します。

## エンドポイント一覧

| Method | Path | 説明 |
|---|---|---|
| GET | `/api/inventory?account_id={accountId}` | アカウントが持つインベントリ一覧を取得 |
| GET | `/api/inventory/{inventoryId}` | インベントリ本体を取得 |
| POST | `/api/inventory` | インベントリ本体を作成 |
| PUT | `/api/inventory/{inventoryId}` | インベントリ本体を更新 |
| GET | `/api/inventory/{inventoryId}/entries` | 指定インベントリのエントリ一覧を取得 |
| GET | `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリを取得 |
| POST | `/api/inventory/{inventoryId}/entries` | インベントリエントリを作成 |
| PUT | `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリを更新 |
| DELETE | `/api/inventory/entries/{inventoryEntryId}?updated_by={updatedBy}` | インベントリエントリを論理削除 |

## モデル概要

### inventory

- `inventoryId`
- `accountId`
- `inventoryType`
- `inventoryProfile` (`GAME` / `BUILDER`)
- `slotCapacity`
- `isEnabled`
- `metadataJson`

### inventory entry

- `inventoryEntryId`
- `inventoryId`
- `slotIndex`
- `itemCategory`
- `itemId`
- `instanceType`
- `instanceId`
- `quantity`
- `metadataJson`

## ペイロードルール

- スタックアイテムは `itemId` を使用する
- インスタンス系アイテムは `instanceType` と `instanceId` を使用する
- `itemId` と `instanceType` / `instanceId` は同時使用しない
- `quantity` は `1` 以上

## 備考

- `inventoryType` の妥当性は API 単体ではマスタ参照しないため、呼び出し元の定義と合わせて扱います
- バニラ `ItemStack` の詳細は `inventory_entry.metadataJson` に保存する運用を想定します

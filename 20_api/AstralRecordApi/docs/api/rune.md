# ルーンインスタンス API

## エンドポイント一覧

| メソッド | パス | 概要 |
|---|---|---|
| POST | /api/rune/instances | ルーンインスタンス作成 |
| GET | /api/rune/instances/{instanceId} | ルーンインスタンス取得 |

## POST /api/rune/instances

YAML の rune 定義からルーン個体を生成し、ランダム値を持つ stat を保存します。

リクエスト例:

```json
{
  "runeId": "rune_attack_small",
  "accountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "source": "loot_drop",
  "createdBy": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

レスポンス例:

```json
{
  "runeInstanceId": "11111111-2222-3333-4444-555555555555",
  "accountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "itemId": "rune_attack_small",
  "createdAt": "2026-04-19T12:00:00Z",
  "updatedAt": "2026-04-19T12:00:00Z",
  "statRolls": [
    {
      "statRollId": "66666666-7777-8888-9999-aaaaaaaaaaaa",
      "status": "ATTACK",
      "type": "FLAT",
      "value": "7",
      "sortOrder": 0
    }
  ]
}
```

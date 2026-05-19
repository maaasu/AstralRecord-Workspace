# アカウント API

## エンドポイント一覧

| メソッド | パス | 概要 |
|---|---|---|
| GET | `/api/account/{uuid}` | アカウント情報取得 |
| POST | `/api/account` | アカウント登録 |
| PUT | `/api/account/{uuid}` | アカウント情報更新 |

## 認証

すべてのリクエストに `X-Api-Key` ヘッダーが必要です。

| ヘッダー | 型 | 必須 | 説明 |
|---|---|---|---|
| `X-Api-Key` | string | ✓ | API キー |

---

## GET /api/account/{uuid}

### 概要

`dbo.account` テーブルから UUID をキーにアカウント情報を取得します。論理削除済みのアカウントは取得できません。

### リクエスト

#### パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|---|---|---|---|
| `uuid` | GUID | ✓ | アカウントの UUID |

```http
GET /api/account/a1b2c3d4-e5f6-7890-abcd-ef1234567890
X-Api-Key: <your-api-key>
```

### レスポンス

#### 200 OK

アカウント取得成功。

```json
{
	"uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
	"userId": "8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46",
	"accountName": "MyCharacter",
	"slotIndex": 0,
	"isActive": true,
	"mode": 0,
	"menuShortcutsJson": "[\"INVENTORY_NORMAL\",\"INVENTORY_EQUIPMENT\",\"INVENTORY_RUNE\",\"INVENTORY_CURRENCY\"]",
	"createdAt": "2026-03-09T12:34:56",
	"updatedAt": "2026-03-20T18:45:12",
	"createdBy": "8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46",
	"updatedBy": "8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46",
	"isDeleted": false
}
```

#### 404 Not Found

指定 UUID のアカウントが存在しない、または論理削除済み。

#### 401 Unauthorized

API キーが指定されていない、または無効。

---

## POST /api/account

### 概要

`dbo.account` テーブルに新規アカウントを登録します。アカウント UUID はサーバー側で自動生成されます。

### リクエスト

```http
POST /api/account
Content-Type: application/json
X-Api-Key: <your-api-key>
```

```json
{
	"userId": "8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46",
	"accountName": "MyCharacter",
	"slotIndex": 0,
	"mode": 0,
	"createdBy": "8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46"
}
```

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| `userId` | GUID | ✓ | 所有プレイヤーの UUID（FK → dbo.user.uuid） |
| `accountName` | string | ✓ | キャラクター名 |
| `slotIndex` | int | ✓ | スロット番号（0 始まり） |
| `mode` | int | ✓ | 権限モード（0: プレイヤー / 1: ビルダー / 2: 管理者） |
| `createdBy` | GUID | ✓ | 作成者の UUID |

### レスポンス

#### 201 Created

アカウント登録成功。登録されたアカウント情報を返します。

#### 401 Unauthorized

API キーが指定されていない、または無効。

---

## PUT /api/account/{uuid}

### 概要

`dbo.account` テーブルの既存アカウント情報を更新します。`null` を指定したフィールドは更新されません。

### リクエスト

#### パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|---|---|---|---|
| `uuid` | GUID | ✓ | 更新対象のアカウント UUID |

```http
PUT /api/account/a1b2c3d4-e5f6-7890-abcd-ef1234567890
Content-Type: application/json
X-Api-Key: <your-api-key>
```

```json
{
	"accountName": "NewCharacterName",
	"isActive": true,
	"mode": null,
	"menuShortcutsJson": null,
	"updatedBy": "8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46"
}
```

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| `accountName` | string \| null | | キャラクター名 |
| `isActive` | bool \| null | | 選択中フラグ |
| `mode` | int \| null | | 権限モード（0: プレイヤー / 1: ビルダー / 2: 管理者） |
| `menuShortcutsJson` | string \| null | | 2x2 craft shortcut settings JSON array |
| `updatedBy` | GUID | ✓ | 更新者の UUID |

### レスポンス

#### 200 OK

アカウント更新成功。更新後のアカウント情報を返します。

#### 404 Not Found

指定 UUID のアカウントが存在しない、または論理削除済み。

#### 401 Unauthorized

API キーが指定されていない、または無効。

---

## AccountResponse フィールド定義

| フィールド | 型 | 説明 |
|---|---|---|
| `uuid` | GUID | アカウントの UUID |
| `userId` | GUID | 所有プレイヤーの UUID |
| `accountName` | string | キャラクター名 |
| `slotIndex` | int | スロット番号（0 始まり） |
| `isActive` | bool | 選択中フラグ（true: 選択中） |
| `mode` | int | 権限モード（0: プレイヤー / 1: ビルダー / 2: 管理者） |
| `menuShortcutsJson` | string | 2x2 craft shortcut settings JSON array |
| `createdAt` | string (ISO 8601) | レコード作成日時 |
| `updatedAt` | string (ISO 8601) | レコード更新日時 |
| `createdBy` | GUID | 作成者 UUID |
| `updatedBy` | GUID | 更新者 UUID |
| `isDeleted` | bool | 論理削除フラグ |

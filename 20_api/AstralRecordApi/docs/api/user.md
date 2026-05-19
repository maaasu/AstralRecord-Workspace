# ユーザー API

## エンドポイント一覧

| メソッド | パス | 概要 |
|---|---|---|
| GET | `/api/user/{uuid}` | ユーザー情報取得 |
| POST | `/api/user` | ユーザー登録 |
| PUT | `/api/user/{uuid}` | ユーザー情報更新 |

## 認証

すべてのリクエストに `X-Api-Key` ヘッダーが必要です。

| ヘッダー | 型 | 必須 | 説明 |
|---|---|---|---|
| `X-Api-Key` | string | ✓ | API キー |

---

## GET /api/user/{uuid}

### 概要

`dbo.user` テーブルから UUID をキーにユーザー情報を取得します。論理削除済みのユーザーは取得できません。

### リクエスト

#### パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|---|---|---|---|
| `uuid` | GUID | ✓ | Minecraft プレイヤーの UUID |

```http
GET /api/user/8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46
X-Api-Key: <your-api-key>
```

### レスポンス

#### 200 OK

ユーザー取得成功。

```json
{
	"uuid": "8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46",
	"mcid": "PlayerName",
	"joinDate": "2026-03-09T12:34:56",
	"lastJoinDate": "2026-03-20T18:45:12",
	"globalIp": "127.0.0.1",
	"accountId": null,
	"banIndefinite": false,
	"banDate": null,
	"kickIp": true,
	"permission": 0,
	"createdAt": "2026-03-09T12:34:56",
	"updatedAt": "2026-03-20T18:45:12",
	"createdBy": "8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46",
	"updatedBy": "8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46",
	"isDeleted": false
}
```

#### 404 Not Found

指定 UUID のユーザーが存在しない、または論理削除済み。

#### 401 Unauthorized

API キーが指定されていない、または無効。

---

## POST /api/user

### 概要

`dbo.user` テーブルに新規ユーザーを登録します。`account_id` は `NULL` で登録され、アカウント作成後に更新します。

### リクエスト

```http
POST /api/user
Content-Type: application/json
X-Api-Key: <your-api-key>
```

```json
{
	"uuid": "8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46",
	"mcid": "PlayerName",
	"joinDate": "2026-03-09T12:34:56",
	"lastJoinDate": "2026-03-09T12:34:56",
	"globalIp": "127.0.0.1",
	"createdBy": "8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46"
}
```

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| `uuid` | GUID | ✓ | Minecraft プレイヤーの UUID |
| `mcid` | string | ✓ | Minecraft ID |
| `joinDate` | string (ISO 8601) | ✓ | 初回参加日時 |
| `lastJoinDate` | string (ISO 8601) | ✓ | 最終参加日時 |
| `globalIp` | string | ✓ | グローバル IP アドレス |
| `createdBy` | GUID | ✓ | 作成者の UUID |

### レスポンス

#### 201 Created

ユーザー登録成功。登録されたユーザー情報を返します。

#### 409 Conflict

指定 UUID のユーザーが既に存在する。

#### 401 Unauthorized

API キーが指定されていない、または無効。

---

## PUT /api/user/{uuid}

### 概要

`dbo.user` テーブルの既存ユーザー情報を更新します。`null` を指定したフィールドは更新されません。

### リクエスト

#### パスパラメータ

| パラメータ | 型 | 必須 | 説明 |
|---|---|---|---|
| `uuid` | GUID | ✓ | 更新対象のプレイヤー UUID |

```http
PUT /api/user/8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46
Content-Type: application/json
X-Api-Key: <your-api-key>
```

```json
{
	"mcid": "NewPlayerName",
	"lastJoinDate": "2026-03-22T10:00:00",
	"globalIp": "192.168.1.1",
	"accountId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
	"banIndefinite": null,
	"banDate": null,
	"kickIp": null,
	"permission": null,
	"updatedBy": "8d8d3d63-6c25-4bde-bf75-6ee3d3f26c46"
}
```

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| `mcid` | string \| null | | Minecraft ID |
| `lastJoinDate` | string \| null (ISO 8601) | | 最終参加日時 |
| `globalIp` | string \| null | | グローバル IP アドレス |
| `accountId` | GUID \| null | | 選択中アカウント UUID（null で更新なし） |
| `banIndefinite` | bool \| null | | 無期限 BAN フラグ |
| `banDate` | string \| null (ISO 8601) | | BAN 解除日時（null で更新なし） |
| `kickIp` | bool \| null | | IP キックフラグ |
| `permission` | int \| null | | 権限レベル |
| `updatedBy` | GUID | ✓ | 更新者の UUID |

### レスポンス

#### 200 OK

ユーザー更新成功。更新後のユーザー情報を返します。

#### 404 Not Found

指定 UUID のユーザーが存在しない、または論理削除済み。

#### 401 Unauthorized

API キーが指定されていない、または無効。

---

## UserResponse フィールド定義

| フィールド | 型 | 説明 |
|---|---|---|
| `uuid` | GUID | Minecraft プレイヤーの UUID |
| `mcid` | string | Minecraft ID |
| `joinDate` | string (ISO 8601) | 初回参加日時 |
| `lastJoinDate` | string (ISO 8601) | 最終参加日時 |
| `globalIp` | string | 最終ログイン IP アドレス |
| `accountId` | GUID \| null | 選択中アカウント UUID |
| `banIndefinite` | bool | 無期限 BAN フラグ |
| `banDate` | string \| null (ISO 8601) | BAN 解除日時 |
| `kickIp` | bool | IP キックフラグ |
| `permission` | int | 権限レベル |
| `createdAt` | string (ISO 8601) | レコード作成日時 |
| `updatedAt` | string (ISO 8601) | レコード更新日時 |
| `createdBy` | GUID | 作成者 UUID |
| `updatedBy` | GUID | 更新者 UUID |
| `isDeleted` | bool | 論理削除フラグ |


# Master data admin tool

`master-data-admin.ps1` は API の `X-Api-Key` 認証を使って filebase YAML を一覧・取得・更新・削除し、Seeder を実行する運用ツールです。

```powershell
.\master-data-admin.ps1 -Action list -BaseUrl https://localhost:5001 -ApiKey $env:ASTRALRECORD_API_KEY
.\master-data-admin.ps1 -Action put -Path 60.features.world/world.yml -ContentFile .\world.yml -ApiKey $env:ASTRALRECORD_API_KEY
.\master-data-admin.ps1 -Action sync -Mode diff -ApiKey $env:ASTRALRECORD_API_KEY
```

更新後に `sync` を実行すると、filebase の YAML が MasterDataDB に反映されます。その後、ゲームサーバーで `/masterdata reload` を実行してください。

管理 API は認証済み利用者向けです。`Path` は filebase ルート配下の `.yml` に限定され、`..` を含むパスは拒否されます。

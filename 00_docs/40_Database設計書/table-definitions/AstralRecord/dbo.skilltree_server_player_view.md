# dbo.skilltree_server_player_view

実ロード済みPluginがプレイヤーごとに評価したWeb表示用viewの短期キャッシュです。

`view_json` は `tree`、`points`、`relockGoldCost` を含み、Webは解放条件・費用を再計算しない。`evaluation_fingerprint` はGold、PP/CP、クラス・レベル、解放条件など判定入力からPluginが生成し、位置・heartbeat時刻など無関係な値を含めない。

TTL切れ、sessionまたはgeneration不一致、state version不一致なら残高を返さず、操作にも使わない。

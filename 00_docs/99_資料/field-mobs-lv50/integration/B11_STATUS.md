# B11 統合受入

固定e9048ffaacb11c6a1aecbc072de489c21e4cc7d9 → f5937cf0b、13所有filesだけ受入。基点41a6dd524から他差分は取り込まない。コノハフクロウ/トゲシカ各Lv36/38/40/42、素材00400/401/402は35/10/35%1個、sale360/760/390で確定。中央4依存constructor生成/register、destroy handleMobDestroyed、onDisable冒頭stopを各1回接続。

第1独立Sol reviewで両clearPathのchunk角見落としを指摘。B10と同じchunk矩形全isChunkLoaded事前確認へ修正し、正規記録を公式updaterで完了。第2独立Sol reviewで境界修正と中央/素材/比較に重要指摘なし。円55%×3の遠隔倍率1.65、近接突進1.25を比較へ反映。

923YAML/1185namespace ID/22技88profile/32新素材、既存12種27profile保持PASS。resource359log/969message PASS。Plugin mvn -o testは1022件失敗0/エラー0/skip0、BUILD SUCCESS、01:14。既存serialVersionUID警告1件のみ。ログC:/Users/kaede/AppData/Local/Temp/field-mobs-lv50-b11-plugin-test.log。ビルド枠解放。

装備導線候補82行（既存採集50+新素材32）をEQUIPMENT_MATERIAL_CANDIDATES.csvへ抽出。Lv20は新規モブ素材の同格候補なし、Lv25はLv22/24、Lv30は追加でLv28から選択。Lv36森林上位、Lv32雪原、メサ/火山/強め種はLv20/25/30装備の必須素材にしない。資料のsource levelはruntime配置/プレイヤー必要Lv確認とは別で、tool tags・入手量・正式Player基準を受入する。

B12未受入のため、23種確定素材表と最終装備開始SHAは次工程。現在22種の表だけを確定とし、B12予約を確定素材に含めない。既存E07以降の追加は独立して継続可能。実server/DB/port、develop/push/version/deployなし、管理表既存メモを保持して最小追記、worktree保持。

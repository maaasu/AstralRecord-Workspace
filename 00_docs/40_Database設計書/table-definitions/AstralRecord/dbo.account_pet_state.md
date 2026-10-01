# dbo.account_pet_state

アカウントごとに選択中のペットを最大1体保持する。`account_id UNIQUEIDENTIFIER` がPK/FK→account.uuid、`equipped_pet_id UNIQUEIDENTIFIER NULL` がFK→pet_instance.instance_id。`updated_at DATETIME2(3)`、`updated_by UNIQUEIDENTIFIER` は非NULL。

APIは所有者・孵化済み・GAME BAG内を検証して選択する。死亡しても選択は保持し、Pluginは召喚しない。選択中の個体は譲渡・出品不可。アカウント削除時に選択行を削除し、複製時に新個体IDへ再マップする。

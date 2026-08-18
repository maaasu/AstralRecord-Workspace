package io.github.maaasu.astralRecord.feature.spawner.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerRegionService;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerLocationRepository;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobSpawnerVisualPermissionTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 22. Mob スポナー座標 > ### Mob spawner 表示・削除認可
     * 検証契約: account modeがADMINならuser.permissionに関係なくMob spawner packet visualを見せる。
     */
    @Test
    void adminAccountModeCanViewSpawnerVisualRegardlessOfPermission() {
        MobSpawnerService service = service();
        AstPlayer administrator = playerWithPermission(UserPermission.PLAYER.getValue());
        AccountModel account = mock(AccountModel.class);
        when(administrator.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.ADMIN);

        assertTrue(service.canViewSpawnerVisual(administrator));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 22. Mob スポナー座標 > ### Mob spawner 表示・削除認可
     * 検証契約: account modeがPLAYERのプレイヤーにはuser.permissionに関係なくMob spawner packet visualを見せない。
     */
    @Test
    void playerAccountModeCannotViewSpawnerVisual() {
        MobSpawnerService service = service();
        AstPlayer player = playerWithPermission(UserPermission.ADMIN.getValue());
        AccountModel account = mock(AccountModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);

        assertFalse(service.canViewSpawnerVisual(player));
        assertFalse(service.canViewSpawnerVisual(null));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 22. Mob スポナー座標 > ### Mob spawner 表示・削除認可
     * 検証契約: Mob spawnerの削除はuser.permission=99かつaccount mode=ADMINでだけ許可する。
     */
    @Test
    void spawnerRemovalRequiresAdminPermissionAndAdminAccountMode() {
        MobSpawnerService service = service();
        AstPlayer administrator = playerWithPermission(UserPermission.ADMIN.getValue());
        AccountModel adminAccount = mock(AccountModel.class);
        when(administrator.getAccount()).thenReturn(adminAccount);
        when(adminAccount.getMode()).thenReturn(AccountMode.ADMIN);

        assertTrue(service.canRemoveSpawner(administrator));

        when(adminAccount.getMode()).thenReturn(AccountMode.PLAYER);
        assertFalse(service.canRemoveSpawner(administrator));

        AstPlayer player = playerWithPermission(UserPermission.PLAYER.getValue());
        when(player.getAccount()).thenReturn(adminAccount);
        when(adminAccount.getMode()).thenReturn(AccountMode.ADMIN);
        assertFalse(service.canRemoveSpawner(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 22. Mob スポナー座標 > ### Mob spawner 表示・削除認可
     * 検証契約: 将来のuser.permission=100でも、AccountMode.ADMINなら表示し、削除は既存の99一致条件を満たさないため許可しない。
     */
    @Test
    void higherFuturePermissionCannotViewOrRemoveSpawner() {
        MobSpawnerService service = service();
        AstPlayer futurePermissionPlayer = playerWithPermission(100);
        AccountModel adminAccount = mock(AccountModel.class);
        when(futurePermissionPlayer.hasAdminPermission()).thenReturn(true);
        when(futurePermissionPlayer.getAccount()).thenReturn(adminAccount);
        when(adminAccount.getMode()).thenReturn(AccountMode.ADMIN);

        assertTrue(service.canViewSpawnerVisual(futurePermissionPlayer));
        assertFalse(service.canRemoveSpawner(futurePermissionPlayer));
    }

    private AstPlayer playerWithPermission(int permission) {
        AstPlayer player = mock(AstPlayer.class);
        UserModel user = mock(UserModel.class);
        when(player.getUser()).thenReturn(user);
        when(user.getPermission()).thenReturn(permission);
        return player;
    }

    private MobSpawnerService service() {
        return new MobSpawnerService(
                PluginMock.builder().withPluginName("AstralRecordTest").build(),
                mock(MobService.class),
                mock(PlayerRegionService.class),
                mock(MobSpawnerDefinitionRepository.class),
                mock(MobSpawnerLocationRepository.class)
        );
    }
}

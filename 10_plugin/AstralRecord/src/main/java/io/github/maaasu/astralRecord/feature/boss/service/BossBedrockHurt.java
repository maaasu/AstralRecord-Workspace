package io.github.maaasu.astralRecord.feature.boss.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.reflect.accessors.ConstructorAccessor;
import com.comphenix.protocol.reflect.accessors.MethodAccessor;
import com.comphenix.protocol.utility.MinecraftReflection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;

/** Geyser が統合版の被弾表示に変換するパケットを組み立てます。 */
final class BossBedrockHurt {
    private static PacketAccess packetAccess;

    private BossBedrockHurt() {
    }

    /**
     * 実体の状態を変更せず、被弾表示用のパケットをメインスレッドで生成します。
     *
     * @param source 被弾を表示するボスの実体
     * @return Geyser へ送る被弾通知
     */
    static PacketContainer create(Entity source) {
        DamageSource damage = DamageSource.builder(DamageType.GENERIC).build();
        if (packetAccess == null) {
            MethodAccessor entityHandle = Accessors.getMethodAccessor(MinecraftReflection.getCraftEntityClass(), "getHandle");
            MethodAccessor damageHandle = Accessors.getMethodAccessor(damage.getClass(), "getHandle");
            ConstructorAccessor constructor = Accessors.getConstructorAccessor(
                PacketType.Play.Server.DAMAGE_EVENT.getPacketClass(),
                entityHandle.getMethod().getReturnType(), damageHandle.getMethod().getReturnType()
            );
            packetAccess = new PacketAccess(entityHandle, damageHandle, constructor);
        }
        // ネイティブのコンストラクターがレジストリの Holder と攻撃者なしの値を設定する。
        return PacketContainer.fromPacket(packetAccess.constructor().invoke(
            packetAccess.entityHandle().invoke(source), packetAccess.damageHandle().invoke(damage)
        ));
    }

    private record PacketAccess(MethodAccessor entityHandle, MethodAccessor damageHandle,
                                ConstructorAccessor constructor) {
    }
}

package io.github.maaasu.astralRecord.feature.skill.service;

import com.comphenix.protocol.utility.MinecraftReflection;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** 武器を持ち替えず、選択枠の処理を Paper の通常の受信パケット列に載せます。 */
final class BedrockSkillHotbarPacketQueue {
    private final Class<?> packetType;
    private final Object processor;
    private final Method schedule;
    private final Method playerHandle;
    private final Field connection;

    private BedrockSkillHotbarPacketQueue() throws ReflectiveOperationException {
        packetType = MinecraftReflection.getPacketClass();
        Class<?> serverType = MinecraftReflection.getMinecraftServerClass();
        Object server = serverType.getMethod("getServer").invoke(null);
        processor = serverType.getMethod("packetProcessor").invoke(server);
        schedule = processor.getClass().getMethod(
            "scheduleIfPossible", MinecraftReflection.getMinecraftClass("network.PacketListener"), packetType
        );
        playerHandle = MinecraftReflection.getCraftPlayerClass().getMethod("getHandle");
        connection = MinecraftReflection.getEntityPlayerClass().getField("connection");
    }

    static @Nullable BedrockSkillHotbarPacketQueue create() {
        try {
            return new BedrockSkillHotbarPacketQueue();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logger.error(LogId.E_3003, failure);
            return null;
        }
    }

    void enqueue(Player player, Object originalPacket, Runnable consume) throws ReflectiveOperationException {
        Object listener = connection.get(playerHandle.invoke(player));
        Object packet = routePacket(packetType, originalPacket, consume);
        schedule.invoke(processor, listener, packet);
    }

    static Object routePacket(Class<?> packetType, Object originalPacket, Runnable consume) {
        return Proxy.newProxyInstance(packetType.getClassLoader(), new Class<?>[] { packetType }, (proxy, method, args) -> {
            if (method.getName().equals("handle")) {
                consume.run();
                return null;
            }
            try {
                return method.invoke(originalPacket, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        });
    }
}

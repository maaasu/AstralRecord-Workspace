package io.github.maaasu.astralRecord.feature.builder.service;

import io.github.maaasu.astralRecord.feature.builder.model.WorldEditSnapshot;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.List;

/**
 * 任意依存のWorldEdit/FAWEから状態を読み、正規コマンドをプレイヤーとして実行します。
 * APIクラスの解決をこの境界に閉じ、WorldEdit未導入でも本体の起動を妨げません。
 */
final class WorldEditGateway {
    private Plugin lastFailedProvider;

    /** 有効なWorldEditまたはFAWEを取得します。 */
    private Plugin provider() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (plugin == null || !plugin.isEnabled()) {
            plugin = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        }
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }

    /** メインスレッドで、現在の提供元とそのデータバージョンを解決します。 */
    SchematicCodec schematicCodec() throws ReflectiveOperationException {
        Plugin plugin = provider();
        if (plugin == null) return null;
        Api api = new Api(plugin.getClass().getClassLoader());
        Object worldEdit = api.invoke("WorldEdit", null, "getInstance");
        Object manager = api.invoke("WorldEdit", worldEdit, "getPlatformManager");
        Class<?> capability = api.type("extension.platform.Capability");
        Object platform = api.invoke("extension.platform.PlatformManager", manager, "queryCapability",
                new Class<?>[]{capability}, capability.getField("WORLD_EDITING").get(null));
        int version = (int) api.invoke("extension.platform.Platform", platform, "getDataVersion");
        Class<?> formats = api.type("extent.clipboard.io.BuiltInClipboardFormat");
        boolean fawe = "FastAsyncWorldEdit".equals(plugin.getName());
        Object format = formats.getField(fawe ? "FAST_V3" : "SPONGE_V3_SCHEMATIC").get(null);
        int paletteLimit = Integer.MAX_VALUE;
        if (fawe) {
            Object[] states = (Object[]) api.type("world.block.BlockTypesCache").getField("states").get(null);
            paletteLimit = Math.min(states.length, Character.MAX_VALUE + 1);
        }
        return new SchematicCodec(plugin, api, platform, format, version, fawe, paletteLimit);
    }

    /** 検証済みのクリップボードを実行者自身のセッションに設定します。 */
    boolean installClipboard(Player player, SchematicCodec codec, Object clipboard) throws ReflectiveOperationException {
        if (provider() != codec.plugin()) return false;
        Api api = codec.api();
        Object session = api.invoke("bukkit.WorldEditPlugin", codec.plugin(), "getSession",
                new Class<?>[]{Player.class}, player);
        Class<?> holderType = api.type("session.ClipboardHolder");
        Object holder = holderType.getConstructor(api.type("extent.clipboard.Clipboard")).newInstance(clipboard);
        api.invoke("LocalSession", session, "setClipboard", new Class<?>[]{holderType}, holder);
        return true;
    }

    /** 検証後のファイルをプラットフォーム共通の公開 API でデコードします。 */
    record SchematicCodec(Plugin plugin, Api api, Object platform, Object format, int dataVersion, boolean fawe,
                          int paletteLimit) {
        Object read(byte[] bytes, SchematicImportValidator.Validation validation)
                throws IOException, ReflectiveOperationException {
            if (validation.dataVersion() > dataVersion) throw new IOException("Unsupported Minecraft data version");
            if (validation.blockStates().size() > paletteLimit) {
                throw new IOException("Schematic palette exceeds the FAWE palette index range");
            }
            validatePalette(validation);
            byte[] input = fawe && !validation.hasOffset() ? SchematicImportNbt.withDefaultOffset(bytes) : bytes;
            try (Closeable reader = (Closeable) api.invoke("extent.clipboard.io.ClipboardFormat", format,
                    "getReader", new Class<?>[]{InputStream.class}, new ByteArrayInputStream(input))) {
                if (fawe) {
                    Function<Object, Object> clipboardFactory = this::memoryClipboard;
                    return api.invoke("extent.clipboard.io.ClipboardReader", reader, "read",
                            new Class<?>[]{UUID.class, Function.class}, UUID.randomUUID(), clipboardFactory);
                }
                return api.invoke("extent.clipboard.io.ClipboardReader", reader, "read");
            }
        }

        /** Reader が不明なパレットを空気に置換する前に、移行後の状態を厳密に照合します。 */
        private void validatePalette(SchematicImportValidator.Validation validation)
                throws IOException, ReflectiveOperationException {
            Object fixer = validation.dataVersion() < dataVersion
                    ? api.invoke("extension.platform.Platform", platform, "getDataFixer") : null;
            Object blockStateFix = fixer == null ? null : api.type("world.DataFixer$FixTypes").getField("BLOCK_STATE").get(null);
            for (String original : validation.blockStates()) {
                String state = original;
                if (fixer != null) {
                    state = (String) api.invoke("world.DataFixer", fixer, "fixUp",
                            new Class<?>[]{api.type("world.DataFixer$FixType"), Object.class, int.class},
                            blockStateFix, original, validation.dataVersion());
                }
                String id = "minecraft:" + SchematicImportValidator.readBlockState(state);
                Object block = api.invoke("world.block.BlockTypes", null, "get", new Class<?>[]{String.class}, id);
                if (block == null) throw new IOException("Unknown block in schematic palette");
                int start = state.indexOf('[');
                if (start >= 0) validateProperties(block, state.substring(start + 1, state.length() - 1));
            }
        }

        private void validateProperties(Object block, String properties)
                throws IOException, ReflectiveOperationException {
            for (String assignment : properties.split(",")) {
                int separator = assignment.indexOf('=');
                Object property = api.invoke("world.block.BlockType", block, "getProperty",
                        new Class<?>[]{String.class}, assignment.substring(0, separator));
                if (property == null) throw new IOException("Unknown schematic block property");
                Object value = api.invoke("registry.state.Property", property, "getValueFor",
                        new Class<?>[]{String.class}, assignment.substring(separator + 1));
                List<?> allowed = (List<?>) api.invoke("registry.state.Property", property, "getValues");
                if (value == null || !allowed.contains(value)) throw new IOException("Unknown schematic block property value");
            }
        }

        /** デコードや検証だけで FAWE のディスククリップボードを生成しないようにします。 */
        private Object memoryClipboard(Object dimensions) {
            try {
                Class<?> vector = api.type("math.BlockVector3");
                Object zero = vector.getField("ZERO").get(null);
                Object one = vector.getField("ONE").get(null);
                Object max = api.invoke("math.BlockVector3", dimensions, "subtract", new Class<?>[]{vector}, one);
                Object region = api.type("regions.CuboidRegion").getConstructor(vector, vector).newInstance(zero, max);
                Class<?> clipboard = Class.forName("com.fastasyncworldedit.core.extent.clipboard.CPUOptimizedClipboard",
                        false, api.loader());
                return clipboard.getConstructor(api.type("regions.Region")).newInstance(region);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot create an in-memory FAWE clipboard", failure);
            }
        }
    }

    /** 実際のセッションを読みます。未選択・未コピーは正常な空状態として扱います。 */
    WorldEditSnapshot inspect(Player player) {
        Plugin plugin = provider();
        if (plugin == null) return new WorldEditSnapshot(false, false, null, null, null, "", false, false);
        try {
            Api api = new Api(plugin.getClass().getClassLoader());
            Object session = api.invoke("bukkit.WorldEditPlugin", plugin, "getSession",
                    new Class<?>[]{Player.class}, player);
            Object world = api.invoke("LocalSession", session, "getSelectionWorld");
            WorldEditSnapshot.Selection selection = null;
            if (world != null) {
                Object region = api.optional("LocalSession", session, "getSelection",
                        "com.sk89q.worldedit.IncompleteRegionException",
                        new Class<?>[]{api.type("world.World")}, world);
                if (region != null) {
                    Object min = api.invoke("regions.Region", region, "getMinimumPoint");
                    Object max = api.invoke("regions.Region", region, "getMaximumPoint");
                    selection = new WorldEditSnapshot.Selection(
                            (String) api.invoke("world.World", world, "getName"),
                            api.coordinate(min, "getBlockX"), api.coordinate(min, "getBlockY"), api.coordinate(min, "getBlockZ"),
                            api.coordinate(max, "getBlockX"), api.coordinate(max, "getBlockY"), api.coordinate(max, "getBlockZ"),
                            ((Number) api.invoke("regions.Region", region, "getVolume")).longValue(),
                            api.type("regions.CuboidRegion").isInstance(region));
                }
            }
            WorldEditSnapshot.Clipboard clipboard = null;
            Object holder = api.optional("LocalSession", session, "getClipboard",
                    "com.sk89q.worldedit.EmptyClipboardException", new Class<?>[0]);
            boolean multipleClipboards = false;
            if (holder != null) {
                Object holders = api.extension("session.ClipboardHolder", holder, "getHolders");
                multipleClipboards = holders instanceof List<?> list && list.size() > 1;
            }
            if (holder != null && !multipleClipboards) {
                Object copy = api.invoke("session.ClipboardHolder", holder, "getClipboard");
                Object region = api.invoke("extent.clipboard.Clipboard", copy, "getRegion");
                Object size = api.invoke("extent.clipboard.Clipboard", copy, "getDimensions");
                Object transform = api.invoke("session.ClipboardHolder", holder, "getTransform");
                clipboard = new WorldEditSnapshot.Clipboard(copy, String.valueOf(transform),
                        ((Number) api.invoke("regions.Region", region, "getVolume")).longValue(),
                        api.coordinate(size, "getBlockX"), api.coordinate(size, "getBlockY"), api.coordinate(size, "getBlockZ"),
                        safeTransform(api, transform));
            }
            Object mask = api.invoke("LocalSession", session, "getMask");
            Object sourceMask = api.extension(session, "getSourceMask");
            boolean transformed = api.extension(session, "getTransform") != null;
            Object restrictions = mask == null && sourceMask == null ? null : Arrays.asList(mask, sourceMask);
            Object actor = api.invoke("bukkit.WorldEditPlugin", plugin, "wrapPlayer", new Class<?>[]{Player.class}, player);
            Object placement = api.optional("LocalSession", session, "getPlacementPosition",
                    "com.sk89q.worldedit.IncompleteRegionException",
                    new Class<?>[]{api.type("extension.platform.Actor")}, actor);
            Object override = api.invoke("LocalSession", session, "getWorldOverride");
            String worldName = override == null ? player.getWorld().getName()
                    : (String) api.invoke("world.World", override, "getName");
            String position = placement == null ? "" : worldName + " " + api.coordinate(placement, "getBlockX")
                    + ", " + api.coordinate(placement, "getBlockY") + ", " + api.coordinate(placement, "getBlockZ");
            // 異なるワールドへのoverrideはGUIから許可せず、明示的な解除を案内します。
            boolean readable = worldName.equals(player.getWorld().getName());
            return new WorldEditSnapshot(true, readable, selection, clipboard, restrictions, position, transformed, multipleClipboards);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            if (lastFailedProvider != plugin) {
                lastFailedProvider = plugin;
                Logger.log(LogId.E_7700, exception, plugin.getName());
            }
            return new WorldEditSnapshot(true, false, null, null, null, "", false, false);
        }
    }

    /** 他プラグインの同名コマンドを呼ばないよう、所有者が一致する登録名だけを返します。 */
    String resolve(String commandLine) {
        Plugin plugin = provider();
        if (plugin == null) return null;
        String label = commandLine.split(" ", 2)[0];
        String qualified = plugin.getName().toLowerCase(Locale.ROOT) + ":" + label;
        Command command = Bukkit.getCommandMap().getCommand(qualified);
        if (!(command instanceof PluginIdentifiableCommand identifiable) || identifiable.getPlugin() != plugin) {
            qualified = label;
            command = Bukkit.getCommandMap().getCommand(label);
            if (!(command instanceof PluginIdentifiableCommand identifiable) || identifiable.getPlugin() != plugin) return null;
        }
        return qualified + commandLine.substring(label.length());
    }

    /** 権限・制限・保護・履歴はWorldEditの正規経路に任せ、コンソール権限を使用しません。 */
    boolean dispatch(Player player, String commandLine) {
        String resolved = resolve(commandLine);
        return resolved != null && player.performCommand(resolved);
    }

    /** 90度単位の回転・反転だけを許し、コピー体積が対象規模の上限となる状態に限定します。 */
    private boolean safeTransform(Api api, Object transform) throws ReflectiveOperationException {
        if ((Boolean) api.invoke("math.transform.Transform", transform, "isIdentity")) return true;
        if (!api.type("math.transform.AffineTransform").isInstance(transform)) return false;
        return unitTransform((double[]) api.invoke("math.transform.AffineTransform", transform, "coefficients"));
    }

    /** 各軸が一つの符号付き単位軸へ写る整数移動行列だけを受け入れます。 */
    private static boolean unitTransform(double[] matrix) {
        if (matrix.length != 12) return false;
        int[] columns = new int[3];
        for (int row = 0; row < 3; row++) {
            int nonzero = 0;
            for (int column = 0; column < 3; column++) {
                double value = matrix[row * 4 + column];
                if (!Double.isFinite(value)) return false;
                if (Math.abs(value) < 1.0e-9) continue;
                if (Math.abs(Math.abs(value) - 1.0) > 1.0e-9) return false;
                nonzero++;
                columns[column]++;
            }
            double offset = matrix[row * 4 + 3];
            if (nonzero != 1 || !Double.isFinite(offset) || Math.abs(offset - Math.rint(offset)) > 1.0e-9) return false;
        }
        return columns[0] == 1 && columns[1] == 1 && columns[2] == 1;
    }

    /** 公開APIの宣言型で反射し、実装クラスの可視性には依存しません。 */
    private record Api(ClassLoader loader) {
        /** WorldEditの公開型を提供元のclassloaderから解決します。 */
        Class<?> type(String suffix) throws ClassNotFoundException {
            return Class.forName("com.sk89q.worldedit." + suffix, false, loader);
        }

        /** 引数のない公開APIを呼びます。 */
        Object invoke(String type, Object target, String method) throws ReflectiveOperationException {
            return invoke(type, target, method, new Class<?>[0]);
        }

        /** 正式な引数型を指定して公開APIを呼びます。 */
        Object invoke(String type, Object target, String method, Class<?>[] arguments, Object... values)
                throws ReflectiveOperationException {
            return type(type).getMethod(method, arguments).invoke(target, values);
        }

        /** 未選択・未コピーの契約例外だけを空状態に変換します。 */
        Object optional(String type, Object target, String method, String emptyException,
                        Class<?>[] arguments, Object... values) throws ReflectiveOperationException {
            try {
                return invoke(type, target, method, arguments, values);
            } catch (InvocationTargetException exception) {
                if (Class.forName(emptyException, false, loader).isInstance(exception.getCause())) return null;
                throw exception;
            }
        }

        /** BlockVector3の整数座標を読みます。 */
        int coordinate(Object point, String method) throws ReflectiveOperationException {
            return ((Number) invoke("math.BlockVector3", point, method)).intValue();
        }

        /** FAWE限定APIは宣言がない通常WorldEditでだけ未設定として扱います。 */
        Object extension(Object session, String method) throws ReflectiveOperationException {
            return extension("LocalSession", session, method);
        }

        /** FAWEの保持コピー一覧など、宣言の有無が提供元で異なるAPIを解決します。 */
        Object extension(String type, Object target, String method) throws ReflectiveOperationException {
            try {
                return invoke(type, target, method);
            } catch (NoSuchMethodException absent) {
                return null;
            }
        }
    }
}

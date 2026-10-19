package io.github.maaasu.astralRecord.feature.defense;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 帰還情報とAPI保存待ちを原子的にローカル保存します。戦闘の再開には使いません。 */
public final class DefenseStateStore {
    private final Path file;
    private static final Gson GSON = new Gson();

    /** @param pluginDirectory プラグインのデータディレクトリ */
    public DefenseStateStore(Path pluginDirectory) { file = pluginDirectory.resolve("defense_runtime.json"); }

    /** @return 保存済みのJSON。未作成時だけ空 @throws IOException 破損時は上書きせず停止 */
    public JsonObject load() throws IOException {
        if (!Files.exists(file)) return new JsonObject();
        try { return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException ex) { throw new IOException("Invalid defense runtime journal", ex); }
    }

    /** @param state 帰還とoutboxを含む不変化済みJSON @throws IOException 保存失敗 */
    public void save(JsonObject state) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(state), StandardCharsets.UTF_8);
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

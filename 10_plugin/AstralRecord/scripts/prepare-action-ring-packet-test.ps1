[CmdletBinding()]
param(
    [string]$ServerRoot = "",
    [int]$ServerPort = 25578,
    [switch]$UseLiveServerClone,
    [switch]$RefreshLiveServerClone,
    [int]$ReproductionWindowMs = 2000,
    [int]$AutoOpenDelayTicks = 60,
    [int]$AutoOpenRetryCount = 20,
    [int]$AutoOpenRetryIntervalTicks = 20
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$pluginRoot = Split-Path -Parent $scriptRoot
$workspaceRoot = Split-Path -Parent (Split-Path -Parent $pluginRoot)
$startScript = Join-Path $scriptRoot "start-dev-server.ps1"

if ([string]::IsNullOrWhiteSpace($ServerRoot)) {
    $ServerRoot = Join-Path $pluginRoot ".dev-server\integration-live-clone-actionring"
}

function Set-IndentedYamlValue {
    param(
        [System.Collections.Generic.List[string]]$Lines,
        [Parameter(Mandatory = $true)]
        [string]$SectionPattern,
        [Parameter(Mandatory = $true)]
        [string]$KeyPattern,
        [Parameter(Mandatory = $true)]
        [string]$ValueLine
    )

    if ($null -eq $Lines -or $Lines.Count -eq 0) {
        throw "YAML lines are empty."
    }

    $sectionIndex = -1
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match $SectionPattern) {
            $sectionIndex = $i
            break
        }
    }
    if ($sectionIndex -lt 0) {
        throw "Section was not found: $SectionPattern"
    }

    $sectionEnd = $Lines.Count
    for ($i = $sectionIndex + 1; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match '^\s{2}\S') {
            $sectionEnd = $i
            break
        }
    }

    for ($i = $sectionIndex + 1; $i -lt $sectionEnd; $i++) {
        if ($Lines[$i] -match $KeyPattern) {
            $Lines[$i] = $ValueLine
            return
        }
    }

    $Lines.Insert($sectionIndex + 1, $ValueLine)
}

function Set-TopLevelYamlValue {
    param(
        [System.Collections.Generic.List[string]]$Lines,
        [Parameter(Mandatory = $true)]
        [string]$SectionPattern,
        [Parameter(Mandatory = $true)]
        [string]$KeyPattern,
        [Parameter(Mandatory = $true)]
        [string]$ValueLine
    )

    if ($null -eq $Lines -or $Lines.Count -eq 0) {
        throw "YAML lines are empty."
    }

    $sectionIndex = -1
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match $SectionPattern) {
            $sectionIndex = $i
            break
        }
    }
    if ($sectionIndex -lt 0) {
        throw "Section was not found: $SectionPattern"
    }

    $sectionEnd = $Lines.Count
    for ($i = $sectionIndex + 1; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match '^\S') {
            $sectionEnd = $i
            break
        }
    }

    for ($i = $sectionIndex + 1; $i -lt $sectionEnd; $i++) {
        if ($Lines[$i] -match $KeyPattern) {
            $Lines[$i] = $ValueLine
            return
        }
    }

    $Lines.Insert($sectionIndex + 1, $ValueLine)
}

function Set-PropertiesValue {
    param(
        [System.Collections.Generic.List[string]]$Lines,
        [Parameter(Mandatory = $true)]
        [string]$Key,
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    $pattern = '^' + [regex]::Escape($Key) + '='
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match $pattern) {
            $Lines[$i] = "$Key=$Value"
            return
        }
    }
    [void]$Lines.Add("$Key=$Value")
}

function Set-ActionRingDirectConnectPaperConfig {
    param([Parameter(Mandatory = $true)][string]$PaperGlobalPath)

    if (-not (Test-Path -LiteralPath $PaperGlobalPath)) {
        return
    }

    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in @(Get-Content -LiteralPath $PaperGlobalPath)) {
        [void]$lines.Add($line)
    }

    $inBungee = $false
    $inVelocity = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s{2}bungee-cord:\s*$') {
            $inBungee = $true
            $inVelocity = $false
            continue
        }
        if ($lines[$i] -match '^\s{2}velocity:\s*$') {
            $inBungee = $false
            $inVelocity = $true
            continue
        }
        if ($lines[$i] -match '^\s{2}\S' -and $lines[$i] -notmatch '^\s{2}(bungee-cord|velocity):\s*$') {
            $inBungee = $false
            $inVelocity = $false
        }

        if ($inBungee -and $lines[$i] -match '^\s{4}online-mode:\s*') {
            $lines[$i] = '    online-mode: false'
            continue
        }
        if ($inVelocity -and $lines[$i] -match '^\s{4}enabled:\s*') {
            $lines[$i] = '    enabled: false'
            continue
        }
        if ($inVelocity -and $lines[$i] -match '^\s{4}online-mode:\s*') {
            $lines[$i] = '    online-mode: false'
        }
    }

    Set-Content -LiteralPath $PaperGlobalPath -Value $lines -Encoding UTF8
}

function Resolve-ServerJarPath {
    param([Parameter(Mandatory = $true)][string]$Root)

    $serverJar = Join-Path $Root "server.jar"
    if (Test-Path -LiteralPath $serverJar) {
        return (Resolve-Path -LiteralPath $serverJar).ProviderPath
    }

    $candidate = Get-ChildItem -Path $Root -Filter "*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(purpur|paper|spigot).+\.jar$' } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $candidate) {
        throw "No Paper/Purpur server jar was found under $Root."
    }
    return $candidate.FullName
}

function Resolve-ProtocolLibJarPath {
    param([Parameter(Mandatory = $true)][string]$Root)

    $pluginsDir = Join-Path $Root "plugins"
    $candidate = Get-ChildItem -Path $pluginsDir -Filter "ProtocolLib*.jar" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $candidate) {
        throw "ProtocolLib jar was not found under $pluginsDir. Use -UseLiveServerClone or install ProtocolLib first."
    }
    return $candidate.FullName
}

function Write-ProbeSource {
    param(
        [Parameter(Mandatory = $true)][string]$SourcePath,
        [Parameter(Mandatory = $true)][int]$WindowMs
    )

    $source = @"
package codex.probe;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class ActionRingPacketProbePlugin extends JavaPlugin implements Listener {
    private static final int ACTION_RING_ENTITY_ID_MIN = 2200000;
    private static final int ACTION_RING_ENTITY_ID_MAX = 2300000;
    private static final long REPRODUCTION_WINDOW_MS = ${WindowMs}L;
    private static final long AUTO_OPEN_DELAY_TICKS = ${AutoOpenDelayTicks}L;
    private static final int AUTO_OPEN_RETRY_COUNT = ${AutoOpenRetryCount};
    private static final long AUTO_OPEN_RETRY_INTERVAL_TICKS = ${AutoOpenRetryIntervalTicks}L;
    private final Map<Integer, Long> spawnedAt = new ConcurrentHashMap<>();
    private final Set<UUID> autoOpenedPlayers = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
            this,
            ListenerPriority.MONITOR,
            PacketType.Play.Server.SPAWN_ENTITY,
            PacketType.Play.Server.ENTITY_DESTROY
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                PacketType type = event.getPacketType();
                if (type == PacketType.Play.Server.SPAWN_ENTITY) {
                    Integer entityId = event.getPacket().getIntegers().readSafely(0);
                    if (isActionRingEntityId(entityId)) {
                        spawnedAt.put(entityId, System.currentTimeMillis());
                        getLogger().info("ACTION_RING_PACKET spawn id=" + entityId + " player=" + event.getPlayer().getName());
                    }
                    return;
                }
                if (type == PacketType.Play.Server.ENTITY_DESTROY) {
                    for (int entityId : destroyEntityIds(event)) {
                        if (!isActionRingEntityId(entityId)) {
                            continue;
                        }
                        Long startedAt = spawnedAt.remove(entityId);
                        long elapsedMs = startedAt == null ? -1L : System.currentTimeMillis() - startedAt;
                        String message = "ACTION_RING_PACKET destroy id=" + entityId
                            + " player=" + event.getPlayer().getName()
                            + " spawn_to_destroy_ms=" + elapsedMs;
                        if (elapsedMs >= 0L && elapsedMs <= REPRODUCTION_WINDOW_MS) {
                            getLogger().warning("ACTION_RING_PACKET_REPRODUCED " + message);
                        } else {
                            getLogger().info(message);
                        }
                    }
                }
            }
        });
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduleAutoOpen(player, "enable-online-player", AUTO_OPEN_DELAY_TICKS);
        }
        getLogger().info("Action ring packet probe enabled. reproductionWindowMs=" + REPRODUCTION_WINDOW_MS
            + " autoOpenDelayTicks=" + AUTO_OPEN_DELAY_TICKS);
    }

    @Override
    public void onDisable() {
        spawnedAt.clear();
        autoOpenedPlayers.clear();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        scheduleAutoOpen(event.getPlayer(), "player-join", AUTO_OPEN_DELAY_TICKS);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("usage: /" + label + " <player>");
            return true;
        }
        Player player = Bukkit.getPlayerExact(args[0]);
        if (player == null) {
            sender.sendMessage("player not found: " + args[0]);
            return true;
        }
        autoOpenedPlayers.remove(player.getUniqueId());
        scheduleAutoOpen(player, "command", 1L);
        sender.sendMessage("scheduled action ring packet probe for " + player.getName());
        return true;
    }

    private void scheduleAutoOpen(Player player, String reason, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(this, () -> tryAutoOpen(player.getName(), reason, 0), delayTicks);
    }

    private void tryAutoOpen(String playerName, String reason, int attempt) {
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null || !player.isOnline()) {
            getLogger().info("ACTION_RING_AUTOTEST skipped player=" + playerName + " reason=offline");
            return;
        }
        if (autoOpenedPlayers.contains(player.getUniqueId())) {
            getLogger().info("ACTION_RING_AUTOTEST skipped player=" + playerName + " reason=already-opened");
            return;
        }

        Object astPlayer = astPlayer(player);
        if (astPlayer == null) {
            if (attempt < AUTO_OPEN_RETRY_COUNT) {
                Bukkit.getScheduler().runTaskLater(
                    this,
                    () -> tryAutoOpen(playerName, reason, attempt + 1),
                    AUTO_OPEN_RETRY_INTERVAL_TICKS
                );
                return;
            }
            getLogger().warning("ACTION_RING_AUTOTEST failed player=" + playerName + " reason=ast-player-unavailable");
            return;
        }

        Object service = actionRingService();
        if (service == null) {
            getLogger().warning("ACTION_RING_AUTOTEST failed player=" + playerName + " reason=service-unavailable");
            return;
        }

        try {
            boolean open = (Boolean) service.getClass().getMethod("isOpen", Player.class).invoke(service, player);
            if (open) {
                service.getClass().getMethod("close", Player.class).invoke(service, player);
            }
            service.getClass().getMethod("toggle", astPlayer.getClass()).invoke(service, astPlayer);
            autoOpenedPlayers.add(player.getUniqueId());
            getLogger().warning("ACTION_RING_AUTOTEST opened player=" + playerName + " reason=" + reason);
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("ACTION_RING_AUTOTEST failed player=" + playerName
                + " reason=" + exception.getClass().getSimpleName()
                + " message=" + exception.getMessage());
        }
    }

    private Object astPlayer(Player player) {
        try {
            Class<?> cacheClass = Class.forName("io.github.maaasu.astralRecord.feature.player.AstPlayerCache");
            return cacheClass.getMethod("get", Player.class).invoke(null, player);
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("ACTION_RING_AUTOTEST ast-player-cache-error type="
                + exception.getClass().getSimpleName() + " message=" + exception.getMessage());
            return null;
        }
    }

    private Object actionRingService() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("AstralRecord");
            if (plugin == null || !plugin.isEnabled()) {
                return null;
            }
            var field = plugin.getClass().getDeclaredField("skillActionRingService");
            field.setAccessible(true);
            return field.get(plugin);
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("ACTION_RING_AUTOTEST service-error type="
                + exception.getClass().getSimpleName() + " message=" + exception.getMessage());
            return null;
        }
    }

    private boolean isActionRingEntityId(Integer entityId) {
        return entityId != null && entityId >= ACTION_RING_ENTITY_ID_MIN && entityId < ACTION_RING_ENTITY_ID_MAX;
    }

    private List<Integer> destroyEntityIds(PacketEvent event) {
        List<Integer> ids = event.getPacket().getIntLists().readSafely(0);
        if (ids != null) {
            return ids;
        }
        int[] array = event.getPacket().getIntegerArrays().readSafely(0);
        if (array == null) {
            return List.of();
        }
        return Arrays.stream(array).boxed().toList();
    }
}
"@

    [System.IO.File]::WriteAllText(
        $SourcePath,
        $source,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Build-ActionRingPacketProbe {
    param([Parameter(Mandatory = $true)][string]$Root)

    Resolve-ServerJarPath -Root $Root | Out-Null
    Resolve-ProtocolLibJarPath -Root $Root | Out-Null
    $probeRoot = Join-Path $Root ".codex-action-ring-packet-probe"
    $sourceDir = Join-Path $probeRoot "src\codex\probe"
    $classesDir = Join-Path $probeRoot "classes"
    $resourcesDir = Join-Path $probeRoot "resources"
    $stageDir = Join-Path $probeRoot "jar-stage"
    $classpathFile = Join-Path $probeRoot "compile-classpath.txt"
    $sourcePath = Join-Path $sourceDir "ActionRingPacketProbePlugin.java"
    $pluginYmlPath = Join-Path $resourcesDir "plugin.yml"
    $probeJar = Join-Path (Join-Path $Root "plugins") "ActionRingPacketProbe.jar"

    New-Item -ItemType Directory -Force -Path $sourceDir, $classesDir, $resourcesDir | Out-Null
    if (Test-Path -LiteralPath $stageDir) {
        Remove-Item -LiteralPath $stageDir -Recurse -Force
    }
    Write-ProbeSource -SourcePath $sourcePath -WindowMs $ReproductionWindowMs

    @(
        "name: ActionRingPacketProbe"
        "version: 1.0.0"
        "main: codex.probe.ActionRingPacketProbePlugin"
        "api-version: '1.21'"
        "depend: [ProtocolLib, AstralRecord]"
        "commands:"
        "  actionringprobe:"
        "    description: Open AstralRecord action ring for packet reproduction."
        "    usage: /actionringprobe <player>"
    ) | Set-Content -LiteralPath $pluginYmlPath -Encoding ASCII

    Push-Location $pluginRoot
    try {
        & mvn "-q" "-Dmdep.outputFile=$classpathFile" "dependency:build-classpath"
        if ($LASTEXITCODE -ne 0) {
            throw "Maven dependency classpath generation failed with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }

    $classpath = Get-Content -LiteralPath $classpathFile -Raw
    & javac "-encoding" "UTF-8" "-proc:none" "-classpath" $classpath "-d" $classesDir $sourcePath
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed while building ActionRingPacketProbe."
    }

    if (Test-Path -LiteralPath $probeJar) {
        Remove-Item -LiteralPath $probeJar -Force
    }
    New-Item -ItemType Directory -Force -Path $stageDir | Out-Null
    Copy-Item -LiteralPath (Join-Path $resourcesDir "plugin.yml") -Destination (Join-Path $stageDir "plugin.yml") -Force
    Copy-Item -Path (Join-Path $classesDir "*") -Destination $stageDir -Recurse -Force
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($stageDir, $probeJar)

    return $probeJar
}

& $startScript `
    -UseLiveServerClone:$UseLiveServerClone `
    -RefreshLiveServerClone:$RefreshLiveServerClone `
    -ServerRoot $ServerRoot `
    -NoStart

$configPath = Join-Path $ServerRoot "plugins\AstralRecord\config.yml"
if (-not (Test-Path -LiteralPath $configPath)) {
    throw "config.yml was not found: $configPath"
}

$configLines = [System.Collections.Generic.List[string]]::new()
foreach ($line in @(Get-Content -LiteralPath $configPath)) {
    [void]$configLines.Add($line)
}
$fileRootPath = Join-Path $workspaceRoot "40_filebase"
$escapedFileRootPath = $fileRootPath.Replace('\', '\\')

Set-IndentedYamlValue -Lines $configLines -SectionPattern '^\s{2}sqlserver:\s*$' -KeyPattern '^\s{4}enabled:\s*' -ValueLine '    enabled: false'
Set-IndentedYamlValue -Lines $configLines -SectionPattern '^\s{2}file:\s*$' -KeyPattern '^\s{4}rootPath:\s*' -ValueLine "    rootPath: `"$escapedFileRootPath`""
Set-TopLevelYamlValue -Lines $configLines -SectionPattern '^resourcePack:\s*$' -KeyPattern '^\s{2}prompt:\s*' -ValueLine '  prompt: "AstralRecord resource pack"'
Set-Content -LiteralPath $configPath -Value $configLines -Encoding UTF8

$serverPropertiesPath = Join-Path $ServerRoot "server.properties"
if (Test-Path -LiteralPath $serverPropertiesPath) {
    $serverProperties = [System.Collections.Generic.List[string]]::new()
    foreach ($line in @(Get-Content -LiteralPath $serverPropertiesPath)) {
        [void]$serverProperties.Add($line)
    }
    $serverPortUpdated = $false
    for ($i = 0; $i -lt $serverProperties.Count; $i++) {
        if ($serverProperties[$i] -match '^server-port=') {
            $serverProperties[$i] = "server-port=$ServerPort"
            $serverPortUpdated = $true
            break
        }
    }
    if (-not $serverPortUpdated) {
        $serverProperties.Add("server-port=$ServerPort")
    }
    Set-PropertiesValue -Lines $serverProperties -Key "online-mode" -Value "false"
    Set-PropertiesValue -Lines $serverProperties -Key "prevent-proxy-connections" -Value "false"
    Set-PropertiesValue -Lines $serverProperties -Key "enforce-secure-profile" -Value "false"
    Set-Content -LiteralPath $serverPropertiesPath -Value $serverProperties -Encoding ASCII
}

Set-ActionRingDirectConnectPaperConfig -PaperGlobalPath (Join-Path $ServerRoot "config\paper-global.yml")

$probeJar = Build-ActionRingPacketProbe -Root $ServerRoot

Write-Host "[AstralRecord action-ring-packet-test] Prepared: $ServerRoot"
Write-Host "[AstralRecord action-ring-packet-test] SQL Server disabled in: $configPath"
Write-Host "[AstralRecord action-ring-packet-test] Filebase root set to: $fileRootPath"
Write-Host "[AstralRecord action-ring-packet-test] Server port set to: $ServerPort"
Write-Host "[AstralRecord action-ring-packet-test] Packet probe installed: $probeJar"
Write-Host "[AstralRecord action-ring-packet-test] Next:"
Write-Host "  1. Start server with start-dev-server.ps1 -ServerRoot `"$ServerRoot`" -SkipBuild"
Write-Host "  2. Join the server. The probe auto-opens the action ring for the joined player."
Write-Host "  3. Check logs/latest.log for ACTION_RING_AUTOTEST and ACTION_RING_PACKET_REPRODUCED"
Write-Host "  4. Optional manual trigger: /actionringprobe <player>"

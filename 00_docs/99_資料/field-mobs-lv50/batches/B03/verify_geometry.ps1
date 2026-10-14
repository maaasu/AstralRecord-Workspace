param(
    [string]$BaselineClasses = 'E:\AstralRecord-Workspace\10_plugin\AstralRecord\target\classes',
    [string]$MavenCacheRoot = 'C:\Users\kaede\.m2\repository',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-21.0.10',
    [string]$OutputRoot = 'C:\Users\kaede\AppData\Local\Temp\field-mobs-b03-compile'
)

# B03だけを既存の共通classとキャッシュ済みPaperへ照合する診断。Maven/サーバーを起動しない。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$b03RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../../..')).Path
$b03RequiredClass = Join-Path $BaselineClasses 'io/github/maaasu/astralRecord/feature/mob/skill/MobSkillExecutor.class'
if (-not (Test-Path -LiteralPath $b03RequiredClass)) {
    throw '既存の共通コンパイルclassが必要です。統合担当の直列build後のclassesを指定してください。'
}
$b03JarRoots = @(
    'io/papermc/paper/paper-api/1.21.11-R0.1-SNAPSHOT',
    'org/jetbrains/annotations', 'org/jetbrains/kotlin/kotlin-stdlib',
    'net/kyori', 'com/google/guava/guava', 'org/joml'
) | ForEach-Object { Join-Path $MavenCacheRoot $_ }
$b03Jars = @(Get-ChildItem -LiteralPath $b03JarRoots -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -ExpandProperty FullName)
$b03Classpath = (@($OutputRoot, $BaselineClasses) + $b03Jars) -join ';'
$b03Sources = @(
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/skill/field/PlainRamMobSkillExecutor.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/skill/field/BladeMantisMobSkillExecutor.java',
    '00_docs/99_資料/field-mobs-lv50/batches/B03/B03GeometryCheck.java'
) | ForEach-Object { Join-Path $b03RepoRoot $_ }
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$b03CompileOutput = & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -proc:none `
    -classpath $b03Classpath -d $OutputRoot @b03Sources 2>&1
$b03CompileExit = $LASTEXITCODE
$b03CompileOutput | Set-Content -LiteralPath (Join-Path $OutputRoot 'javac-output.txt') -Encoding UTF8
$b03CompileOutput | Write-Output
if ($b03CompileExit -ne 0) { exit $b03CompileExit }
$b03TestOutput = & (Join-Path $JavaHome 'bin/java.exe') -classpath $b03Classpath `
    io.github.maaasu.astralRecord.feature.mob.skill.field.B03GeometryCheck 2>&1
$b03TestExit = $LASTEXITCODE
$b03TestOutput | Set-Content -LiteralPath (Join-Path $OutputRoot 'geometry-output.txt') -Encoding UTF8
$b03TestOutput | Write-Output
exit $b03TestExit

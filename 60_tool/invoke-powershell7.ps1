# Windows PowerShell 5 compatible bootstrap; remaining arguments are forwarded without evaluation.
param([string]$ScriptPath)
$ErrorActionPreference='Stop'
try {
    $localConfig=Join-Path $PSScriptRoot 'powershell7.local.json'
    if (Test-Path -LiteralPath $localConfig -PathType Leaf) {
        $settings=Get-Content -Raw -Encoding UTF8 -LiteralPath $localConfig | ConvertFrom-Json
        $executable=[string]$settings.executablePath
        if (![IO.Path]::IsPathRooted($executable) -or !(Test-Path -LiteralPath $executable -PathType Leaf) -or
            [IO.Path]::GetFileName($executable) -ine 'pwsh.exe') { throw 'powershell7.local.json must point to an existing absolute pwsh.exe path.' }
    } else {
        $command=Get-Command pwsh -CommandType Application -ErrorAction SilentlyContinue
        if (!$command) { throw 'PowerShell 7 is required. Configure 60_tool/powershell7.local.json with executablePath or install pwsh on PATH.' }
        $executable=$command.Source
    }
    if ([string]::IsNullOrWhiteSpace($ScriptPath) -or !(Test-Path -LiteralPath $ScriptPath -PathType Leaf)) { throw 'The update entry script was not found.' }
    # Only this launcher process and its children receive the configured build tools.
    $buildConfig=Join-Path $PSScriptRoot 'build-tools.local.json'
    if (Test-Path -LiteralPath $buildConfig -PathType Leaf) {
        $buildSettings=Get-Content -Raw -Encoding UTF8 -LiteralPath $buildConfig | ConvertFrom-Json
        if ($buildSettings -isnot [pscustomobject]) { throw 'build-tools.local.json must contain an object.' }
        $requiredFiles=@{javaHome=@('bin/java.exe','bin/javac.exe'); mavenHome=@('bin/mvn.cmd'); dotnetRoot=@('dotnet.exe')}
        $buildPaths=@()
        foreach ($property in $buildSettings.PSObject.Properties) {
            if (!$requiredFiles.ContainsKey($property.Name)) { throw 'Unknown setting in build-tools.local.json. Use javaHome, mavenHome, or dotnetRoot.' }
            if ($property.Value -isnot [string] -or [string]::IsNullOrWhiteSpace($property.Value) -or
                ![IO.Path]::IsPathRooted($property.Value) -or
                $property.Value -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\/]+[\\/][^\\/]+(?:[\\/]|$))') {
                throw "build-tools.local.json $($property.Name) must be an absolute directory path."
            }
            foreach ($relativeFile in $requiredFiles[$property.Name]) {
                if (!(Test-Path -LiteralPath (Join-Path $property.Value $relativeFile) -PathType Leaf)) {
                    throw "build-tools.local.json $($property.Name) is missing $relativeFile."
                }
            }
            $buildPaths+=if ($property.Name -eq 'dotnetRoot') { $property.Value } else { Join-Path $property.Value 'bin' }
        }
        if ($buildSettings.PSObject.Properties['javaHome']) { $env:JAVA_HOME=$buildSettings.javaHome }
        if ($buildSettings.PSObject.Properties['dotnetRoot']) { $env:DOTNET_ROOT=$buildSettings.dotnetRoot }
        if ($buildPaths.Count -gt 0) { $env:PATH=($buildPaths -join [IO.Path]::PathSeparator)+[IO.Path]::PathSeparator+$env:PATH }
    }
    & $executable -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @args
    exit $LASTEXITCODE
} catch {
    Write-Error $_.Exception.Message
    exit 1
}

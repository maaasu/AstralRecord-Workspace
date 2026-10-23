#requires -Version 7.0
param([Parameter(Mandatory)][string]$DotNetPath)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
function Assert($Condition,[string]$Message){if(!$Condition){throw $Message}}
$root=Join-Path ([IO.Path]::GetTempPath()) ('ar migration IO '+[Guid]::NewGuid().ToString('N'))
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
New-Item -ItemType Directory -Path $root | Out-Null
$fixture=Join-Path $root 'repository'
New-Item -ItemType Directory -Path $fixture | Out-Null
$sqlRelative='00_docs/40_Database設計書/table-definitions/AstralRecord/migrations/test.sql'
$sqlPath=Join-Path $fixture $sqlRelative
New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($sqlPath)) -Force | Out-Null
$sql="-- 日本語のmigration`nSELECT N'内容を保持';`n"
[IO.File]::WriteAllText($sqlPath,$sql,[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $repo '.gitattributes') -Destination $fixture
& git -C $fixture init --quiet
& git -C $fixture -c core.autocrlf=true add -- .gitattributes $sqlRelative
& git -C $fixture -c user.name=Fixture -c user.email=fixture@example.invalid commit --quiet -m fixture
if($LASTEXITCODE -ne 0){throw 'Git fixture setup failed.'}
$checkout=(Join-Path $root 'checkout')+[IO.Path]::DirectorySeparatorChar
New-Item -ItemType Directory -Path $checkout | Out-Null
& git -C $fixture -c core.autocrlf=true checkout-index --prefix=$checkout -- $sqlRelative
if($LASTEXITCODE -ne 0){throw 'Git checkout fixture failed.'}
$bytes=[IO.File]::ReadAllBytes((Join-Path $checkout $sqlRelative))
Assert (@($bytes | Where-Object {$_ -eq 13}).Count -eq 0) 'autocrlf changed migration checkout to CRLF.'
Assert ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)) -ceq [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($sql)))) 'Checkout changed migration contents/hash.'

$probeRoot=Join-Path $root 'native'
New-Item -ItemType Directory -Path $probeRoot | Out-Null
[IO.File]::WriteAllText((Join-Path $probeRoot 'probe.csproj'),'<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>')
[IO.File]::WriteAllText((Join-Path $probeRoot 'Program.cs'),@'
System.Console.OutputEncoding = new System.Text.UTF8Encoding(false);
System.Console.WriteLine("40_Database設計書: migration検査");
System.Console.Error.WriteLine("検査の失敗メッセージ");
return 17;
'@,[Text.UTF8Encoding]::new($false))
& $DotNetPath build (Join-Path $probeRoot 'probe.csproj') --nologo --verbosity quiet
if($LASTEXITCODE -ne 0){throw 'Native UTF-8 probe build failed.'}
$launcher=Join-Path $root 'invoke-powershell7.ps1'
Copy-Item -LiteralPath (Join-Path $repo '60_tool/invoke-powershell7.ps1') -Destination $launcher
@{executablePath=(Join-Path $PSHOME 'pwsh.exe')} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $root 'powershell7.local.json')
$nativeDll=Join-Path $probeRoot 'bin/Debug/net10.0/probe.dll'
@'
param($DotNetPath,$NativeDll)
& $DotNetPath $NativeDll
exit $LASTEXITCODE
'@ | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $root 'backend.ps1')
@'
param($DotNetPath,$NativeDll,$LogPath)
& (Join-Path $PSHOME 'pwsh.exe') -NoProfile -File (Join-Path $PSScriptRoot 'backend.ps1') $DotNetPath $NativeDll 2>&1 | Tee-Object -FilePath $LogPath | Out-Host
exit $LASTEXITCODE
'@ | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $root 'frontend.ps1')
@'
param($DotNetPath,$NativeDll,$LogPath,$ResultPath)
$before=[Console]::OutputEncoding
try {
    [Console]::OutputEncoding=[Text.Encoding]::GetEncoding(932)
    & (Join-Path $PSScriptRoot 'invoke-powershell7.ps1') -ScriptPath (Join-Path $PSScriptRoot 'frontend.ps1') $DotNetPath $NativeDll $LogPath
    $childExit=$LASTEXITCODE
    @{exitCode=$childExit;restoredCodePage=[Console]::OutputEncoding.CodePage} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $ResultPath
} finally {[Console]::OutputEncoding=$before}
'@ | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $root 'host.ps1')
$log=Join-Path $root 'captured.log';$resultPath=Join-Path $root 'result.json'
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'host.ps1') $DotNetPath $nativeDll $log $resultPath | Out-Host
if($LASTEXITCODE -ne 0){throw 'PS5 host fixture failed.'}
$result=Get-Content -Raw -Encoding UTF8 -LiteralPath $resultPath | ConvertFrom-Json
Assert ($result.exitCode -eq 17) 'Native exit code was lost.'
Assert ($result.restoredCodePage -eq 932) 'Calling console code page was not restored.'
$lines=@(Get-Content -Encoding UTF8 -LiteralPath $log)
Assert ($lines.Count -eq 2 -and $lines -ccontains '40_Database設計書: migration検査' -and $lines -ccontains '検査の失敗メッセージ') 'Native Japanese stdout/stderr was corrupted in the Tee log.'
Write-Host "PASS: LF migration checkout under autocrlf, CP932 -> PS5 -> PS7 -> native UTF-8 -> Tee, exit code and code-page restoration. Evidence: $root"

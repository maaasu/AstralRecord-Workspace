#requires -Version 7.0
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '../EnvironmentFacts.psm1') -Force
$fixture=@{
    hostId='0604b62f-c10b-46e8-a0cf-cca70aab6b5f';serverUrl='https://control.example.test';token='PRIVATE_SENTINEL';credentials=@{secret='PRIVATE_SENTINEL'}
    localResources=@{roots=@(@{id='root';path='C:\server';backupOnly=$false;secret='PRIVATE_SENTINEL'});executables=@(@{id='java';path='C:\java\java.exe';kind='Java';arguments='PRIVATE_SENTINEL'})}
}
$safe=Get-PublicControlFacts $fixture
$json=$safe | ConvertTo-Json -Depth 10
if ($json.Contains('PRIVATE_SENTINEL') -or $safe.hostId -ne $fixture.hostId -or $safe.localResources.roots[0].path -ne 'C:\server') { throw 'Secret filtering or resource extraction failed.' }
$fixture.serverUrl='https://user:PRIVATE_SENTINEL@control.example.test/?secret=PRIVATE_SENTINEL'
$safe=Get-PublicControlFacts $fixture
if ($safe.Contains('serverUrl')) { throw 'Credential-bearing URL must be omitted.' }
Write-Host 'PASS: environment report allowlist preserves roots/IDs and excludes tokens, credentials, arguments, and unsafe URLs.'

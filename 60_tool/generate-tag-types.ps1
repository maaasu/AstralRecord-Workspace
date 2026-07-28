param(
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$toolRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repositoryRoot = Split-Path -Parent $toolRoot
$project = Join-Path $toolRoot 'tag-catalog-codegen\TagCatalogCodegen.csproj'
$generatorArguments = @('--repo-root', $repositoryRoot)
if ($Check) {
    $generatorArguments += '--check'
}

& dotnet run --project $project --configuration Release -- @generatorArguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

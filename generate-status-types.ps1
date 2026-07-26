param(
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Join-Path $repositoryRoot '60_tool\status-catalog-codegen\StatusCatalogCodegen.csproj'
$generatorArguments = @('--repo-root', $repositoryRoot)
if ($Check) {
    $generatorArguments += '--check'
}

& dotnet run --project $project --configuration Release -- @generatorArguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

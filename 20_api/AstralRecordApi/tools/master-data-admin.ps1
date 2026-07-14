[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('list', 'get', 'put', 'delete', 'sync')]
    [string] $Action,
    [string] $BaseUrl = 'https://localhost:5001',
    [Parameter(Mandatory = $true)]
    [string] $ApiKey,
    [string] $Path,
    [string] $ContentFile,
    [ValidateSet('diff', 'rebuild')]
    [string] $Mode = 'diff'
)

$headers = @{ 'X-Api-Key' = $ApiKey }
$endpoint = "$BaseUrl/api/master-data"

switch ($Action) {
    'list' {
        $query = if ($Path) { "?directory=$([uri]::EscapeDataString($Path))" } else { '' }
        Invoke-RestMethod -Uri "$endpoint/files$query" -Headers $headers -Method Get
    }
    'get' {
        if (-not $Path) { throw 'Path is required for get.' }
        Invoke-RestMethod -Uri "$endpoint/files/$([uri]::EscapeDataString($Path))" -Headers $headers -Method Get
    }
    'put' {
        if (-not $Path -or -not $ContentFile) { throw 'Path and ContentFile are required for put.' }
        $body = @{ content = Get-Content -LiteralPath $ContentFile -Raw } | ConvertTo-Json -Depth 10
        Invoke-RestMethod -Uri "$endpoint/files/$([uri]::EscapeDataString($Path))" -Headers $headers -Method Put -ContentType 'application/json' -Body $body
    }
    'delete' {
        if (-not $Path) { throw 'Path is required for delete.' }
        Invoke-RestMethod -Uri "$endpoint/files/$([uri]::EscapeDataString($Path))" -Headers $headers -Method Delete
    }
    'sync' {
        Invoke-RestMethod -Uri "$endpoint/seed?mode=$Mode" -Headers $headers -Method Post
    }
}

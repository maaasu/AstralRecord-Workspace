# Diagnostic output is deliberately separate from recovery/transaction state.
function New-MaintenanceDiagnosticLog([string]$RunDirectory, [string]$Role) {
    $path = Join-Path $RunDirectory ("diagnostics-{0}-{1}-{2}.jsonl" -f $Role,$PID,[guid]::NewGuid().ToString('N'))
    Write-Host "Diagnostic log: $path"
    return $path
}

function Write-MaintenanceDiagnostic {
    param([string]$Event, [hashtable]$Data = @{}, [System.Management.Automation.ErrorRecord]$Failure)
    # Dynamic scope keeps concurrent calls and nested workflow/deploy runs isolated.
    $context = Get-Variable -Name maintenanceDiagnosticFile -ErrorAction SilentlyContinue
    if (!$context -or !$context.Value) { return }
    try {
        $record = [ordered]@{ utc=[DateTime]::UtcNow.ToString('o'); pid=$PID; event=$Event; data=$Data }
        if ($Failure) {
            # Never serialize exception messages, invocation text, target objects, headers,
            # responses or config: even nested HTTP exceptions may contain credentials.
            $chain = @(); $exception = $Failure.Exception
            while ($exception) {
                $chain += @{ type=$exception.GetType().FullName; hresult=$exception.HResult }
                $exception = $exception.InnerException
            }
            $record.error = @{ exceptions=$chain; category=[string]$Failure.CategoryInfo.Category
                script=$Failure.InvocationInfo.ScriptName; line=$Failure.InvocationInfo.ScriptLineNumber
                column=$Failure.InvocationInfo.OffsetInLine; stack=$Failure.ScriptStackTrace }
        }
        # Open/append/close on each event, independent of console redirection or Tee-Object.
        [IO.File]::AppendAllText($context.Value, (($record | ConvertTo-Json -Depth 12 -Compress) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
    } catch {
        # Diagnostics must not turn a successful file swap into a failed transaction.
        Write-Warning 'Diagnostic log write failed; transaction state is unchanged.' -WarningAction Continue
    }
}

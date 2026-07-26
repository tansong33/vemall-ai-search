$ErrorActionPreference = 'Stop'
$scripts = $PSScriptRoot
$actions = @{
    'AI Search Server Startup' = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}"' -f `
            (Join-Path $scripts 'start-server.ps1'))
    'AI Search Daily Wake 0500 Beijing' = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}"' -f `
            (Join-Path $scripts 'resume-server.ps1'))
    'AI Search Maintenance Timezone Sync' = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}" -Quiet' -f `
            (Join-Path $scripts 'update-maintenance-schedule.ps1'))
}

foreach ($entry in $actions.GetEnumerator()) {
    if (Get-ScheduledTask -TaskName $entry.Key -ErrorAction SilentlyContinue) {
        Set-ScheduledTask -TaskName $entry.Key -Action $entry.Value | Out-Null
        Write-Output "Updated task action: $($entry.Key)"
    }
}

& (Join-Path $scripts 'update-maintenance-schedule.ps1')

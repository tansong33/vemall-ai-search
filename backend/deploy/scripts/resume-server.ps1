$logDirectory = 'E:\ai-search-next-data\logs\host'
$logFile = Join-Path $logDirectory 'maintenance.log'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
Add-Content -LiteralPath $logFile `
    -Value ('{0:u} Beijing 05:00 wake trigger ran.' -f (Get-Date)) -Encoding UTF8

& (Join-Path $PSScriptRoot 'start-server.ps1')

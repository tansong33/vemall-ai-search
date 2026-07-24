param(
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$beijingZone = [System.TimeZoneInfo]::FindSystemTimeZoneById('China Standard Time')
$beijingNow = [System.TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $beijingZone)
$schedules = @(
    @{ TaskName = 'AI Search Daily Hibernate 0400 Beijing'; Hour = 4 },
    @{ TaskName = 'AI Search Daily Wake 0500 Beijing'; Hour = 5 }
)

foreach ($schedule in $schedules) {
    $task = Get-ScheduledTask -TaskName $schedule.TaskName -ErrorAction SilentlyContinue
    if (-not $task) { continue }
    $beijingTarget = $beijingNow.Date.AddHours($schedule.Hour)
    if ($beijingTarget -le $beijingNow) { $beijingTarget = $beijingTarget.AddDays(1) }
    $unspecified = [DateTime]::SpecifyKind($beijingTarget, [DateTimeKind]::Unspecified)
    $localTarget = ([System.TimeZoneInfo]::ConvertTimeToUtc(
        $unspecified, $beijingZone)).ToLocalTime()
    Set-ScheduledTask -TaskName $schedule.TaskName `
        -Trigger (New-ScheduledTaskTrigger -Daily -At $localTarget) | Out-Null
    if (-not $Quiet) {
        Write-Output ('{0}: Beijing {1:yyyy-MM-dd HH:mm}, local {2:yyyy-MM-dd HH:mm zzz}' -f `
            $schedule.TaskName, $beijingTarget, $localTarget)
    }
}

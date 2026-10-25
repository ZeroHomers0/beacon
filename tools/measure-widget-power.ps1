param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][ValidateSet('Start','End')][string]$Phase,
    [Parameter(Mandatory=$true)][ValidateSet('no-widget','one-widget','four-widgets','download','idle-night','weak-network')][string]$Scenario,
    [string]$Package='io.github.beacon.app.debug',
    [string]$Adb='adb',
    [string]$OutputDirectory='artifacts/widget-power'
)
$ErrorActionPreference='Stop'
if($Serial -like 'emulator-*'){throw '功耗对照要求真机；模拟器结果不能作为耗电结论。'}
if($Package -notmatch '^[a-zA-Z0-9_.]+$'){throw '应用包名无效'}
$directory=Join-Path $OutputDirectory $Scenario
New-Item -ItemType Directory -Force -Path $directory | Out-Null
function Save-DeviceOutput([string]$Name,[string[]]$Arguments){
    $result=& $Adb -s $Serial @Arguments 2>&1
    if($LASTEXITCODE -ne 0){throw "ADB采集失败：$Name"}
    $result | Set-Content -LiteralPath (Join-Path $directory "$Phase-$Name.txt") -Encoding utf8
}
Save-DeviceOutput 'battery' @('shell','dumpsys','battery')
Save-DeviceOutput 'app-batterystats' @('shell','dumpsys','batterystats',$Package)
Save-DeviceOutput 'power' @('shell','dumpsys','power')
Save-DeviceOutput 'app-processes' @('shell','dumpsys','procstats','--hours','3',$Package)
Save-DeviceOutput 'app-memory' @('shell','dumpsys','meminfo',$Package)
@{phase=$Phase;scenario=$Scenario;serial=$Serial;package=$Package;captured_at=(Get-Date).ToUniversalTime().ToString('o')} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $directory "$Phase-metadata.json") -Encoding utf8
Write-Output "已采集 $Scenario / $Phase，目录：$directory。此脚本不修改电池状态、不重置系统统计。"

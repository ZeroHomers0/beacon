param(
    [switch]$DeviceTests,
    [switch]$Lint,
    [string]$TestClass = ""
)
$ErrorActionPreference = "Stop"
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../android"))
$socketDirectory = Join-Path $androidRoot ".gradle/sockets"
New-Item -ItemType Directory -Force -Path $socketDirectory | Out-Null
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    # Windows JDK NIO pipes use Unix sockets. A short path avoids sandbox TEMP path failures.
    $socketOption = '-Djdk.net.unixdomain.tmpdir="' + $socketDirectory.Replace('\', '/') + '"'
    $env:JAVA_TOOL_OPTIONS = (($previousJavaOptions, $socketOption) -join ' ').Trim()
    $tasks = @(':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:testDebugUnitTest')
    $extra = @()
    if ($Lint) { $tasks += ':app:lintDebug' }
    if ($DeviceTests) {
        $tasks += ':app:connectedDebugAndroidTest'
        if (-not $TestClass) {
            $uiClasses = @('ReceptionPageOrderTest', 'SettingsStableLayoutTest', 'SettingsActionsLayoutTest', 'SettingsBackupPanelTest',
                'StorageSettingsTest', 'SettingsInteractionRegressionTest', 'SettingsVisualQaTest',
                'AdminSettingsLayoutTest', 'ReceiveRulesCompactTest', 'TimePreferenceTest',
                'ConfigurationDialogsTest', 'BeaconDialogTest')
            $TestClass = ($uiClasses | ForEach-Object { "io.github.beacon.app.$_" }) -join ','
        }
        if ($TestClass) { $extra += "-Pandroid.testInstrumentationRunnerArguments.class=$TestClass" }
    }
    & (Join-Path $androidRoot 'gradlew.bat') -p $androidRoot @tasks @extra --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Android verification failed with exit code $LASTEXITCODE" }
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
}

param([string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk")
$ErrorActionPreference = 'Stop'
$avdName = 'Narrio_Fold_QA_API_36'
$avdDir = Join-Path $env:USERPROFILE ".android\avd\$avdName.avd"
$avdIni = Join-Path $env:USERPROFILE ".android\avd\$avdName.ini"
if (-not (Test-Path -LiteralPath $avdDir)) {
    New-Item -ItemType Directory -Path $avdDir | Out-Null
    $config = [ordered]@{
        AvdId=$avdName; 'avd.ini.displayname'='Narrio Fold QA API 36'; 'avd.ini.encoding'='UTF-8'
        'abi.type'='x86_64'; 'hw.cpu.arch'='x86_64'; 'hw.cpu.ncore'='4'; 'hw.ramSize'='3072'
        'hw.device.name'='foldable'; 'hw.device.manufacturer'='Generic'; 'hw.lcd.width'='1848'; 'hw.lcd.height'='2448'; 'hw.lcd.density'='360'
        'hw.gpu.enabled'='yes'; 'hw.gpu.mode'='auto'; 'hw.keyboard'='yes'; 'hw.battery'='yes'; 'hw.mainKeys'='no'
        'hw.accelerometer'='yes'; 'hw.gyroscope'='yes'; 'hw.sensors.orientation'='yes'; 'hw.audioInput'='no'
        'hw.sensor.hinge'='yes'; 'hw.sensor.hinge.count'='1'; 'hw.sensor.hinge.type'='1'; 'hw.sensor.hinge.sub_type'='0'
        'hw.sensor.hinge.ranges'='0-180'; 'hw.sensor.hinge.defaults'='180'; 'hw.sensor.hinge.areas'='924-0-0-2448'
        'hw.sensor.posture_list'='1,2,3'; 'hw.sensor.hinge_angles_posture_definitions'='0-30,30-150,150-180'
        'hw.sensor.hinge.fold_to_displayRegion.0.1_at_posture'='1'
        'hw.displayRegion.0.1.xOffset'='0'; 'hw.displayRegion.0.1.yOffset'='0'; 'hw.displayRegion.0.1.width'='1248'; 'hw.displayRegion.0.1.height'='1972'
        'image.sysdir.1'=(Join-Path $AndroidSdk 'system-images\android-36\google_apis_playstore\x86_64\')
        'tag.id'='google_apis_playstore'; 'tag.display'='Google Play'; 'PlayStore.enabled'='true'
        'disk.dataPartition.size'='6442450944'; 'skin.dynamic'='yes'; 'skin.name'='1848x2448'; 'showDeviceFrame'='no'
        'fastboot.forceColdBoot'='yes'; 'fastboot.forceFastBoot'='no'; 'runtime.network.latency'='none'; 'runtime.network.speed'='full'
    }
    $config.GetEnumerator() | ForEach-Object { "$($_.Key) = $($_.Value)" } | Set-Content -LiteralPath (Join-Path $avdDir 'config.ini')
    @('avd.ini.encoding=UTF-8',"path=$avdDir","path.rel=avd\$avdName.avd",'target=android-36') | Set-Content -LiteralPath $avdIni
}
$logDir = Join-Path $PSScriptRoot '..\verification'
Start-Process -FilePath (Join-Path $AndroidSdk 'emulator\emulator.exe') -ArgumentList @('-avd',$avdName,'-port','5556','-no-window','-no-boot-anim','-no-snapshot-save','-gpu','auto') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDir 'fold-emulator.log') -RedirectStandardError (Join-Path $logDir 'fold-emulator-error.log')
Write-Output "$avdName started on emulator-5556. This is a generic foldable QA profile, not physical Samsung hardware."

param([string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk")
$ErrorActionPreference = 'Stop'
$narrioProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$narrioQaOutput = Join-Path $narrioProject 'verification\private\device-qa'
$narrioAndroidJar = Join-Path $SdkRoot 'platforms\android-36\android.jar'
$narrioBuildTools = Join-Path $SdkRoot 'build-tools\36.0.0'
New-Item -ItemType Directory -Force -Path (Join-Path $narrioQaOutput 'classes'),(Join-Path $narrioQaOutput 'dex') | Out-Null
& javac -source 17 -target 17 -classpath $narrioAndroidJar -d (Join-Path $narrioQaOutput 'classes') (Join-Path $PSScriptRoot 'DeviceChecks.java')
if ($LASTEXITCODE -ne 0) { throw 'Device controller compilation failed' }
& (Join-Path $narrioBuildTools 'd8.bat') --lib $narrioAndroidJar --output (Join-Path $narrioQaOutput 'dex') (Join-Path $narrioQaOutput 'classes\app\narrio\deviceqa\DeviceChecks.class')
if ($LASTEXITCODE -ne 0) { throw 'Device controller dex build failed' }
& (Join-Path $narrioBuildTools 'aapt2.exe') link -o (Join-Path $narrioQaOutput 'qa-unsigned.apk') -I $narrioAndroidJar --manifest (Join-Path $PSScriptRoot 'AndroidManifest.xml')
if ($LASTEXITCODE -ne 0) { throw 'Device controller package build failed' }
$narrioZipScript = @'
from pathlib import Path
from zipfile import ZipFile
import sys
root=Path(sys.argv[1])
with ZipFile(root/'qa-unsigned.apk','a') as archive:
    archive.write(root/'dex/classes.dex','classes.dex')
'@
$narrioZipScript | python -X utf8 - $narrioQaOutput
if ($LASTEXITCODE -ne 0) { throw 'Device controller package assembly failed' }
& (Join-Path $narrioBuildTools 'apksigner.bat') sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out (Join-Path $narrioQaOutput 'qa.apk') (Join-Path $narrioQaOutput 'qa-unsigned.apk')
if ($LASTEXITCODE -ne 0) { throw 'Device controller signing failed' }
Write-Output (Join-Path $narrioQaOutput 'qa.apk')

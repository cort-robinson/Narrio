$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
if ((Test-Path -LiteralPath '.signing\narrio-release.jks') -and (Test-Path -LiteralPath 'signing.properties')) {
    Write-Output 'Existing Narrio release key retained.'
    exit 0
}
if (Test-Path -LiteralPath '.signing\narrio-release.jks') { throw 'A release key exists without signing.properties; recover its credentials before continuing.' }
New-Item -ItemType Directory -Force -Path '.signing' | Out-Null
$randomBytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
$narrioPassword = [Convert]::ToHexString($randomBytes)
$env:NARRIO_SIGNING_PASSWORD = $narrioPassword
try {
    & keytool -genkeypair -noprompt -alias narrio -keyalg RSA -keysize 3072 -validity 10000 -storetype JKS -keystore '.signing\narrio-release.jks' -storepass:env NARRIO_SIGNING_PASSWORD -keypass:env NARRIO_SIGNING_PASSWORD -dname 'CN=Narrio, OU=Personal Android App, O=Narrio, C=US'
    if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed.' }
    @('storeFile=.signing/narrio-release.jks',"storePassword=$narrioPassword",'keyAlias=narrio',"keyPassword=$narrioPassword") | Set-Content -LiteralPath 'signing.properties'
    Write-Output 'Release signing configured locally. Preserve the ignored key and signing.properties for future app updates.'
} finally { Remove-Item Env:NARRIO_SIGNING_PASSWORD -ErrorAction SilentlyContinue }

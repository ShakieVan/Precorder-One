$ErrorActionPreference = "Stop"

$keystoreDir = Join-Path $env:USERPROFILE ".keystores"
New-Item -ItemType Directory -Force -Path $keystoreDir | Out-Null
$keystorePath = Join-Path $keystoreDir "precorder-one-release.jks"

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()

function New-SecureToken([int]$bytes) {
    $buffer = New-Object byte[] $bytes
    $rng.GetBytes($buffer)
    return [Convert]::ToBase64String($buffer).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

$storePass = New-SecureToken 24
$keyPass = New-SecureToken 24

# Never replace an existing key: installed apps only accept updates signed with the same key.
if (Test-Path $keystorePath) {
    throw "Keystore existiert bereits ($keystorePath). Ein neuer Schlüssel würde alle Updates unmöglich machen."
}

keytool -genkeypair -v `
  -keystore "$keystorePath" `
  -alias precorderone `
  -keyalg RSA `
  -keysize 4096 `
  -validity 10000 `
  -storepass "$storePass" `
  -keypass "$keyPass" `
  -dname "CN=Precorder-One, OU=Mobile, O=Precorder, L=Berlin, ST=Berlin, C=DE" | Out-Null

if (!(Test-Path $keystorePath)) {
    throw "Keystore creation failed."
}

$storeFileForProps = $keystorePath.Replace("\", "/")
$keyPropsPath = Join-Path (Get-Location) "key.properties"
@"
storeFile=$storeFileForProps
storePassword=$storePass
keyAlias=precorderone
keyPassword=$keyPass
"@ | Set-Content -Path $keyPropsPath -Encoding Ascii

$fingerprint = (
    keytool -list -v -keystore "$keystorePath" -alias precorderone -storepass "$storePass" |
    Select-String -Pattern "SHA256:" |
    Select-Object -First 1
).ToString().Trim()

$infoPath = Join-Path $keystoreDir "precorder-one-keepass.txt"
@"
Title: Precorder-One Release Signing
KeystorePath: $keystorePath
StorePassword: $storePass
KeyAlias: precorderone
KeyPassword: $keyPass
CertificateSHA256: $fingerprint
CreatedAt: $(Get-Date -Format o)
"@ | Set-Content -Path $infoPath -Encoding UTF8

Write-Output "KEYSTORE_PATH=$keystorePath"
Write-Output "KEY_PROPERTIES_PATH=$keyPropsPath"
Write-Output "KEEPASS_INFO_PATH=$infoPath"
Write-Output "FINGERPRINT=$fingerprint"

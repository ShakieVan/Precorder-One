# Veröffentlicht die aktuelle Version als GitHub-Release, damit der In-App-Updater sie findet.
#
#   1. Version in app/build.gradle.kts erhöhen (val appVersion = "X.Y.Z") und auf main committen.
#   2. Versionshinweise als Markdown-Datei schreiben (z. B. RELEASE_NOTES_V1.1.md).
#   3. scripts\release.ps1 -Notes RELEASE_NOTES_V1.1.md
#
# Das Skript baut die signierte APK, setzt den Tag vX.Y.Z auf den aktuellen Commit und lädt
# PrecorderOne-X.Y.Z.apk als Release-Asset hoch. GitHub berechnet dabei die SHA-256-Prüfsumme,
# die der Updater vor der Installation prüft.
param(
    [Parameter(Mandatory = $true)][string]$Notes
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

if (!(Test-Path key.properties)) { throw "key.properties fehlt. Ohne den Release-Schlüssel lassen sich Updates nicht installieren." }
if (!(Test-Path $Notes)) { throw "Versionshinweise '$Notes' nicht gefunden." }
if ((git branch --show-current) -ne "main") { throw "Releases bitte nur vom Branch main veröffentlichen." }
if (git status --porcelain --untracked-files=no) { throw "Es gibt nicht committete Änderungen. Bitte erst committen." }

$match = Select-String -Path app/build.gradle.kts -Pattern 'val appVersion = "(\d+\.\d+\.\d+)"'
if (!$match) { throw "appVersion in app/build.gradle.kts nicht gefunden." }
$version = $match.Matches[0].Groups[1].Value
$tag = "v$version"
if (git tag -l $tag) { throw "Tag $tag existiert schon. Bitte appVersion erhöhen." }

& .\gradlew.bat clean assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Build fehlgeschlagen." }
$apk = "app/build/outputs/apk/release/PrecorderOne-$version.apk"
if (!(Test-Path $apk)) { throw "$apk wurde nicht erzeugt." }

git tag -a $tag -m "Precorder-One $version"
git push origin main $tag
if ($LASTEXITCODE -ne 0) { throw "Push fehlgeschlagen." }
gh release create $tag $apk --verify-tag --title "Precorder-One $version" --notes-file $Notes
if ($LASTEXITCODE -ne 0) { throw "GitHub-Release fehlgeschlagen." }
Write-Output "Veröffentlicht: https://github.com/ShakieVan/Precorder-One/releases/tag/$tag"

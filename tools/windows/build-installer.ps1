# Builds build/release/Ghostly-Windows.exe: Compose app image + Inno Setup installer.
param([string]$Iscc = "$env:ISCC")
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\.."
Push-Location $root
try {
    & .\gradlew.bat :desktopApp:createDistributable --console=plain
    if (-not $Iscc) { $Iscc = (Get-Command iscc -ErrorAction SilentlyContinue).Source }
    if (-not $Iscc) { $Iscc = "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe" }
    $version = (Select-String -Path desktopApp\build.gradle.kts -Pattern 'packageVersion = "(.+)"').Matches[0].Groups[1].Value
    & $Iscc "/DAppVersion=$version" tools\windows\ghostly.iss
} finally { Pop-Location }

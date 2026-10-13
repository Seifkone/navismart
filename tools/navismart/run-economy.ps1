[CmdletBinding()]
param([ValidateSet('Test', 'Simulate')][string]$Mode = 'Test', [string]$Scenario)
$ErrorActionPreference = 'Stop'
$navismartRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$navismartModule = Join-Path $navismartRoot 'navismart/economy'
$navismartOutput = Join-Path $navismartRoot 'out/navismart/economy'
New-Item -ItemType Directory -Force -Path $navismartOutput | Out-Null
$navismartSources = @(Get-ChildItem (Join-Path $navismartModule 'src/main/java') -Filter '*.java' -Recurse)
if ($Mode -eq 'Test') {
  $navismartSources += @(Get-ChildItem (Join-Path $navismartModule 'src/test/java') -Filter '*.java' -Recurse)
}
$navismartJavac = (Get-Command javac -ErrorAction Stop).Source
$navismartJava = Join-Path (Split-Path $navismartJavac) 'java.exe'
$navismartArguments = Join-Path $navismartOutput 'sources.args'
$navismartCompilerArgs = @('-encoding', 'UTF-8', '--release', '17', '-d', ('"' + $navismartOutput.Replace('\', '/') + '"')) +
  @($navismartSources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
[System.IO.File]::WriteAllLines($navismartArguments, [string[]]$navismartCompilerArgs,
                              [System.Text.UTF8Encoding]::new($false))
& $navismartJavac "@$navismartArguments"
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
$navismartMain = if ($Mode -eq 'Test') { 'com.navismart.economy.EconomyTests' } else { 'com.navismart.economy.Simulator' }
$navismartRunArgs = @('-Dfile.encoding=UTF-8', '-cp', $navismartOutput, $navismartMain)
if ($Scenario) {
  if ($Mode -ne 'Simulate') { throw '-Scenario requires -Mode Simulate' }
  $navismartRunArgs += @('--scenario', (Resolve-Path -LiteralPath $Scenario).Path)
}
& $navismartJava @navismartRunArgs
if ($LASTEXITCODE -ne 0) { throw 'NaviSmart execution failed' }

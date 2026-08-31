[CmdletBinding()]
param(
    [string] $ProjectName = 'personal-memo-private-win',
    [string] $EnvFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalMemo.Common.ps1')

$layoutArguments = @{ ProjectName = $ProjectName }
if (-not [string]::IsNullOrWhiteSpace($EnvFile)) {
    $layoutArguments.EnvFile = $EnvFile
}
$layout = New-PersonalMemoLayout @layoutArguments
Assert-PersonalMemoTlsFiles -Layout $layout
$null = Assert-PersonalMemoComposeContract -Layout $layout

Write-Host 'Starting the one-time initial-account command.'
Write-Host 'Enter the account password only in the interactive backend prompt.'
$bootstrapArguments = @(Get-PersonalMemoInitialAccountComposeArguments)
Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -CommandArguments $bootstrapArguments
Write-Host 'The one-time account command exited successfully.'

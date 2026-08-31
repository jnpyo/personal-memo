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

Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -CommandArguments @('ps')
Assert-PersonalMemoCloudflarePublicTopologyInactive -Layout $layout
Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -CommandArguments @('stop')
Write-Host 'The exact personal stack was stopped. Its PostgreSQL volume was preserved.'

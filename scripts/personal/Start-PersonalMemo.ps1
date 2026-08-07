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

Write-Warning (
    'This command never changes Windows Firewall. Before opening the app from a phone, run ' +
    'scripts\personal\Enable-PersonalMemoFirewall.ps1 once in an administrator PowerShell ' +
    'and confirm that the active Wi-Fi network is Private.'
)
Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -CommandArguments @('up', '-d', '--build', '--wait')
Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -CommandArguments @('ps')

$values = Read-PersonalMemoEnvFile -Path $layout.EnvFile
$address = Get-PersonalMemoEnvValue -Values $values -Name 'PERSONAL_MEMO_HTTPS_BIND_ADDRESS'
$port = Get-PersonalMemoEnvValue -Values $values -Name 'PERSONAL_MEMO_HTTPS_PORT'
Write-Host "Personal Memo is available at https://${address}:${port}/ after the private CA is trusted on the device."

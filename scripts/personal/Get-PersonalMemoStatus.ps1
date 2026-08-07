[CmdletBinding()]
param(
    [string] $ProjectName = 'personal-memo-private-win',
    [string] $EnvFile,
    [switch] $VerifyTrustedTls
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalMemo.Common.ps1')

$layoutArguments = @{ ProjectName = $ProjectName }
if (-not [string]::IsNullOrWhiteSpace($EnvFile)) {
    $layoutArguments.EnvFile = $EnvFile
}
$layout = New-PersonalMemoLayout @layoutArguments
$null = Assert-PersonalMemoComposeContract -Layout $layout

Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -CommandArguments @('ps')
Invoke-PersonalMemoCompose -Layout $layout -IncludePersonal -CommandArguments @(
    'exec', '-T', 'backend', 'wget', '-q', '-O', '-', 'http://127.0.0.1:8080/actuator/health'
)

if ($VerifyTrustedTls) {
    $values = Read-PersonalMemoEnvFile -Path $layout.EnvFile
    $address = Get-PersonalMemoEnvValue -Values $values -Name 'PERSONAL_MEMO_HTTPS_BIND_ADDRESS'
    $port = Get-PersonalMemoEnvValue -Values $values -Name 'PERSONAL_MEMO_HTTPS_PORT'
    $response = Invoke-RestMethod -Method Get -Uri "https://${address}:${port}/api/v1/health"
    if ([string] $response.status -ne 'UP') {
        throw 'The trusted HTTPS health response was not UP.'
    }
    Write-Host 'Trusted private HTTPS health check passed.'
}

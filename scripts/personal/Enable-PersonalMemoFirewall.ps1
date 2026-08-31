[CmdletBinding()]
param(
    [string] $ProjectName = 'personal-memo-private-win',
    [string] $EnvFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalMemo.Common.ps1')

$principal = New-Object Security.Principal.WindowsPrincipal(
    [Security.Principal.WindowsIdentity]::GetCurrent()
)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from a PowerShell window opened with Run as administrator.'
}

$layoutArguments = @{ ProjectName = $ProjectName }
if (-not [string]::IsNullOrWhiteSpace($EnvFile)) {
    $layoutArguments.EnvFile = $EnvFile
}
$layout = New-PersonalMemoLayout @layoutArguments
$null = Assert-PersonalMemoComposeContract -Layout $layout
$values = Read-PersonalMemoEnvFile -Path $layout.EnvFile
$address = Get-PersonalMemoEnvValue -Values $values -Name 'PERSONAL_MEMO_HTTPS_BIND_ADDRESS'
$port = Get-PersonalMemoEnvValue -Values $values -Name 'PERSONAL_MEMO_HTTPS_PORT'
$ruleName = "Personal Memo private HTTPS ($ProjectName)"

$existing = @(Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)
if ($existing.Count -gt 1) {
    throw "Refusing ambiguous firewall rules named: $ruleName"
}
if ($existing.Count -eq 1) {
    $rule = $existing[0]
    $portFilter = $rule | Get-NetFirewallPortFilter
    $addressFilter = $rule | Get-NetFirewallAddressFilter
    $profileNames = @([string] $rule.Profile -split ',') | ForEach-Object { $_.Trim() }
    $remoteAddresses = @($addressFilter.RemoteAddress | ForEach-Object { [string] $_ })
    $localAddresses = @($addressFilter.LocalAddress | ForEach-Object { [string] $_ })
    $matches =
        [string] $rule.Direction -eq 'Inbound' -and
        [string] $rule.Action -eq 'Allow' -and
        [string] $rule.Enabled -eq 'True' -and
        $profileNames -contains 'Private' -and
        [string] $portFilter.Protocol -eq 'TCP' -and
        [string] $portFilter.LocalPort -eq $port -and
        $localAddresses -contains $address -and
        $remoteAddresses -contains 'LocalSubnet'
    if (-not $matches) {
        throw 'An existing rule has the expected name but a different scope. Refusing to modify it.'
    }
    Write-Host "The scoped firewall rule already exists: $ruleName"
    return
}

New-NetFirewallRule `
    -DisplayName $ruleName `
    -Description 'Allow the Personal Memo PWA only from the private local subnet.' `
    -Direction Inbound `
    -Action Allow `
    -Enabled True `
    -Profile Private `
    -Protocol TCP `
    -LocalAddress $address `
    -LocalPort $port `
    -RemoteAddress LocalSubnet | Out-Null

Write-Host "Created private-profile LocalSubnet firewall rule for ${address}:${port}."
Write-Host 'No public profile or router port-forwarding rule was created.'

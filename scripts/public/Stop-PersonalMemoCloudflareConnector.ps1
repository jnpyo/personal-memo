#Requires -Version 5.1
#Requires -RunAsAdministrator

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$serviceName = 'PersonalMemoCalendarCloudflareTunnel'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this rollback script from an elevated Windows PowerShell session.'
}

function Assert-OnlyReviewedAppConnectorMayRemain {
    $allowedProcessId = $null
    $appService = Get-Service -Name 'PersonalMemoAppCloudflareTunnel' -ErrorAction SilentlyContinue
    if ($null -ne $appService -and
        $appService.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
        if ($appService.Status -ne [ServiceProcess.ServiceControllerStatus]::Running) {
            throw 'The app Cloudflare service is in a transitional state.'
        }
        $serviceInstance = Get-CimInstance `
            -ClassName Win32_Service `
            -Filter "Name='PersonalMemoAppCloudflareTunnel'" `
            -ErrorAction Stop
        if ($null -eq $serviceInstance -or [int]$serviceInstance.ProcessId -le 0) {
            throw 'The running app Cloudflare service has no verifiable process.'
        }
        $allowedProcessId = [int]$serviceInstance.ProcessId
    }

    $cloudflaredProcesses = @(Get-Process -Name 'cloudflared' -ErrorAction SilentlyContinue)
    try {
        foreach ($process in $cloudflaredProcesses) {
            if ($null -eq $allowedProcessId -or [int]$process.Id -ne $allowedProcessId) {
                throw 'An unknown cloudflared process remains after the calendar connector stopped.'
            }
        }
        if ($null -ne $allowedProcessId -and
            @($cloudflaredProcesses | Where-Object { [int]$_.Id -eq $allowedProcessId }).Count -ne 1) {
            throw 'The reviewed app connector process could not be matched exactly.'
        }
    } finally {
        foreach ($process in $cloudflaredProcesses) {
            $process.Dispose()
        }
    }
}

$service = Get-Service -Name $serviceName -ErrorAction Stop
if ($service.Status -eq [ServiceProcess.ServiceControllerStatus]::Stopped) {
    Write-Host 'The Cloudflare calendar connector is already stopped.'
    Assert-OnlyReviewedAppConnectorMayRemain
    Write-Host 'Verify that no successful feed response remains through Cloudflare.'
    return
}

if ($PSCmdlet.ShouldProcess($serviceName, 'Stop the public calendar connector first')) {
    Stop-Service -Name $serviceName -Force
    $service.WaitForStatus([ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(45))
    Assert-OnlyReviewedAppConnectorMayRemain
    Write-Host 'This Cloudflare calendar connector is stopped.'
    Write-Host 'Verify that no successful feed response remains through Cloudflare.'
}

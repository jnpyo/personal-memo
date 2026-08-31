#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $StopMarkerPath,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $OutputPath,

    [Parameter()]
    [ValidateNotNullOrEmpty()]
    [string] $Endpoint = 'http://127.0.0.1:11435',

    [Parameter()]
    [ValidateNotNullOrEmpty()]
    [string] $ExpectedModel = 'hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedEndpoint = 'http://127.0.0.1:11435'
$expectedModelName = 'hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0'
$sampleIntervalMilliseconds = 250
$httpTimeoutMilliseconds = 1500
$maximumResponseBytes = 262144

function Get-ExactFullPath {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $Purpose
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw ($Purpose + ' path must not be empty.')
    }

    try {
        return [IO.Path]::GetFullPath($Path)
    } catch {
        throw ($Purpose + ' path is not valid.')
    }
}

function Get-NvidiaAggregateSample {
    param([Parameter(Mandatory = $true)][string] $ExecutablePath)

    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $ExecutablePath
    $startInfo.Arguments = '--query-gpu=memory.used,utilization.gpu --format=csv,noheader,nounits'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    $standardOutputTask = $null
    $standardErrorTask = $null
    try {
        if (-not $process.Start()) {
            throw 'The bounded GPU sampler process did not start.'
        }

        $standardOutputTask = $process.StandardOutput.ReadToEndAsync()
        $standardErrorTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(2000)) {
            try {
                $process.Kill()
            } catch {
                # The process may have exited between the timeout and the owned-process kill.
            }
            try {
                $process.WaitForExit(500)
            } catch {
                # The caller records this polling cycle as a miss.
            }
            throw 'The bounded GPU sample timed out.'
        }

        $tasks = @($standardOutputTask, $standardErrorTask)
        if (-not [Threading.Tasks.Task]::WaitAll($tasks, 1000)) {
            throw 'The bounded GPU sample streams did not close in time.'
        }
        if ($process.ExitCode -ne 0) {
            throw 'The GPU sampler returned a non-success status.'
        }

        $rawOutput = $standardOutputTask.Result
        if ([string]::IsNullOrWhiteSpace($rawOutput) -or $rawOutput.Length -gt 4096) {
            throw 'The GPU sampler returned an invalid bounded result.'
        }

        [long] $totalUsedMiB = 0
        [int] $maximumUtilization = 0
        [int] $rowCount = 0
        foreach ($line in ($rawOutput -split "`r?`n")) {
            if ([string]::IsNullOrWhiteSpace($line)) {
                continue
            }
            if ($line -cnotmatch '^\s*([0-9]+)\s*,\s*([0-9]+)\s*$') {
                throw 'The GPU sampler returned an unexpected aggregate row.'
            }

            [long] $usedMiB = 0
            [int] $utilization = 0
            if (-not [long]::TryParse(
                    $Matches[1],
                    [Globalization.NumberStyles]::None,
                    [Globalization.CultureInfo]::InvariantCulture,
                    [ref] $usedMiB
                ) -or
                -not [int]::TryParse(
                    $Matches[2],
                    [Globalization.NumberStyles]::None,
                    [Globalization.CultureInfo]::InvariantCulture,
                    [ref] $utilization
                )) {
                throw 'The GPU sampler returned a non-numeric aggregate row.'
            }
            if ($usedMiB -lt 0 -or $usedMiB -gt 262144 -or
                $utilization -lt 0 -or $utilization -gt 100) {
                throw 'The GPU sampler returned an out-of-range aggregate row.'
            }

            $totalUsedMiB += $usedMiB
            if ($totalUsedMiB -gt 262144) {
                throw 'The device-wide GPU memory aggregate is out of range.'
            }
            if ($utilization -gt $maximumUtilization) {
                $maximumUtilization = $utilization
            }
            $rowCount++
        }

        if ($rowCount -lt 1) {
            throw 'The GPU sampler returned no aggregate rows.'
        }

        return [PSCustomObject][ordered]@{
            UsedMiB = [int] $totalUsedMiB
            UtilizationPercent = $maximumUtilization
        }
    } finally {
        if ($null -ne $process) {
            $process.Dispose()
        }
    }
}

function Read-BoundedOllamaProcessAggregate {
    param(
        [Parameter(Mandatory = $true)][Uri] $RequestUri,
        [Parameter(Mandatory = $true)][string] $ModelName
    )

    $request = $null
    $response = $null
    $responseStream = $null
    $memoryStream = $null
    try {
        $request = [Net.HttpWebRequest]::Create($RequestUri)
        $request.Method = 'GET'
        $request.Accept = 'application/json'
        $request.Proxy = $null
        $request.AllowAutoRedirect = $false
        $request.KeepAlive = $false
        $request.UseDefaultCredentials = $false
        $request.Credentials = $null
        $request.MaximumResponseHeadersLength = 32
        $request.Timeout = $httpTimeoutMilliseconds
        $request.ReadWriteTimeout = $httpTimeoutMilliseconds

        $response = $request.GetResponse()
        if ([int] $response.StatusCode -ne 200) {
            throw 'The owned Ollama process inventory returned a non-success status.'
        }
        if ($response.ContentLength -gt $maximumResponseBytes) {
            throw 'The owned Ollama process inventory exceeded the response limit.'
        }

        $responseStream = $response.GetResponseStream()
        $memoryStream = New-Object IO.MemoryStream
        $buffer = New-Object byte[] 4096
        [int] $totalBytes = 0
        while ($true) {
            $read = $responseStream.Read($buffer, 0, $buffer.Length)
            if ($read -le 0) {
                break
            }
            $totalBytes += $read
            if ($totalBytes -gt $maximumResponseBytes) {
                throw 'The owned Ollama process inventory exceeded the response limit.'
            }
            $memoryStream.Write($buffer, 0, $read)
        }
        if ($totalBytes -lt 2) {
            throw 'The owned Ollama process inventory returned an empty response.'
        }

        $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
        $jsonText = $strictUtf8.GetString($memoryStream.ToArray())
        try {
            $payload = $jsonText | ConvertFrom-Json -ErrorAction Stop
        } catch {
            throw 'The owned Ollama process inventory returned invalid JSON.'
        } finally {
            $jsonText = $null
        }

        $modelsProperty = $payload.PSObject.Properties['models']
        if ($null -eq $modelsProperty) {
            throw 'The owned Ollama process inventory omitted the models collection.'
        }

        $models = @()
        if ($null -ne $modelsProperty.Value) {
            $models = @($modelsProperty.Value)
        }

        [bool] $observed = $false
        [long] $maximumVramBytes = 0
        [int] $maximumContextLength = 0
        foreach ($model in $models) {
            if ($null -eq $model) {
                continue
            }

            $nameProperty = $model.PSObject.Properties['name']
            $modelProperty = $model.PSObject.Properties['model']
            $candidateName = $null
            $candidateModel = $null
            if ($null -ne $nameProperty -and $null -ne $nameProperty.Value) {
                $candidateName = [string] $nameProperty.Value
            }
            if ($null -ne $modelProperty -and $null -ne $modelProperty.Value) {
                $candidateModel = [string] $modelProperty.Value
            }
            if ($candidateName -cne $ModelName -and $candidateModel -cne $ModelName) {
                continue
            }

            $vramProperty = $model.PSObject.Properties['size_vram']
            $contextProperty = $model.PSObject.Properties['context_length']
            if ($null -eq $vramProperty -or $null -eq $contextProperty) {
                throw 'The expected model entry omitted aggregate resource fields.'
            }

            [long] $vramBytes = 0
            [int] $contextLength = 0
            if (-not [long]::TryParse(
                    [Convert]::ToString($vramProperty.Value, [Globalization.CultureInfo]::InvariantCulture),
                    [Globalization.NumberStyles]::Integer,
                    [Globalization.CultureInfo]::InvariantCulture,
                    [ref] $vramBytes
                ) -or
                -not [int]::TryParse(
                    [Convert]::ToString($contextProperty.Value, [Globalization.CultureInfo]::InvariantCulture),
                    [Globalization.NumberStyles]::Integer,
                    [Globalization.CultureInfo]::InvariantCulture,
                    [ref] $contextLength
                ) -or
                $vramBytes -lt 1 -or $contextLength -lt 1) {
                throw 'The expected model entry contained invalid aggregate resource fields.'
            }

            $observed = $true
            if ($vramBytes -gt $maximumVramBytes) {
                $maximumVramBytes = $vramBytes
            }
            if ($contextLength -gt $maximumContextLength) {
                $maximumContextLength = $contextLength
            }
        }

        return [PSCustomObject][ordered]@{
            Observed = $observed
            MaxVramBytes = $maximumVramBytes
            MaxContextLength = $maximumContextLength
        }
    } finally {
        if ($null -ne $memoryStream) {
            $memoryStream.Dispose()
        }
        if ($null -ne $responseStream) {
            $responseStream.Dispose()
        }
        if ($null -ne $response) {
            $response.Dispose()
        }
        if ($null -ne $request) {
            $request.Abort()
        }
    }
}

if ($Endpoint -cne $expectedEndpoint) {
    throw 'Only the owned loopback Ollama endpoint is accepted.'
}
if ($ExpectedModel -cne $expectedModelName) {
    throw 'Only the exact qualified LiquidAI model is accepted.'
}

$stopMarkerFullPath = Get-ExactFullPath -Path $StopMarkerPath -Purpose 'Stop marker'
$outputFullPath = Get-ExactFullPath -Path $OutputPath -Purpose 'Output'
if ($stopMarkerFullPath.Equals($outputFullPath, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The stop marker and output paths must be distinct.'
}
$stopParent = [IO.Path]::GetDirectoryName($stopMarkerFullPath)
$outputParent = [IO.Path]::GetDirectoryName($outputFullPath)
if (-not [IO.Directory]::Exists($stopParent) -or -not [IO.Directory]::Exists($outputParent)) {
    throw 'The exact stop marker and output parent directories must already exist.'
}
if ([IO.File]::Exists($stopMarkerFullPath)) {
    throw 'The exact stop marker must not exist when sampling starts.'
}
if ([IO.File]::Exists($outputFullPath) -or [IO.Directory]::Exists($outputFullPath)) {
    throw 'The exact aggregate output path must be unused when sampling starts.'
}

$endpointUri = New-Object Uri(($Endpoint + '/api/ps'), [UriKind]::Absolute)
if ($endpointUri.Scheme -cne 'http' -or
    $endpointUri.Host -cne '127.0.0.1' -or
    $endpointUri.Port -ne 11435 -or
    $endpointUri.AbsolutePath -cne '/api/ps' -or
    $endpointUri.Query.Length -ne 0 -or
    $endpointUri.Fragment.Length -ne 0 -or
    $endpointUri.UserInfo.Length -ne 0) {
    throw 'The owned Ollama process inventory URI is not exact.'
}

$nvidiaCommand = Get-Command 'nvidia-smi.exe' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $nvidiaCommand) {
    $nvidiaCommand = Get-Command 'nvidia-smi' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
}
if ($null -eq $nvidiaCommand) {
    throw 'The bounded NVIDIA sampler executable is unavailable.'
}
$nvidiaExecutablePath = [IO.Path]::GetFullPath($nvidiaCommand.Source)
if (-not [IO.File]::Exists($nvidiaExecutablePath)) {
    throw 'The bounded NVIDIA sampler executable path is invalid.'
}

[int] $sampleCount = 0
[int] $sampleMissCount = 0
[int] $successfulGpuSampleCount = 0
$baselineUsedMiB = $null
[int] $maximumUsedMiB = 0
$postUsedMiB = $null
[int] $maximumUtilizationPercent = 0
[bool] $loadedModelObserved = $false
[long] $maximumOllamaVramBytes = 0
[int] $maximumContextLength = 0

do {
    [bool] $cycleMissed = $false
    try {
        $gpuSample = Get-NvidiaAggregateSample -ExecutablePath $nvidiaExecutablePath
        if ($null -eq $baselineUsedMiB) {
            $baselineUsedMiB = [int] $gpuSample.UsedMiB
        }
        $postUsedMiB = [int] $gpuSample.UsedMiB
        if ($gpuSample.UsedMiB -gt $maximumUsedMiB) {
            $maximumUsedMiB = [int] $gpuSample.UsedMiB
        }
        if ($gpuSample.UtilizationPercent -gt $maximumUtilizationPercent) {
            $maximumUtilizationPercent = [int] $gpuSample.UtilizationPercent
        }
        $successfulGpuSampleCount++
    } catch {
        $cycleMissed = $true
    }

    try {
        $ollamaSample = Read-BoundedOllamaProcessAggregate `
            -RequestUri $endpointUri `
            -ModelName $ExpectedModel
        if ($ollamaSample.Observed) {
            $loadedModelObserved = $true
            if ($ollamaSample.MaxVramBytes -gt $maximumOllamaVramBytes) {
                $maximumOllamaVramBytes = [long] $ollamaSample.MaxVramBytes
            }
            if ($ollamaSample.MaxContextLength -gt $maximumContextLength) {
                $maximumContextLength = [int] $ollamaSample.MaxContextLength
            }
        }
    } catch {
        $cycleMissed = $true
    }

    $sampleCount++
    if ($cycleMissed) {
        $sampleMissCount++
    }

    if ([IO.File]::Exists($stopMarkerFullPath)) {
        break
    }
    Start-Sleep -Milliseconds $sampleIntervalMilliseconds
} while ($true)

if ($sampleCount -lt 1 -or $successfulGpuSampleCount -lt 1 -or
    $null -eq $baselineUsedMiB -or $null -eq $postUsedMiB) {
    throw 'No valid device-wide GPU aggregate was observed.'
}

$aggregate = [PSCustomObject][ordered]@{
    scope = 'DEVICE_WIDE_NON_EXCLUSIVE'
    sampleCount = $sampleCount
    sampleMissCount = $sampleMissCount
    baselineUsedMiB = [int] $baselineUsedMiB
    maxUsedMiB = $maximumUsedMiB
    postUsedMiB = [int] $postUsedMiB
    maxUtilizationPercent = $maximumUtilizationPercent
    loadedModelObserved = $loadedModelObserved
    maxOllamaVramBytes = $maximumOllamaVramBytes
    contextLength = $maximumContextLength
}

$aggregateJson = $aggregate | ConvertTo-Json -Depth 3 -Compress
$utf8NoBom = New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllText($outputFullPath, ($aggregateJson + "`r`n"), $utf8NoBom)

[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PersonalMemo.Common.ps1')

if ($env:OS -ne 'Windows_NT') {
    Write-Host 'UTF-8 native capture contract is Windows-only; skipped.'
    exit 0
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('personal-memo-utf8-contract-' + [Guid]::NewGuid().ToString('N'))
$fakeDocker = Join-Path $tempRoot 'docker.exe'
$previousPath = $env:PATH
$previousOutputEncoding = [Console]::OutputEncoding
$previousDockerMode = [Environment]::GetEnvironmentVariable(
    'PERSONAL_MEMO_TEST_DOCKER_MODE',
    [EnvironmentVariableTarget]::Process
)
$previousDockerCounter = [Environment]::GetEnvironmentVariable(
    'PERSONAL_MEMO_TEST_DOCKER_COUNTER',
    [EnvironmentVariableTarget]::Process
)

try {
    $null = New-Item -ItemType Directory -Path $tempRoot
    $fakeDockerSource = @'
using System;
using System.IO;
using System.Text;

public static class FakeDocker
{
    public static int Main(string[] args)
    {
        var mode = Environment.GetEnvironmentVariable("PERSONAL_MEMO_TEST_DOCKER_MODE");
        if (mode == "postgres-fail")
        {
            var input = Console.In.ReadToEnd();
            Console.Error.Write(input);
            return 17;
        }
        if (mode == "postgres-success")
        {
            Console.In.ReadToEnd();
            return 0;
        }
        if (mode == "postgres-fail-once")
        {
            var input = Console.In.ReadToEnd();
            var counter = Environment.GetEnvironmentVariable("PERSONAL_MEMO_TEST_DOCKER_COUNTER");
            if (!File.Exists(counter))
            {
                File.WriteAllText(counter, "seen");
                Console.Error.Write(input);
                return 17;
            }
            return 0;
        }
        if (mode == "postgres-double-fail")
        {
            var input = Console.In.ReadToEnd();
            Console.Error.Write(input);
            return 17;
        }
        if (Array.IndexOf(args, "fail") >= 0)
        {
            return 17;
        }

        var json = "{\"displayName\":\"\uC774\uC900\uD45C\"}";
        var bytes = Encoding.UTF8.GetBytes(json);
        using (var stdout = Console.OpenStandardOutput())
        {
            stdout.Write(bytes, 0, bytes.Length);
        }
        return 0;
    }
}
'@
    Add-Type `
        -TypeDefinition $fakeDockerSource `
        -Language CSharp `
        -OutputAssembly $fakeDocker `
        -OutputType ConsoleApplication

    $env:PATH = $tempRoot + [IO.Path]::PathSeparator + $previousPath
    [Console]::OutputEncoding = [Text.Encoding]::GetEncoding(949)

    $rawJson = Invoke-PersonalMemoDocker -Arguments @('emit') -Capture
    $result = ConvertFrom-PersonalMemoJson -Json $rawJson -Context 'UTF-8 capture contract'
    $expectedDisplayName = -join @([char] 0xC774, [char] 0xC900, [char] 0xD45C)
    if ([string] $result.displayName -cne $expectedDisplayName) {
        throw 'Native UTF-8 output did not preserve the Korean display name.'
    }
    if ([Console]::OutputEncoding.CodePage -ne 949) {
        throw 'Native capture did not restore the original console output encoding.'
    }

    $nonZeroObserved = $false
    try {
        $null = Invoke-PersonalMemoDocker -Arguments @('fail') -Capture
    } catch {
        if ($_.Exception.Message -cne 'Docker command failed with exit code 17.') {
            throw
        }
        $nonZeroObserved = $true
    }
    if (-not $nonZeroObserved) {
        throw 'The fake Docker failure was not propagated.'
    }
    if ([Console]::OutputEncoding.CodePage -ne 949) {
        throw 'A failed native capture did not restore the original console output encoding.'
    }

    $historyMarker = 'preexisting-error-history-must-remain'
    try {
        throw $historyMarker
    } catch {
        # The JSON sanitizer must remove only its own credential-bearing parser error.
    }
    $sentinel = 'compose-secret-must-not-appear'
    $sanitizedFailureObserved = $false
    try {
        $null = ConvertFrom-PersonalMemoJson `
            -Json ('{"password":"' + $sentinel + '"') `
            -Context 'Sanitized parse contract'
    } catch {
        if ($_.Exception.Message.Contains($sentinel)) {
            throw 'Invalid JSON exposed its raw credential-bearing input.'
        }
        $sanitizedFailureObserved = $true
    }
    if (-not $sanitizedFailureObserved) {
        throw 'The invalid JSON contract did not fail as expected.'
    }
    foreach ($errorRecord in $Error) {
        if (($errorRecord | Out-String).Contains($sentinel)) {
            throw 'PowerShell error history retained credential-bearing invalid JSON.'
        }
    }
    if (@($Error | Where-Object { $_.Exception.Message -ceq $historyMarker }).Count -ne 1) {
        throw 'JSON sanitization removed unrelated PowerShell error history.'
    }

    $databaseIdentity = [PSCustomObject]@{
        Username = 'personal_memo_app'
        Database = 'personal_memo'
    }
    $containerId = 'a' * 64
    $sqlSentinel = "ALTER ROLE personal_memo_app PASSWORD 'postgres-input-secret-must-not-appear';"
    $env:PERSONAL_MEMO_TEST_DOCKER_MODE = 'postgres-fail'
    $inputMayHaveReachedServer = $false
    $protectedFailureObserved = $false
    try {
        Invoke-PersonalMemoPostgresInput `
            -ContainerId $containerId `
            -DatabaseIdentity $databaseIdentity `
            -Sql $sqlSentinel `
            -InputMayHaveReachedServer ([ref] $inputMayHaveReachedServer)
    } catch {
        if ($_.Exception.Message -cne 'The protected PostgreSQL standard-input operation failed.') {
            throw
        }
        $protectedFailureObserved = $true
    }
    if (-not $protectedFailureObserved -or -not $inputMayHaveReachedServer) {
        throw 'The protected PostgreSQL failure/input-start contract was not preserved.'
    }
    foreach ($errorRecord in $Error) {
        if (($errorRecord | Out-String).Contains('postgres-input-secret-must-not-appear')) {
            throw 'Protected PostgreSQL stderr entered PowerShell error history.'
        }
    }

    $env:PERSONAL_MEMO_TEST_DOCKER_MODE = 'postgres-success'
    $inputMayHaveReachedServer = $false
    Invoke-PersonalMemoPostgresInput `
        -ContainerId $containerId `
        -DatabaseIdentity $databaseIdentity `
        -Sql 'SELECT 1;' `
        -InputMayHaveReachedServer ([ref] $inputMayHaveReachedServer)
    if (-not $inputMayHaveReachedServer) {
        throw 'The successful PostgreSQL stdin contract did not report input start.'
    }

    $retryCounter = Join-Path $tempRoot 'forward-retry.count'
    $env:PERSONAL_MEMO_TEST_DOCKER_COUNTER = $retryCounter
    $env:PERSONAL_MEMO_TEST_DOCKER_MODE = 'postgres-fail-once'
    $forwardSqlSentinel = "ALTER ROLE personal_memo_app PASSWORD 'forward-retry-secret-must-not-appear';"
    $inputMayHaveReachedServer = $false
    Invoke-PersonalMemoForwardOnlyPostgresInput `
        -ContainerId $containerId `
        -DatabaseIdentity $databaseIdentity `
        -Sql $forwardSqlSentinel `
        -InputMayHaveReachedServer ([ref] $inputMayHaveReachedServer)
    if (-not $inputMayHaveReachedServer -or -not (Test-Path -LiteralPath $retryCounter -PathType Leaf)) {
        throw 'The forward-only PostgreSQL helper did not retry an ambiguous first result.'
    }

    $env:PERSONAL_MEMO_TEST_DOCKER_MODE = 'postgres-double-fail'
    $ambiguousFailureObserved = $false
    $inputMayHaveReachedServer = $false
    try {
        Invoke-PersonalMemoForwardOnlyPostgresInput `
            -ContainerId $containerId `
            -DatabaseIdentity $databaseIdentity `
            -Sql $forwardSqlSentinel `
            -InputMayHaveReachedServer ([ref] $inputMayHaveReachedServer)
    } catch {
        if (-not $_.Exception.Message.StartsWith(
            'The PostgreSQL password update result is ambiguous after protected input started.'
        )) {
            throw
        }
        $ambiguousFailureObserved = $true
    }
    if (-not $ambiguousFailureObserved -or -not $inputMayHaveReachedServer) {
        throw 'A repeated ambiguous PostgreSQL result did not preserve forward-only state.'
    }
    foreach ($errorRecord in $Error) {
        if (($errorRecord | Out-String).Contains('forward-retry-secret-must-not-appear')) {
            throw 'Forward-only PostgreSQL failure retained secret input in PowerShell error history.'
        }
    }

    $inputMayHaveReachedServer = $true
    $preInputFailureObserved = $false
    try {
        Invoke-PersonalMemoForwardOnlyPostgresInput `
            -ContainerId 'invalid-container-id' `
            -DatabaseIdentity $databaseIdentity `
            -Sql $forwardSqlSentinel `
            -InputMayHaveReachedServer ([ref] $inputMayHaveReachedServer)
    } catch {
        $preInputFailureObserved = $true
        if ($inputMayHaveReachedServer) {
            throw 'A pre-input PostgreSQL failure incorrectly entered forward-only state.'
        }
    }
    if (-not $preInputFailureObserved) {
        throw 'The pre-input PostgreSQL failure contract did not fail as expected.'
    }
} finally {
    if ($null -eq $previousDockerMode) {
        Remove-Item Env:PERSONAL_MEMO_TEST_DOCKER_MODE -ErrorAction SilentlyContinue
    } else {
        $env:PERSONAL_MEMO_TEST_DOCKER_MODE = $previousDockerMode
    }
    if ($null -eq $previousDockerCounter) {
        Remove-Item Env:PERSONAL_MEMO_TEST_DOCKER_COUNTER -ErrorAction SilentlyContinue
    } else {
        $env:PERSONAL_MEMO_TEST_DOCKER_COUNTER = $previousDockerCounter
    }
    $env:PATH = $previousPath
    [Console]::OutputEncoding = $previousOutputEncoding
    if (Test-Path -LiteralPath $tempRoot -PathType Container) {
        $resolvedTemp = [IO.Path]::GetFullPath($tempRoot)
        $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if ($resolvedTemp.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolvedTemp).StartsWith('personal-memo-utf8-contract-')) {
            Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
        }
    }
}

Write-Host 'Personal Memo Windows PowerShell UTF-8 and protected-stdin contracts are valid.'

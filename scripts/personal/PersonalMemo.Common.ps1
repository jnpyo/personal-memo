Set-StrictMode -Version Latest

$script:PersonalMemoDefaultProjectName = 'personal-memo-private-win'
$script:PersonalMemoRepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Assert-PersonalMemoCommand {
    param([Parameter(Mandatory = $true)][string] $Name)

    if ($null -eq (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command was not found: $Name"
    }
}

function Get-PersonalMemoInitialAccountComposeArguments {
    # Keep this command fixed: --build prevents a stale backend image from bypassing the current
    # one-time bootstrap implementation, while the absence of -T preserves the attached terminal.
    return [string[]] @('run', '--build', '--rm', 'backend', 'bootstrap-account')
}

function Assert-PersonalMemoProjectName {
    param(
        [Parameter(Mandatory = $true)][string] $ProjectName,
        [switch] $RestoreProject
    )

    $pattern = if ($RestoreProject) {
        '^personal-memo-restore-[0-9]{14}-[a-f0-9]{8}$'
    } else {
        '^personal-memo-private-[a-z0-9][a-z0-9-]{0,40}$'
    }

    if ($ProjectName -cnotmatch $pattern) {
        throw "Refusing unexpected Compose project name: $ProjectName"
    }
}

function New-PersonalMemoLayout {
    param(
        [string] $ProjectName = $script:PersonalMemoDefaultProjectName,
        [string] $EnvFile = (Join-Path $script:PersonalMemoRepositoryRoot '.env.personal'),
        [switch] $RestoreProject
    )

    Assert-PersonalMemoProjectName -ProjectName $ProjectName -RestoreProject:$RestoreProject

    $layout = [PSCustomObject]@{
        ProjectName = $ProjectName
        RepositoryRoot = $script:PersonalMemoRepositoryRoot
        EnvFile = [IO.Path]::GetFullPath($EnvFile)
        BaseCompose = Join-Path $script:PersonalMemoRepositoryRoot 'compose.yaml'
        ProductionCompose = Join-Path $script:PersonalMemoRepositoryRoot 'compose.prod.yaml'
        PersonalCompose = Join-Path $script:PersonalMemoRepositoryRoot 'compose.personal.yaml'
    }

    foreach ($requiredFile in @($layout.EnvFile, $layout.BaseCompose, $layout.ProductionCompose)) {
        if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
            throw "Required file was not found: $requiredFile"
        }
    }

    return $layout
}

function Read-PersonalMemoEnvFile {
    param([Parameter(Mandatory = $true)][string] $Path)

    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }

        $separator = $line.IndexOf('=')
        if ($separator -le 0) {
            throw "Invalid environment-file line in $Path"
        }

        $name = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        if ($name -notmatch '^[A-Z][A-Z0-9_]*$') {
            throw "Invalid environment variable name in $Path"
        }
        $values[$name] = $value
    }
    return $values
}

function Get-PersonalMemoEnvValue {
    param(
        [Parameter(Mandatory = $true)][hashtable] $Values,
        [Parameter(Mandatory = $true)][string] $Name,
        [switch] $AllowEmpty
    )

    if (-not $Values.ContainsKey($Name)) {
        throw "Missing required setting $Name"
    }
    $value = [string] $Values[$Name]
    if (-not $AllowEmpty -and [string]::IsNullOrWhiteSpace($value)) {
        throw "Setting $Name must not be empty"
    }
    return $value
}

function Get-PersonalMemoComposePrefix {
    param(
        [Parameter(Mandatory = $true)][PSCustomObject] $Layout,
        [switch] $IncludePersonal
    )

    $prefix = @(
        'compose', '--env-file', $Layout.EnvFile,
        '-p', $Layout.ProjectName,
        '-f', $Layout.BaseCompose,
        '-f', $Layout.ProductionCompose
    )
    if ($IncludePersonal) {
        if (-not (Test-Path -LiteralPath $Layout.PersonalCompose -PathType Leaf)) {
            throw "Personal Compose overlay was not found: $($Layout.PersonalCompose)"
        }
        $prefix += @('-f', $Layout.PersonalCompose)
    }
    return $prefix
}

function Invoke-PersonalMemoDocker {
    param(
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [switch] $Capture
    )

    Assert-PersonalMemoCommand -Name 'docker'
    if ($Capture) {
        # Docker writes UTF-8 on Windows. Windows PowerShell 5.1 otherwise decodes native stdout
        # with the active console code page, which can corrupt non-ASCII Compose JSON.
        $previousOutputEncoding = [Console]::OutputEncoding
        try {
            [Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
            $output = & docker @Arguments 2>$null
            $exitCode = $LASTEXITCODE
        } finally {
            [Console]::OutputEncoding = $previousOutputEncoding
        }
        if ($exitCode -ne 0) {
            throw "Docker command failed with exit code $exitCode."
        }
        return ($output -join [Environment]::NewLine)
    }

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed with exit code $LASTEXITCODE."
    }
}

function Invoke-PersonalMemoCompose {
    param(
        [Parameter(Mandatory = $true)][PSCustomObject] $Layout,
        [Parameter(Mandatory = $true)][string[]] $CommandArguments,
        [switch] $IncludePersonal,
        [switch] $Capture
    )

    $arguments = @(Get-PersonalMemoComposePrefix -Layout $Layout -IncludePersonal:$IncludePersonal)
    $arguments += $CommandArguments
    return Invoke-PersonalMemoDocker -Arguments $arguments -Capture:$Capture
}

function Invoke-PersonalMemoPostgresInput {
    param(
        [Parameter(Mandatory = $true)][string] $ContainerId,
        [Parameter(Mandatory = $true)][PSCustomObject] $DatabaseIdentity,
        [Parameter(Mandatory = $true)][string] $Sql,
        [Parameter(Mandatory = $true)][ref] $InputMayHaveReachedServer
    )

    Assert-PersonalMemoCommand -Name 'docker'
    if ($ContainerId -cnotmatch '^[a-f0-9]{12,64}$') {
        throw 'Refusing an unexpected PostgreSQL container identifier.'
    }
    foreach ($identifier in @($DatabaseIdentity.Username, $DatabaseIdentity.Database)) {
        if ([string] $identifier -cnotmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') {
            throw 'PostgreSQL database and role names must use safe identifier characters.'
        }
    }
    $arguments = @(
        'exec', '-i', $ContainerId,
        'psql', '--no-psqlrc', '--quiet',
        "--username=$($DatabaseIdentity.Username)",
        "--dbname=$($DatabaseIdentity.Database)",
        '--set=ON_ERROR_STOP=1'
    )

    # Use redirected .NET streams instead of PowerShell's native-error pipeline. Windows
    # PowerShell 5.1 can otherwise turn stderr into a NativeCommandError that retains SQL text.
    # The secret-bearing SQL reaches psql only through standard input and both output streams are
    # drained without ever entering PowerShell's error history.
    $dockerCommand = @(
        Get-Command docker.exe -CommandType Application -ErrorAction Stop
    )[0].Source
    if (-not [IO.File]::Exists($dockerCommand)) {
        throw 'The Docker executable path could not be resolved safely.'
    }
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $dockerCommand
    $startInfo.Arguments = $arguments -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    $standardOutput = $null
    $standardError = $null
    $standardOutputTask = $null
    $standardErrorTask = $null
    $InputMayHaveReachedServer.Value = $false
    try {
        if (-not $process.Start()) {
            throw 'The protected PostgreSQL process could not be started.'
        }
        $standardOutputTask = $process.StandardOutput.ReadToEndAsync()
        $standardErrorTask = $process.StandardError.ReadToEndAsync()
        $InputMayHaveReachedServer.Value = $true
        $process.StandardInput.Write($Sql)
        $process.StandardInput.Close()
        $process.WaitForExit()
        $standardOutput = $standardOutputTask.GetAwaiter().GetResult()
        $standardError = $standardErrorTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw 'The protected PostgreSQL standard-input operation failed.'
        }
    } finally {
        $standardOutput = $null
        $standardError = $null
        $standardOutputTask = $null
        $standardErrorTask = $null
        $process.Dispose()
    }
}

function Invoke-PersonalMemoForwardOnlyPostgresInput {
    param(
        [Parameter(Mandatory = $true)][string] $ContainerId,
        [Parameter(Mandatory = $true)][PSCustomObject] $DatabaseIdentity,
        [Parameter(Mandatory = $true)][string] $Sql,
        [Parameter(Mandatory = $true)][ref] $InputMayHaveReachedServer
    )

    $InputMayHaveReachedServer.Value = $false
    $firstInputStarted = $false
    try {
        Invoke-PersonalMemoPostgresInput `
            -ContainerId $ContainerId `
            -DatabaseIdentity $DatabaseIdentity `
            -Sql $Sql `
            -InputMayHaveReachedServer ([ref] $firstInputStarted)
        $InputMayHaveReachedServer.Value = $firstInputStarted
        return
    } catch {
        $InputMayHaveReachedServer.Value = $firstInputStarted
        if (-not $firstInputStarted) {
            throw
        }
    }

    # ALTER ROLE is idempotent for the same generated value. If the first result is ambiguous
    # after stdin started, retry forward rather than restoring the exposed old credential.
    $retryInputStarted = $false
    try {
        Invoke-PersonalMemoPostgresInput `
            -ContainerId $ContainerId `
            -DatabaseIdentity $DatabaseIdentity `
            -Sql $Sql `
            -InputMayHaveReachedServer ([ref] $retryInputStarted)
        $InputMayHaveReachedServer.Value = $true
    } catch {
        $InputMayHaveReachedServer.Value = $firstInputStarted -or $retryInputStarted
        throw (
            'The PostgreSQL password update result is ambiguous after protected input started. ' +
            'The new environment value must be retained and the operation retried forward.'
        )
    }
}

function Get-PersonalMemoObjectProperty {
    param(
        [Parameter(Mandatory = $true)] $Object,
        [Parameter(Mandatory = $true)][string] $Name
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function ConvertFrom-PersonalMemoJson {
    param(
        [Parameter(Mandatory = $true)][string] $Json,
        [Parameter(Mandatory = $true)][string] $Context
    )

    try {
        return ConvertFrom-Json -InputObject $Json -ErrorAction Stop
    } catch {
        # Compose configuration can contain database and provider credentials. Never let
        # ConvertFrom-Json include its raw input in a user-visible error record or retain that
        # credential-bearing parser record in PowerShell's automatic error history.
        $parserError = $_
        for ($index = $Error.Count - 1; $index -ge 0; $index--) {
            if ([Object]::ReferenceEquals($Error[$index].Exception, $parserError.Exception)) {
                $Error.RemoveAt($index)
            }
        }
        $parserError = $null
        throw "$Context returned invalid JSON. Raw output was withheld because it may contain credentials."
    }
}

function Test-PersonalMemoPrivateIPv4 {
    param([Parameter(Mandatory = $true)][string] $Address)

    $parsed = $null
    if (-not [Net.IPAddress]::TryParse($Address, [ref] $parsed)) {
        return $false
    }
    if ($parsed.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) {
        return $false
    }
    $bytes = $parsed.GetAddressBytes()
    return ($bytes[0] -eq 10) -or
        ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) -or
        ($bytes[0] -eq 192 -and $bytes[1] -eq 168)
}

function Assert-PersonalMemoComposeContract {
    param([Parameter(Mandatory = $true)][PSCustomObject] $Layout)

    $rawConfig = Invoke-PersonalMemoCompose -Layout $Layout -IncludePersonal -Capture -CommandArguments @('config', '--format', 'json')
    $config = ConvertFrom-PersonalMemoJson -Json $rawConfig -Context 'Personal Compose configuration'
    if ([string] $config.name -cne $Layout.ProjectName) {
        throw "Compose resolved an unexpected project name."
    }

    foreach ($serviceName in @('postgres', 'backend', 'frontend')) {
        if ($null -eq (Get-PersonalMemoObjectProperty -Object $config.services -Name $serviceName)) {
            throw "Compose service is missing: $serviceName"
        }
    }

    $postgresPorts = @((Get-PersonalMemoObjectProperty -Object $config.services.postgres -Name 'ports') | Where-Object { $null -ne $_ })
    $backendPorts = @((Get-PersonalMemoObjectProperty -Object $config.services.backend -Name 'ports') | Where-Object { $null -ne $_ })
    if ($postgresPorts.Count -ne 0) {
        throw 'PostgreSQL must not publish a host port in personal mode.'
    }
    if ($backendPorts.Count -ne 0) {
        throw 'The backend must not publish a host port in personal mode.'
    }

    $envValues = Read-PersonalMemoEnvFile -Path $Layout.EnvFile
    $expectedHttpPort = Get-PersonalMemoEnvValue -Values $envValues -Name 'PERSONAL_MEMO_FRONTEND_PORT'
    $expectedTlsPort = Get-PersonalMemoEnvValue -Values $envValues -Name 'PERSONAL_MEMO_HTTPS_PORT'
    if ($expectedTlsPort -cne '8443') {
        throw 'PERSONAL_MEMO_HTTPS_PORT must remain 8443 for the fixed private TLS listener.'
    }
    $expectedTlsAddress = Get-PersonalMemoEnvValue -Values $envValues -Name 'PERSONAL_MEMO_HTTPS_BIND_ADDRESS'
    if (-not (Test-PersonalMemoPrivateIPv4 -Address $expectedTlsAddress)) {
        throw 'PERSONAL_MEMO_HTTPS_BIND_ADDRESS must be a private RFC1918 IPv4 address.'
    }

    $httpFound = $false
    $tlsFound = $false
    foreach ($port in @($config.services.frontend.ports)) {
        $hostIp = [string] $port.host_ip
        $published = [string] $port.published
        $target = [string] $port.target
        if ($hostIp -eq '127.0.0.1' -and $published -eq $expectedHttpPort -and $target -eq '5173') {
            $httpFound = $true
            continue
        }
        if ($hostIp -eq $expectedTlsAddress -and $published -eq $expectedTlsPort -and $target -eq '8443') {
            $tlsFound = $true
            continue
        }
        throw "Unexpected personal frontend port mapping: ${hostIp}:${published} -> $target"
    }
    if (-not $httpFound -or -not $tlsFound) {
        throw 'Personal mode requires loopback HTTP and private-LAN HTTPS mappings.'
    }

    $backendEnvironment = $config.services.backend.environment
    foreach ($disabledSetting in @('AUTH_REGISTRATION_ENABLED', 'GOOGLE_AUTH_ENABLED', 'GOOGLE_REGISTRATION_ENABLED')) {
        if ([string] (Get-PersonalMemoObjectProperty -Object $backendEnvironment -Name $disabledSetting) -ne 'false') {
            throw "$disabledSetting must be false in personal mode."
        }
    }
    foreach ($emptyGoogleSetting in @('GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'GOOGLE_REDIRECT_URI')) {
        if (-not [string]::IsNullOrEmpty([string] (Get-PersonalMemoObjectProperty -Object $backendEnvironment -Name $emptyGoogleSetting))) {
            throw "$emptyGoogleSetting must be empty in personal mode."
        }
    }
    foreach ($bootstrapSetting in @(
        'PERSONAL_MEMO_BOOTSTRAP_EMAIL',
        'PERSONAL_MEMO_BOOTSTRAP_DISPLAY_NAME',
        'PERSONAL_MEMO_BOOTSTRAP_TIME_ZONE'
    )) {
        $expectedBootstrapValue = Get-PersonalMemoEnvValue -Values $envValues -Name $bootstrapSetting -AllowEmpty
        $resolvedBootstrapValue = [string] (Get-PersonalMemoObjectProperty -Object $backendEnvironment -Name $bootstrapSetting)
        if ($resolvedBootstrapValue -cne $expectedBootstrapValue) {
            throw "Compose did not preserve private bootstrap metadata setting $bootstrapSetting."
        }
    }
    if ([string] (Get-PersonalMemoObjectProperty -Object $backendEnvironment -Name 'SPRING_PROFILES_ACTIVE') -ne 'prod') {
        throw 'Personal mode must use the Spring prod profile.'
    }
    if ([string] (Get-PersonalMemoObjectProperty -Object $backendEnvironment -Name 'SESSION_COOKIE_SECURE') -ne 'true') {
        throw 'Personal mode must issue Secure cookies.'
    }

    $volume = Get-PersonalMemoObjectProperty -Object $config.volumes -Name 'personal-memo-postgres'
    if ($null -eq $volume -or [string] $volume.name -cne "$($Layout.ProjectName)_personal-memo-postgres") {
        throw 'The PostgreSQL volume is not scoped to the exact personal project.'
    }

    foreach ($target in @(
        '/etc/nginx/personal-listeners/personal-tls.conf',
        '/run/personal-memo/tls/server-cert.pem',
        '/run/personal-memo/tls/server-key.pem'
    )) {
        $mount = @($config.services.frontend.volumes | Where-Object { [string] $_.target -eq $target })
        if ($mount.Count -ne 1 -or $mount[0].read_only -ne $true) {
            throw "TLS mount must exist exactly once and be read-only: $target"
        }
    }

    return $config
}

function Assert-PersonalMemoRestoreComposeContract {
    param([Parameter(Mandatory = $true)][PSCustomObject] $Layout)

    Assert-PersonalMemoProjectName -ProjectName $Layout.ProjectName -RestoreProject
    $rawConfig = Invoke-PersonalMemoCompose -Layout $Layout -Capture -CommandArguments @('config', '--format', 'json')
    $config = ConvertFrom-PersonalMemoJson -Json $rawConfig -Context 'Restore Compose configuration'
    if ([string] $config.name -cne $Layout.ProjectName) {
        throw 'Restore Compose resolved an unexpected project name.'
    }
    $postgresPorts = @((Get-PersonalMemoObjectProperty -Object $config.services.postgres -Name 'ports') | Where-Object { $null -ne $_ })
    $backendPorts = @((Get-PersonalMemoObjectProperty -Object $config.services.backend -Name 'ports') | Where-Object { $null -ne $_ })
    if ($postgresPorts.Count -ne 0 -or $backendPorts.Count -ne 0) {
        throw 'Restore PostgreSQL and backend services must not publish host ports.'
    }
    $volume = Get-PersonalMemoObjectProperty -Object $config.volumes -Name 'personal-memo-postgres'
    if ($null -eq $volume -or [string] $volume.name -cne "$($Layout.ProjectName)_personal-memo-postgres") {
        throw 'Restore volume is not scoped to the exact generated project.'
    }
    return $config
}

function Assert-PersonalMemoTlsFiles {
    param([Parameter(Mandatory = $true)][PSCustomObject] $Layout)

    $values = Read-PersonalMemoEnvFile -Path $Layout.EnvFile
    foreach ($name in @('PERSONAL_MEMO_TLS_CERT_FILE', 'PERSONAL_MEMO_TLS_KEY_FILE', 'PERSONAL_MEMO_TLS_CA_FILE')) {
        $path = Get-PersonalMemoEnvValue -Values $values -Name $name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "TLS file does not exist: $name"
        }
    }
}

function Get-PersonalMemoDatabaseIdentity {
    param([Parameter(Mandatory = $true)][PSCustomObject] $Layout)

    $values = Read-PersonalMemoEnvFile -Path $Layout.EnvFile
    $database = Get-PersonalMemoEnvValue -Values $values -Name 'POSTGRES_DB'
    $username = Get-PersonalMemoEnvValue -Values $values -Name 'POSTGRES_USER'
    foreach ($value in @($database, $username)) {
        if ($value -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') {
            throw 'PostgreSQL database and role names must use safe identifier characters.'
        }
    }
    return [PSCustomObject]@{ Database = $database; Username = $username }
}

function Get-PersonalMemoDocumentsDirectory {
    $documents = [Environment]::GetFolderPath([Environment+SpecialFolder]::MyDocuments)
    if ([string]::IsNullOrWhiteSpace($documents)) {
        throw 'The Windows Documents directory could not be resolved.'
    }
    return [IO.Path]::GetFullPath($documents)
}

function Test-PersonalMemoPathWithin {
    param(
        [Parameter(Mandatory = $true)][string] $Child,
        [Parameter(Mandatory = $true)][string] $Parent
    )

    $childPath = [IO.Path]::GetFullPath($Child).TrimEnd('\')
    $parentPath = [IO.Path]::GetFullPath($Parent).TrimEnd('\')
    return $childPath.Equals($parentPath, [StringComparison]::OrdinalIgnoreCase) -or
        $childPath.StartsWith($parentPath + '\', [StringComparison]::OrdinalIgnoreCase)
}

function Get-PersonalMemoCurrentUserSid {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if ($null -eq $identity.User) {
        throw 'The current Windows user SID could not be resolved.'
    }
    return $identity.User
}

function Assert-PersonalMemoPrivateAcl {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [switch] $Directory
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $targetExists = if ($Directory) {
        [IO.Directory]::Exists($fullPath)
    } else {
        [IO.File]::Exists($fullPath)
    }
    if (-not $targetExists) {
        throw "Private ACL target does not exist: $fullPath"
    }

    $security = if ($Directory) {
        [IO.Directory]::GetAccessControl($fullPath)
    } else {
        [IO.File]::GetAccessControl($fullPath)
    }
    $expectedSid = Get-PersonalMemoCurrentUserSid
    $owner = $security.GetOwner([Security.Principal.SecurityIdentifier])
    if ($owner.Value -cne $expectedSid.Value) {
        throw "Private ACL target is not owned by the current Windows user: $fullPath"
    }
    if (-not $security.AreAccessRulesProtected) {
        throw "Private ACL target still inherits access rules: $fullPath"
    }

    $rules = @($security.GetAccessRules(
        $true,
        $true,
        [Security.Principal.SecurityIdentifier]
    ))
    if ($rules.Count -ne 1) {
        throw "Private ACL target must have exactly one access rule: $fullPath"
    }

    $rule = $rules[0]
    $expectedInheritance = if ($Directory) {
        [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
            [Security.AccessControl.InheritanceFlags]::ObjectInherit
    } else {
        [Security.AccessControl.InheritanceFlags]::None
    }
    if ($rule.IdentityReference.Value -cne $expectedSid.Value -or
        $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
        $rule.FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl -or
        $rule.InheritanceFlags -ne $expectedInheritance -or
        $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None -or
        $rule.IsInherited) {
        throw "Private ACL target grants access outside the current Windows user: $fullPath"
    }
}

function Set-PersonalMemoPrivateDirectoryAcl {
    param([Parameter(Mandatory = $true)][string] $Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not [IO.Directory]::Exists($fullPath)) {
        throw "Private directory does not exist: $fullPath"
    }
    $currentSid = Get-PersonalMemoCurrentUserSid
    $inheritance = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
        [Security.AccessControl.InheritanceFlags]::ObjectInherit
    $rule = New-Object Security.AccessControl.FileSystemAccessRule -ArgumentList @(
        $currentSid,
        [Security.AccessControl.FileSystemRights]::FullControl,
        $inheritance,
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow
    )
    $security = New-Object Security.AccessControl.DirectorySecurity
    $security.SetOwner($currentSid)
    $security.SetAccessRuleProtection($true, $false)
    $null = $security.AddAccessRule($rule)
    [IO.Directory]::SetAccessControl($fullPath, $security)
    Assert-PersonalMemoPrivateAcl -Path $fullPath -Directory
}

function Set-PersonalMemoPrivateFileAcl {
    param([Parameter(Mandatory = $true)][string] $Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not [IO.File]::Exists($fullPath)) {
        throw "Private file does not exist: $fullPath"
    }
    $currentSid = Get-PersonalMemoCurrentUserSid
    $rule = New-Object Security.AccessControl.FileSystemAccessRule -ArgumentList @(
        $currentSid,
        [Security.AccessControl.FileSystemRights]::FullControl,
        [Security.AccessControl.AccessControlType]::Allow
    )
    $security = New-Object Security.AccessControl.FileSecurity
    $security.SetOwner($currentSid)
    $security.SetAccessRuleProtection($true, $false)
    $null = $security.AddAccessRule($rule)
    [IO.File]::SetAccessControl($fullPath, $security)
    Assert-PersonalMemoPrivateAcl -Path $fullPath
}

function Assert-PersonalMemoBackupDirectory {
    param([Parameter(Mandatory = $true)][string] $Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    $documents = Get-PersonalMemoDocumentsDirectory
    $personalBackupRoot = Join-Path $documents 'PersonalMemo\Backups'
    if (-not (Test-PersonalMemoPathWithin -Child $fullPath -Parent $personalBackupRoot)) {
        throw 'Backup output must remain in Documents\PersonalMemo\Backups or one of its children.'
    }
    if (Test-PersonalMemoPathWithin -Child $fullPath -Parent $script:PersonalMemoRepositoryRoot) {
        throw 'Backup output must remain outside the Git repository.'
    }
    return $fullPath
}

function New-PersonalMemoHexSecret {
    param([ValidateRange(16, 128)][int] $ByteCount = 32)

    $bytes = New-Object byte[] $ByteCount
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    return ([BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant()
}

function ConvertTo-PersonalMemoDockerHostPath {
    param([Parameter(Mandatory = $true)][string] $Path)
    return ([IO.Path]::GetFullPath($Path) -replace '\\', '/')
}

function Get-PersonalMemoServiceContainerId {
    param(
        [Parameter(Mandatory = $true)][PSCustomObject] $Layout,
        [Parameter(Mandatory = $true)][string] $Service,
        [switch] $IncludePersonal
    )

    $containerId = (Invoke-PersonalMemoCompose -Layout $Layout -IncludePersonal:$IncludePersonal -Capture -CommandArguments @('ps', '-q', $Service)).Trim()
    if ([string]::IsNullOrWhiteSpace($containerId) -or $containerId.Contains([Environment]::NewLine)) {
        throw "Expected exactly one running container for service $Service."
    }
    return $containerId
}

function Assert-PersonalMemoContainerDumpPath {
    param([Parameter(Mandatory = $true)][string] $Path)
    if ($Path -cnotmatch '^/tmp/personal-memo-(backup|restore)-[a-f0-9]{32}\.dump$') {
        throw "Refusing unexpected container dump path: $Path"
    }
}

#Requires -Version 5.1

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$paths = [ordered]@{
    Install = Join-Path $PSScriptRoot 'Install-PersonalMemoAppCloudflareTunnel.ps1'
    StartConnector = Join-Path $PSScriptRoot 'Start-PersonalMemoAppCloudflareConnector.ps1'
    StopConnector = Join-Path $PSScriptRoot 'Stop-PersonalMemoAppCloudflareConnector.ps1'
    StartEdge = Join-Path $PSScriptRoot 'Start-PersonalMemoPublicAppEdge.ps1'
    StopEdge = Join-Path $PSScriptRoot 'Stop-PersonalMemoPublicAppEdge.ps1'
    Compose = Join-Path $repositoryRoot 'compose.public-app.yaml'
    ComposeTest = Join-Path $repositoryRoot 'compose.public-app.test.yaml'
    FrontendDockerfile = Join-Path $repositoryRoot 'frontend\Dockerfile'
    HomeOverviewModel = Join-Path $repositoryRoot 'frontend\src\features\home\homeOverviewModel.ts'
    HomeOverview = Join-Path $repositoryRoot 'frontend\src\features\home\HomeOverview.tsx'
    Dockerfile = Join-Path $repositoryRoot 'app-edge\Dockerfile'
    Nginx = Join-Path $repositoryRoot 'app-edge\default.conf.template'
    UpstreamFixture = Join-Path $repositoryRoot 'app-edge\test\upstream-nginx.conf'
    Smoke = Join-Path $PSScriptRoot 'Test-PersonalMemoPublicAppEdge.ps1'
}

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw "Source contract failed: $Message" }
}

function Assert-Contains([string] $Text, [string] $Pattern, [string] $Message) {
    Assert-True ([regex]::IsMatch($Text, $Pattern, [Text.RegularExpressions.RegexOptions]::Multiline)) $Message
}

function Assert-NotContains([string] $Text, [string] $Pattern, [string] $Message) {
    Assert-True (-not [regex]::IsMatch($Text, $Pattern, [Text.RegularExpressions.RegexOptions]::Multiline)) $Message
}

function Assert-Ordered([string] $Text, [string] $First, [string] $Second, [string] $Message) {
    $firstIndex = $Text.IndexOf($First, [StringComparison]::Ordinal)
    $secondIndex = $Text.IndexOf($Second, [StringComparison]::Ordinal)
    Assert-True ($firstIndex -ge 0 -and $secondIndex -gt $firstIndex) $Message
}

function Get-ExecutionBlock([string] $Text, [string] $InvocationPattern, [string] $Message) {
    $matches = @([regex]::Matches($Text, '(?m)^' + $InvocationPattern + '\r?$'))
    Assert-True ($matches.Count -eq 1) $Message
    return $Text.Substring($matches[0].Index)
}

function Get-Source([string] $Path) {
    Assert-True (Test-Path -LiteralPath $Path -PathType Leaf) "required file is missing: $Path"
    return [IO.File]::ReadAllText($Path)
}

function Get-ComposeServiceBlock([string] $ComposeText, [string] $ServiceName) {
    $match = [regex]::Match(
        $ComposeText,
        '(?ms)^  ' + [regex]::Escape($ServiceName) + ':\r?\n(?<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*\r?$|^networks:\s*\r?$)'
    )
    Assert-True $match.Success "Compose service block is missing: $ServiceName"
    return $match.Groups['body'].Value
}

function Get-ComposeServiceNetworks([string] $ServiceBlock) {
    $match = [regex]::Match($ServiceBlock, '(?m)^    networks:[ \t]*\r?\n(?<items>(?:      - [A-Za-z0-9_-]+[ \t]*\r?\n?)+)')
    Assert-True $match.Success 'Compose service must have an explicit network list'
    return @([regex]::Matches($match.Groups['items'].Value, '(?m)^      - (?<name>[A-Za-z0-9_-]+)[ \t]*\r?$') | ForEach-Object { $_.Groups['name'].Value })
}

function Assert-PowerShellParses([string] $Path) {
    $tokens = $null
    $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
    Assert-True ($errors.Count -eq 0) "Windows PowerShell parser rejected $Path"
}

$source = @{}
foreach ($entry in $paths.GetEnumerator()) { $source[$entry.Key] = Get-Source $entry.Value }
foreach ($key in @('Install', 'StartConnector', 'StopConnector', 'StartEdge', 'StopEdge')) {
    Assert-PowerShellParses $paths[$key]
    Assert-NotContains $source[$key] '\{\{[^\r\n]*"[^\r\n]*\}\}' 'Docker templates must not contain embedded double quotes under Windows PowerShell 5.1'
}

# The public hostname is injected from ignored deployment configuration and never embedded in PWA source.
$frontendCompose = Get-ComposeServiceBlock $source.Compose 'frontend'
Assert-Contains $frontendCompose 'VITE_OWNER_REMOTE_APP_HOSTNAME:' 'the frontend build must receive an explicit owner hostname'
Assert-Contains $frontendCompose '\$\{PUBLIC_APP_HOSTNAME:\?Set PUBLIC_APP_HOSTNAME' 'the frontend hostname build argument must fail closed'
Assert-Contains $source.FrontendDockerfile '^ARG VITE_OWNER_REMOTE_APP_HOSTNAME=$' 'the frontend image must declare the bounded hostname build argument'
Assert-Contains $source.FrontendDockerfile 'VITE_OWNER_REMOTE_APP_HOSTNAME=\$\{VITE_OWNER_REMOTE_APP_HOSTNAME\}' 'the Vite build must receive the reviewed hostname'
Assert-Contains $source.HomeOverviewModel 'import\.meta\.env\.VITE_OWNER_REMOTE_APP_HOSTNAME' 'the home model must read only the build-time public hostname'
Assert-Contains $source.HomeOverview 'ownerRemoteAppHostname' 'the home view must compare against the configured hostname'
Assert-Contains $source.HomeOverview '<strong>\{currentHostname\}</strong>' 'the home view must render only the exact current hostname'
foreach ($publicSource in @($source.HomeOverviewModel, $source.HomeOverview)) {
    Assert-NotContains $publicSource 'junpyo\.net' 'the public PWA source must not embed the owner domain'
}

# The app tunnel is an independent, pinned, token-file-only Windows service.
$install = $source.Install
$startConnector = $source.StartConnector
$stopConnector = $source.StopConnector
foreach ($text in @($install, $startConnector)) {
    Assert-Contains $text "PersonalMemoAppCloudflareTunnel" 'the exact app connector service name must be pinned'
    Assert-Contains $text ([regex]::Escape('C:\ProgramData\PersonalMemo\AppCloudflare')) 'the exact protected app connector root must be pinned'
    Assert-Contains $text "127\.0\.0\.1:49313" 'the app connector metrics endpoint must remain loopback-only and unique'
    Assert-Contains $text "tunnel-token\.txt" 'the service must use a protected token file'
}
Assert-Contains $install "2025\.4\.0" 'cloudflared 2025.4 or newer must be required'
Assert-Contains $install "Get-AuthenticodeSignature" 'the installer must verify the Authenticode signature'
Assert-Contains $install "Cloudflare, Inc\\\." 'the signer must be Cloudflare, Inc.'
Assert-Contains $install "ExpectedSha256" 'a separately reviewed SHA-256 must be mandatory'
Assert-Contains $install "Get-FileHash[^\r\n]+SHA256" 'the reviewed executable hash must be computed'
Assert-Contains $install '& \$resolved --version 2>&1' 'the reviewed executable must report its own cloudflared version'
Assert-Contains $install 'cloudflared\\s\+version' 'the installer must parse the cloudflared CLI version format'
Assert-Ordered $install 'Get-FileHash -LiteralPath $resolved -Algorithm SHA256' '& $resolved --version 2>&1' 'the separately reviewed hash must match before the executable is invoked'
Assert-Ordered $install '$versionLines = @(& $resolved --version 2>&1)' '$versionExitCode = $LASTEXITCODE' 'the cloudflared version exit code must be captured immediately'
Assert-NotContains $install 'VersionInfo\.(?:ProductVersion|FileVersion)' 'empty Windows file metadata must not be the version authority'
Assert-Contains $install 'SetAccessRuleProtection\(\$true, \$false\)' 'ACL inheritance must be removed'
Assert-Contains $install "S-1-5-18" 'SYSTEM must be explicitly allowed by the protected ACL'
Assert-Contains $install "S-1-5-32-544" 'Administrators must own and control protected artifacts'
Assert-Contains $install "StartupType Manual" 'the app connector must be installed as manual'
Assert-Contains $install "State -ne 'Stopped'" 'installation must verify the stopped state'
Assert-Contains $install "--token-file" 'the service command must consume only the token file'
Assert-NotContains $install '&\s+[^\r\n]*service\s+install' 'the Cloudflare token must never be passed to cloudflared service install'
Assert-NotContains $install 'Write-(?:Host|Output|Verbose|Debug)[^\r\n]*\$token' 'the token must never reach console output'
Assert-Contains $install "\[A-Za-z0-9\._~\+/\-\]\{20,4094\}=\{0,2\}" 'opaque Cloudflare token characters and bounds must be explicit'
Assert-Contains $install "cloudflared\\\.exe service install" 'only the exact Cloudflare Windows install command may wrap a token'
Assert-NotContains $install '\(\?:\\\.[A-Za-z0-9_=\-]\+\)\{2\}' 'the installer must not assume every Tunnel token is a three-segment JWT'
Assert-Contains $install '\$serviceDeleteExitCode\s*=\s*\$LASTEXITCODE' 'installer rollback must capture the sc.exe deletion result'
Assert-Contains $install 'for \(\$attempt = 0; \$attempt -lt 20; \$attempt\+\+\)' 'installer rollback must bound service deletion observation'
Assert-Contains $install '\$serviceDeletionProven' 'installer rollback must track proven Windows service deletion'
Assert-Ordered $install '& sc.exe delete $serviceName' '$serviceDeleteExitCode = $LASTEXITCODE' 'the sc.exe result must be captured immediately after deletion is requested'
Assert-Ordered $install 'if ($serviceDeletionProven)' 'Remove-Item -LiteralPath $installRoot -Recurse -Force' 'the protected install root may be removed only after service deletion is proven'
Assert-Contains $install 'Protected app connector artifacts were preserved' 'uncertain service deletion must preserve protected artifacts'
Assert-Contains $install 'APP_TUNNEL_INSTALL_CLEANUP_INCOMPLETE' 'incomplete rollback must return a bounded actionable safe code'
Assert-Contains $install 'Rotate the app Tunnel token in Cloudflare' 'a persisted or accepted token must have an explicit rotation warning'

$installTokens = $null
$installErrors = $null
$installAst = [Management.Automation.Language.Parser]::ParseFile(
    $paths.Install,
    [ref]$installTokens,
    [ref]$installErrors
)
$tokenConverterAsts = @($installAst.FindAll(
    {
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -ceq 'ConvertFrom-CloudflareTunnelSecretInput'
    },
    $true
))
Assert-True ($tokenConverterAsts.Count -eq 1) 'exactly one pure Tunnel credential converter must exist'
$tokenConverterSource = $tokenConverterAsts[0].Extent.Text
foreach ($forbiddenTokenParserFragment in @(
    'Write-Host', 'Write-Output', 'Write-Verbose', 'Write-Debug', 'Write-Warning',
    'Invoke-Expression', 'Start-Process', '& $SecretInput'
)) {
    Assert-NotContains $tokenConverterSource ([regex]::Escape($forbiddenTokenParserFragment)) "the token parser must not execute or log input: $forbiddenTokenParserFragment"
}
. ([scriptblock]::Create($tokenConverterSource))
$syntheticOpaqueToken = 'eyJhIjoiU1lOVEhFVElDX1RVTk5FTF9UT0tFTiJ9=='
Assert-True (
    (ConvertFrom-CloudflareTunnelSecretInput -SecretInput $syntheticOpaqueToken) -ceq $syntheticOpaqueToken
) 'a bounded opaque non-JWT Tunnel token must be accepted'
Assert-True (
    (ConvertFrom-CloudflareTunnelSecretInput -SecretInput ('cloudflared.exe service install ' + $syntheticOpaqueToken)) -ceq $syntheticOpaqueToken
) 'the exact copied Windows install command must yield only its token'
Assert-True (
    (ConvertFrom-CloudflareTunnelSecretInput -SecretInput ('CLOUDFLARED.EXE SERVICE INSTALL ' + $syntheticOpaqueToken)) -ceq $syntheticOpaqueToken
) 'the exact Windows command must be case-insensitive'
foreach ($invalidSecretInput in @(
    '', 'short', (' ' + $syntheticOpaqueToken), ($syntheticOpaqueToken + ' '),
    ($syntheticOpaqueToken + ';whoami'),
    ('$ cloudflared.exe service install ' + $syntheticOpaqueToken),
    ('cloudflared.exe service install  ' + $syntheticOpaqueToken),
    ('cloudflared.exe service install ' + $syntheticOpaqueToken + ' extra'),
    ('cmd.exe /c cloudflared.exe service install ' + $syntheticOpaqueToken),
    ('cloudflared.exe tunnel run ' + $syntheticOpaqueToken),
    ("${syntheticOpaqueToken}`r`nextra")
)) {
    $invalidInputRejected = $false
    try { $null = ConvertFrom-CloudflareTunnelSecretInput -SecretInput $invalidSecretInput }
    catch {
        $invalidInputRejected = $true
        Assert-True (
            $_.Exception.Message.IndexOf($syntheticOpaqueToken, [StringComparison]::Ordinal) -lt 0
        ) 'token parser errors must not reflect the supplied secret'
    }
    Assert-True $invalidInputRejected 'malformed or executable-looking Tunnel credential input must be rejected'
}

# Activation is fail-closed on the exact owner, Access and privacy review gates.
foreach ($gate in @(
    'AccessExactOwnerVerified', 'AccessDefaultDenyVerified', 'ProtectWithAccessVerified',
    'CacheBypassRuleVerified', 'RemoteRouteVerified', 'RemoteCatchAllVerified',
    'PrivacyBoundaryAccepted'
)) {
    Assert-Contains $startConnector ('\[switch\]\s+\$' + $gate) "connector activation must require $gate"
}
$exactHostnameRegex = "'^(?!calendar\.)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.[a-z]{2,63}$'"
foreach ($text in @($startConnector, $source.StartEdge, $source.StopEdge)) {
    Assert-Contains $text 'Value -cne \$Value\.ToLowerInvariant\(\)' 'hostnames must be exact lower-case values'
    Assert-True $text.Contains($exactHostnameRegex) 'hostnames must be exactly three labels and reject calendar'
}
Assert-Contains $startConnector ([regex]::Escape("'http://127.0.0.1:8788'")) 'the connector origin must be the loopback app edge'
Assert-Contains $startConnector ([regex]::Escape("'^/.*$'")) 'the exact hostname route must cover all application paths'
Assert-Contains $startConnector ([regex]::Escape("'http_status:404'")) 'the remote ingress must end with a 404 catch-all'
Assert-Contains $startConnector "PersonalMemoCalendarCloudflareTunnel" 'the known calendar service PID may coexist'
Assert-Contains $startConnector "GetProcessesByName\('cloudflared'\)" 'unknown cloudflared processes must be rejected'
Assert-Ordered $startConnector '$versionOutput = @(& $exe --version 2>&1)' '$versionExitCode = $LASTEXITCODE' 'connector activation must capture the cloudflared version exit code immediately'
Assert-Contains $startConnector ([regex]::Escape('$ErrorActionPreference = ''Continue''')) 'connector Docker inspection must locally suppress Windows PowerShell 5.1 NativeCommandError promotion'
Assert-Ordered $startConnector '$output = @(& docker.exe @Tail 2>$null)' '$dockerExitCode = $LASTEXITCODE' 'connector Docker inspection must capture its native exit code immediately'
Assert-Contains $startConnector 'if \(\$dockerExitCode -ne 0\)' 'connector Docker inspection failures must use a bounded error'
$startConnectorExecution = Get-ExecutionBlock $startConnector ([regex]::Escape('Assert-Administrator')) 'connector execution must have one top-level administrator gate'
Assert-Ordered $startConnectorExecution 'Assert-InstalledContract' 'Start-Service -Name $serviceName' 'all source and privacy gates must precede connector activation'
Assert-Ordered $startConnectorExecution 'Assert-ReviewedAppEdge' 'Start-Service -Name $serviceName' 'the exact labeled, healthy Docker edge and local probes must precede connector activation'
Assert-Contains $startConnector 'label=com\.docker\.compose\.project=personal-memo-private-win' 'connector activation must select the exact Compose project label'
Assert-Contains $startConnector 'label=com\.docker\.compose\.service=app-public-edge' 'connector activation must select the exact edge service label'
Assert-Contains $startConnector 'running\|healthy' 'connector activation must require a running healthy edge'
Assert-Contains $startConnector 'personal-memo-private-win_app-loopback' 'connector activation must require the exact edge-only network'
Assert-Contains $startConnector 'personal-memo-private-win_app-publication' 'connector activation must require the exact frontend publication network'
Assert-Contains $startConnector '\{\{json \.NetworkSettings\.Ports\}\}' 'connector activation must read the bounded full Docker ports object'
Assert-Contains $startConnector '\$ports\.PSObject\.Properties' 'connector activation must select the exact port key inside PowerShell'
Assert-Contains $startConnector "Name -ceq '8080/tcp'" 'connector activation must require only the exact container port key'
Assert-Contains $startConnector '\$bindings\s*=\s*@\(\)\s*\r?\n\s*if \(' 'connector activation must preserve zero/one Docker bindings as an array under Windows PowerShell 5.1'
Assert-Contains $startConnector '\$bindings\s*=\s*@\(\$portProperties\[0\]\.Value\)' 'connector activation must preserve the accepted Docker binding as an array under Windows PowerShell 5.1'
Assert-Contains $startConnector '\$null -ne \$portProperties\[0\]\.Value' 'connector activation must reject null Docker port metadata deterministically'
Assert-NotContains $startConnector '\$bindings\s*=\s*if\s*\(' 'connector activation must not assign an enumerated if result under Windows PowerShell 5.1'
Assert-NotContains $startConnector 'index \.NetworkSettings\.Ports' 'Docker templates must not rely on embedded native-argument quotes under Windows PowerShell 5.1'
Assert-Contains $startConnector 'wrong\.invalid' 'connector activation must prove wrong-Host bodyless denial locally'

$stopConnectorExecution = Get-ExecutionBlock $stopConnector ([regex]::Escape('Assert-Administrator')) 'connector stop must have one top-level administrator gate'
$stopServiceIndex = $stopConnectorExecution.IndexOf('Stop-Service -Name $serviceName', [StringComparison]::Ordinal)
$manualConvergenceIndex = $stopConnectorExecution.IndexOf('Set-Service -Name $serviceName -StartupType Manual', [StringComparison]::Ordinal)
$pinnedDefinitionIndex = $stopConnectorExecution.IndexOf('Test-PinnedServiceDefinition $svc', [StringComparison]::Ordinal)
Assert-True ($stopServiceIndex -ge 0 -and $manualConvergenceIndex -gt $stopServiceIndex -and $pinnedDefinitionIndex -gt $manualConvergenceIndex) 'connector stop must stop the exact-name service, converge Manual startup, and only then report pinned-definition drift'

# Edge lifecycle reuses the private project but scopes its public hostname to this process only.
foreach ($text in @($source.StartEdge, $source.StopEdge)) {
    Assert-Contains $text "personal-memo-private-win" 'the existing private Compose project name must be reused'
    Assert-Contains $text "\.env\.personal" 'the existing personal environment file must be reused'
    Assert-Contains $text "compose\.public-app\.yaml" 'the app publication overlay must be explicit'
    Assert-Contains $text "GetEnvironmentVariable\('PUBLIC_APP_HOSTNAME', 'Process'\)" 'the previous process-scoped hostname must be captured'
    Assert-Contains $text 'SetEnvironmentVariable\(''PUBLIC_APP_HOSTNAME'', \$PublicAppHostname, ''Process''\)' 'the hostname must only enter the current process environment'
    Assert-Contains $text 'SetEnvironmentVariable\(''PUBLIC_APP_HOSTNAME'', \$previous, ''Process''\)' 'the previous process environment must be restored'
    Assert-Contains $text "Assert-ConnectorStopped" 'edge changes must require a stopped connector'
    Assert-Contains $text "app-public-edge" 'the exact least-privilege edge service name must be used'
    Assert-NotContains $text "public-app-edge" 'the obsolete edge service name must not return'
    Assert-Contains $text ([regex]::Escape("if (`$ProjectName -cne 'personal-memo-private-win')")) 'edge lifecycle must reject every alternate Compose project name'
}
$startEdgeExecution = Get-ExecutionBlock $source.StartEdge ([regex]::Escape('Assert-Hostname $PublicAppHostname')) 'edge start must have one top-level hostname gate'
$stopEdgeExecution = Get-ExecutionBlock $source.StopEdge ([regex]::Escape('Assert-Hostname $PublicAppHostname')) 'edge stop must have one top-level hostname gate'
Assert-Ordered $stopEdgeExecution 'Assert-ConnectorStopped' "Invoke-Compose @('stop', 'app-public-edge')" 'rollback must stop the connector before the edge'
$snapshotIndex = $startEdgeExecution.IndexOf('$frontendSnapshot = Get-FrontendSnapshot', [StringComparison]::Ordinal)
$buildIndex = $startEdgeExecution.IndexOf("Invoke-Compose @('build', 'frontend', 'app-public-edge')", [StringComparison]::Ordinal)
$frontendUpIndex = $startEdgeExecution.IndexOf("Invoke-Compose @('up', '-d', '--no-build', '--no-deps', '--wait', 'frontend')", [StringComparison]::Ordinal)
$edgeUpIndex = $startEdgeExecution.IndexOf("Invoke-Compose @('up', '-d', '--no-build', '--no-deps', '--wait', 'app-public-edge')", [StringComparison]::Ordinal)
$rollbackIndex = $startEdgeExecution.IndexOf('Restore-FrontendSnapshot -Snapshot $frontendSnapshot', [StringComparison]::Ordinal)
$localReadyIndex = $startEdgeExecution.IndexOf('Assert-LocalEdgeReady', [StringComparison]::Ordinal)
$finalStoppedGateIndex = $startEdgeExecution.LastIndexOf('Assert-ConnectorStopped', [StringComparison]::Ordinal)
Assert-True ($snapshotIndex -ge 0 -and $buildIndex -gt $snapshotIndex -and $frontendUpIndex -gt $buildIndex -and $edgeUpIndex -gt $frontendUpIndex -and $rollbackIndex -gt $edgeUpIndex) 'edge startup must snapshot, prebuild, mutate last, and retain an exact frontend rollback call'
Assert-True ($localReadyIndex -gt $edgeUpIndex -and $finalStoppedGateIndex -gt $localReadyIndex) 'edge startup must prove exact-Host readiness and wrong-Host denial before its final connector-stopped gate'
Assert-True ($edgeUpIndex -ge 0 -and $finalStoppedGateIndex -gt $edgeUpIndex) 'startup must leave the connector stopped after the edge is ready'
Assert-Contains $source.StartEdge ([regex]::Escape("'personal-memo-private-win-app-public-edge:latest'")) 'isolated edge validation must resolve the exact deterministic build tag'
Assert-Contains $source.StartEdge 'ExistingPublicationNetworks' 'frontend snapshot must retain pre-existing publication network resources'
Assert-Contains $source.StartEdge 'Remove-NewEmptyPublicationNetworks' 'failed startup must remove newly created empty publication networks'

# Topology A uses one internal frontend link and one edge-only non-internal bridge required for
# Windows Docker Desktop to honor the host-loopback publish.
$compose = $source.Compose
Assert-Contains $compose "(?m)^\s{2}app-public-edge:\s*$" 'Compose must define app-public-edge exactly'
Assert-NotContains $compose "(?m)^\s{2}public-app-edge:\s*$" 'Compose must not define the obsolete edge name'
Assert-Contains $compose ([regex]::Escape('PUBLIC_APP_HOSTNAME: ${PUBLIC_APP_HOSTNAME:?Set PUBLIC_APP_HOSTNAME to the exact public app hostname}')) 'Compose must fail closed without the scoped hostname'
Assert-Contains $compose ([regex]::Escape('127.0.0.1:${PERSONAL_MEMO_APP_EDGE_PORT:-8788}:8080')) 'only the loopback app-edge port may be published'
Assert-NotContains $compose "(?m)^\s{2}(?:backend|postgres):[\s\S]*?\n\s{4}ports:" 'the publication overlay must not publish backend or PostgreSQL'
foreach ($composeKey in @('Compose', 'ComposeTest')) {
    $topology = $source[$composeKey]
    $edgeBlock = Get-ComposeServiceBlock $topology 'app-public-edge'
    $frontendBlock = Get-ComposeServiceBlock $topology 'frontend'
    $edgeNetworks = @(Get-ComposeServiceNetworks $edgeBlock)
    $frontendNetworks = @(Get-ComposeServiceNetworks $frontendBlock)
    Assert-True (($edgeNetworks -join ',') -ceq 'app-publication,app-loopback') "$composeKey edge must join only app-publication and app-loopback"
    Assert-True ($frontendNetworks -contains 'app-publication') "$composeKey frontend must share app-publication"
    Assert-True ($frontendNetworks -notcontains 'app-loopback') "$composeKey app-loopback must remain edge-only"
    foreach ($forbiddenNetwork in @('default', 'backend', 'postgres')) {
        Assert-True ($edgeNetworks -notcontains $forbiddenNetwork) "$composeKey edge must not join $forbiddenNetwork"
    }
    Assert-Contains $topology '(?ms)^  app-publication:\s*\r?\n    internal: true\s*$' "$composeKey app-publication must remain internal"
    Assert-Contains $topology '(?m)^  app-loopback: \{\}\s*$' "$composeKey app-loopback must remain an explicitly non-internal bridge"
    foreach ($hardening in @('read_only: true', 'cap_drop:', '- ALL', 'no-new-privileges:true')) {
        Assert-True $edgeBlock.Contains($hardening) "$composeKey edge must retain hardening: $hardening"
    }
}

# The container renders one hostname variable; all Nginx runtime variables stay untouched.
Assert-Contains $source.Dockerfile ([regex]::Escape('ENV NGINX_ENVSUBST_FILTER=^PUBLIC_APP_HOSTNAME$')) 'envsubst must be restricted to PUBLIC_APP_HOSTNAME'
Assert-Contains $source.Dockerfile "USER 101:101" 'the edge must run unprivileged'
Assert-Contains $source.Dockerfile "nginx-unprivileged:[^\r\n]+@sha256:" 'the base image must be digest pinned'

$nginx = $source.Nginx
Assert-Contains $nginx ([regex]::Escape('server_name ${PUBLIC_APP_HOSTNAME};')) 'Nginx must select only the exact configured hostname'
Assert-Contains $nginx ([regex]::Escape('if ($http_host != "${PUBLIC_APP_HOSTNAME}")')) 'Host case, ports and trailing dots must be rejected'
foreach ($method in @('GET', 'HEAD', 'POST', 'PATCH', 'DELETE')) {
    Assert-Contains $nginx ("(?m)^\s+" + $method + " 1;\s*$") "method $method must be explicitly allowed"
}
Assert-Contains $nginx '(?ms)map \$request_method \$personal_memo_app_method_allowed \{\s*default 0;' 'all other methods must fail closed'
Assert-Contains $nginx ([regex]::Escape('"https://${PUBLIC_APP_HOSTNAME}" 1;')) 'unsafe methods must accept only the exact same Origin'
Assert-Contains $nginx "proxy_pass_request_headers off;" 'request headers must use an explicit allowlist'
Assert-Contains $nginx 'map \$http_cookie \$personal_memo_app_session_cookie_value' 'SESSION must be selected from the incoming cookie header'
Assert-Contains $nginx 'map \$http_cookie \$personal_memo_app_xsrf_cookie_value' 'XSRF-TOKEN must be selected from the incoming cookie header'
Assert-Contains $nginx 'SESSION=\$personal_memo_app_session_both; XSRF-TOKEN=\$personal_memo_app_xsrf_both' 'the application Cookie header must be reconstructed from two bounded values'
Assert-Contains $nginx 'proxy_set_header Cookie \$personal_memo_app_cookie_allowlist;' 'the raw incoming Cookie header must never be proxied'
Assert-NotContains $nginx 'proxy_set_header Cookie \$http_cookie;' 'raw cookies must not cross the origin boundary'
Assert-NotContains $nginx 'CF_Authorization' 'the Cloudflare Access cookie must not be copied or reconstructed'
$allowedRequestHeaders = @(
    'Host', 'Connection', 'X-Forwarded-Host', 'X-Forwarded-Port', 'X-Forwarded-Proto',
    'Cookie', 'Content-Type', 'Content-Length', 'Accept', 'Origin', 'X-XSRF-TOKEN',
    'X-Expected-Owner-Id', 'Idempotency-Key', 'X-Analysis-Proposal-Schema-Version',
    'If-None-Match', 'If-Modified-Since'
)
foreach ($header in $allowedRequestHeaders) {
    Assert-Contains $nginx ("proxy_set_header\s+" + [regex]::Escape($header) + "\s+") "the minimal browser header allowlist must explicitly set $header"
}
$configuredRequestHeaders = @([regex]::Matches($nginx, '(?m)^\s*proxy_set_header\s+(?<name>[^\s;]+)\s+') | ForEach-Object { $_.Groups['name'].Value } | Sort-Object -Unique)
Assert-True (($configuredRequestHeaders -join ',') -ceq (($allowedRequestHeaders | Sort-Object -Unique) -join ',')) 'proxy_set_header must contain exactly the reviewed allowlist and canonical forwarding headers'
foreach ($rawHeaderVariable in @('$http_authorization', '$http_forwarded', '$http_cf_', '$http_user_agent', '$http_referer', '$http_accept_language')) {
    Assert-True (-not $nginx.Contains($rawHeaderVariable)) "untrusted raw request header must not be forwarded: $rawHeaderVariable"
}
foreach ($blocked in @('/calendar/v1/feed.ics', '/actuator', '/_internal', '/api/v1/auth/register', '/oauth2', '/login/oauth2')) {
    Assert-Contains $nginx ("location = " + [regex]::Escape($blocked) + " \{ return 404; \}") "$blocked must be blocked at the edge"
}
Assert-Contains $nginx ([regex]::Escape('location ^~ "/api/v1/auth/register;" { return 404; }')) 'Spring-style registration matrix parameters must be blocked before the generic API proxy'
Assert-Contains $source.UpstreamFixture 'registrationHandlerReached' 'the disposable upstream must expose a registration handler sentinel'
Assert-Contains $source.Smoke ([regex]::Escape("'--noproxy', '127.0.0.1'")) 'the disposable smoke must bypass proxies only for its exact loopback target'
Assert-Contains $source.Smoke ([regex]::Escape("'/api/v1/auth/register;matrix=synthetic'")) 'smoke must probe a literal registration matrix parameter'
Assert-Contains $source.Smoke ([regex]::Escape("'/api/v1/auth/register%3Bmatrix=synthetic'")) 'smoke must probe a percent-encoded registration matrix parameter'
foreach ($allowedPath in @('/', '/index.html', '/assets/', '/icons/', '/sw.js', '/registerSW.js', '/manifest.webmanifest', '/api/v1/auth/login', '/api/v1/')) {
    Assert-Contains $nginx ('location (?:= |\^~ )?' + [regex]::Escape($allowedPath)) "the reviewed application path must remain explicit: $allowedPath"
}
Assert-Contains $nginx '(?ms)location / \{\s*return 404;\s*\}' 'all unlisted application paths must fail closed'

$logMatch = [regex]::Match($nginx, '(?ms)log_format\s+personal_memo_app_edge_safe\s+(?<body>.*?);')
Assert-True $logMatch.Success 'the fixed safe access-log format must exist'
$logBody = $logMatch.Groups['body'].Value
foreach ($forbidden in @('$request_uri', '$uri', '$args', '$host', '$http_', '$remote_addr', '$request', '$cookie', '$sent_http_')) {
    $forbiddenPattern = [regex]::Escape($forbidden)
    if (-not $forbidden.EndsWith('_', [StringComparison]::Ordinal)) { $forbiddenPattern += '(?![A-Za-z0-9_])' }
    Assert-True (-not [regex]::IsMatch($logBody, $forbiddenPattern)) "safe logs must not contain raw variable $forbidden"
}
foreach ($allowed in @('$personal_memo_app_method_class', '$personal_memo_app_route_class', '$status', '$body_bytes_sent', '$request_time')) {
    Assert-True $logBody.Contains($allowed) "safe logs must retain fixed-class diagnostic $allowed"
}

foreach ($bound in @(
    'client_max_body_size 256k;', 'client_body_buffer_size 32k;', 'client_header_timeout 10s;',
    'client_body_timeout 10s;', 'client_header_buffer_size 2k;', 'large_client_header_buffers 4 8k;',
    'limit_req_zone $personal_memo_app_limit_key zone=personal_memo_app_rate:1m rate=120r/m;',
    'limit_req_zone $personal_memo_app_login_limit_key zone=personal_memo_app_login_rate:1m rate=10r/m;',
    'limit_req zone=personal_memo_app_rate burst=30 nodelay;',
    'limit_req zone=personal_memo_app_login_rate burst=5 nodelay;',
    'limit_conn personal_memo_app_connections 32;', 'keepalive_timeout 15s;',
    'keepalive_requests 50;', 'proxy_connect_timeout 3s;', 'proxy_send_timeout 15s;',
    'proxy_read_timeout 60s;', 'proxy_next_upstream off;'
)) {
    Assert-Contains $nginx ([regex]::Escape($bound)) "bounded edge setting must remain exact: $bound"
}
Assert-Contains $nginx 'Strict-Transport-Security\s+"max-age=[1-9][0-9]*"\s+always;' 'HSTS must be enforced'
Assert-Contains $nginx ([regex]::Escape('Content-Security-Policy "default-src ''self'';')) 'the CSP must be fail-closed by default'
Assert-Contains $nginx ([regex]::Escape('default "no-store";')) 'dynamic responses must be non-cacheable'
Assert-Contains $nginx "public, max-age=31536000, immutable" 'only content-hashed artifacts may be cached long-term'
Assert-Contains $nginx "assets/\[A-Za-z0-9\._-\]\+-\[A-Za-z0-9_-\]\{8," 'the immutable asset rule must require a content hash'
Assert-Contains $nginx ([regex]::Escape('map "$uri:$status" $personal_memo_app_cache_control')) 'immutable caching must depend on the final response status as well as the normalized path'
Assert-Contains $nginx ([regex]::Escape(':(?:200|206|304)$')) 'only successful or revalidated hashed assets may receive immutable caching'
Assert-Contains $source.Smoke ([regex]::Escape("'/assets/missing-abcdef12.js'")) 'the disposable smoke must cover a missing hash-shaped asset'
Assert-Contains $source.Smoke 'missing hash-shaped asset did not fail closed with 404' 'the disposable smoke must require a non-cacheable missing hashed asset response'

Write-Host 'Personal Memo public app source contracts passed without reading personal data or starting services.'

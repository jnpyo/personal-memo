[CmdletBinding()]
param(
    [Parameter()]
    [ValidateNotNullOrEmpty()]
    [string] $ReceiptPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Stop-ContractValidation {
    param([Parameter(Mandatory = $true)][string] $Message)

    throw "AI product smoke source contract failed: $Message"
}

function Get-JsonProperty {
    param(
        [Parameter(Mandatory = $true)][object] $Object,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $Path
    )

    if ($null -eq $Object) {
        Stop-ContractValidation "$Path is null."
    }

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        Stop-ContractValidation "$Path is missing property '$Name'."
    }

    return $property.Value
}

function Get-JsonPropertyNames {
    param(
        [Parameter(Mandatory = $true)][object] $Object,
        [Parameter(Mandatory = $true)][string] $Path
    )

    if ($null -eq $Object) {
        Stop-ContractValidation "$Path is null."
    }

    return @($Object.PSObject.Properties | ForEach-Object { $_.Name })
}

function Assert-ExactSet {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]] $Actual,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]] $Expected,
        [Parameter(Mandatory = $true)][string] $Path
    )

    [string[]] $actualSorted = @($Actual)
    [string[]] $expectedSorted = @($Expected)
    [Array]::Sort($actualSorted, [StringComparer]::Ordinal)
    [Array]::Sort($expectedSorted, [StringComparer]::Ordinal)
    [bool] $different = $actualSorted.Count -ne $expectedSorted.Count
    if (-not $different) {
        for ($index = 0; $index -lt $actualSorted.Count; $index++) {
            if ($actualSorted[$index] -cne $expectedSorted[$index]) {
                $different = $true
                break
            }
        }
    }
    if ($different) {
        Stop-ContractValidation "$Path must contain exactly [$($expectedSorted -join ', ')]; found [$($actualSorted -join ', ')]."
    }
}

function Assert-ExactProperties {
    param(
        [Parameter(Mandatory = $true)][object] $Object,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]] $Expected,
        [Parameter(Mandatory = $true)][string] $Path
    )

    Assert-ExactSet -Actual (Get-JsonPropertyNames -Object $Object -Path $Path) -Expected $Expected -Path "$Path properties"
}

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)] $Actual,
        [Parameter(Mandatory = $true)] $Expected,
        [Parameter(Mandatory = $true)][string] $Path
    )

    if ($Actual -cne $Expected) {
        Stop-ContractValidation "$Path must be '$Expected'; found '$Actual'."
    }
}

function Assert-Boolean {
    param(
        [Parameter(Mandatory = $true)] $Actual,
        [Parameter(Mandatory = $true)][bool] $Expected,
        [Parameter(Mandatory = $true)][string] $Path
    )

    if (($Actual -isnot [bool]) -or ([bool]$Actual -ne $Expected)) {
        Stop-ContractValidation "$Path must be Boolean '$Expected'."
    }
}

function Assert-IntegerRange {
    param(
        [Parameter(Mandatory = $true)] $Actual,
        [Parameter(Mandatory = $true)][long] $Minimum,
        [Parameter(Mandatory = $true)][long] $Maximum,
        [Parameter(Mandatory = $true)][string] $Path
    )

    if ($Actual -is [bool] -or $Actual -isnot [ValueType]) {
        Stop-ContractValidation "$Path must be an integer."
    }

    $number = 0.0
    if (-not [double]::TryParse(
            ([string]$Actual),
            [Globalization.NumberStyles]::Number,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$number)) {
        Stop-ContractValidation "$Path must be an integer."
    }
    if ([double]::IsNaN($number) -or [double]::IsInfinity($number) -or [Math]::Truncate($number) -ne $number) {
        Stop-ContractValidation "$Path must be an integer."
    }
    if ($number -lt $Minimum -or $number -gt $Maximum) {
        Stop-ContractValidation "$Path must be between $Minimum and $Maximum; found $number."
    }
}

function Assert-NumberRange {
    param(
        [Parameter(Mandatory = $true)] $Actual,
        [Parameter(Mandatory = $true)][double] $Minimum,
        [Parameter(Mandatory = $true)][double] $Maximum,
        [Parameter(Mandatory = $true)][string] $Path
    )

    if ($Actual -is [bool] -or $Actual -isnot [ValueType]) {
        Stop-ContractValidation "$Path must be a number."
    }

    $number = 0.0
    if (-not [double]::TryParse(
            ([string]$Actual),
            [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$number)) {
        Stop-ContractValidation "$Path must be a number."
    }
    if ([double]::IsNaN($number) -or [double]::IsInfinity($number) -or $number -lt $Minimum -or $number -gt $Maximum) {
        Stop-ContractValidation "$Path must be between $Minimum and $Maximum; found $number."
    }
}

function Assert-RegexValue {
    param(
        [Parameter(Mandatory = $true)] $Actual,
        [Parameter(Mandatory = $true)][string] $Pattern,
        [Parameter(Mandatory = $true)][string] $Path
    )

    if ($Actual -isnot [string] -or -not [regex]::IsMatch([string]$Actual, $Pattern, [Text.RegularExpressions.RegexOptions]::CultureInvariant)) {
        Stop-ContractValidation "$Path has an invalid format."
    }
}

function Assert-DateTimeValue {
    param(
        [Parameter(Mandatory = $true)] $Actual,
        [Parameter(Mandatory = $true)][string] $Path
    )

    if ($Actual -is [DateTimeOffset] -or $Actual -is [DateTime]) {
        return
    }
    if ($Actual -isnot [string]) {
        Stop-ContractValidation "$Path must be a date-time string."
    }

    $parsed = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse(
            [string]$Actual,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind,
            [ref]$parsed)) {
        Stop-ContractValidation "$Path must be a valid date-time string."
    }
}

function Read-StrictJsonDocument {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $Label
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Stop-ContractValidation "$Label does not exist at '$Path'."
    }

    $raw = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
    if ([string]::IsNullOrWhiteSpace($raw)) {
        Stop-ContractValidation "$Label is empty."
    }

    try {
        $document = $raw | ConvertFrom-Json
    } catch {
        Stop-ContractValidation "$Label is not valid JSON."
    }

    return [PSCustomObject]@{
        Raw = $raw
        Document = $document
    }
}

function Get-Utf8Sha256 {
    param([Parameter(Mandatory = $true)][string] $Value)

    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        return ([BitConverter]::ToString($algorithm.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

function Assert-SchemaConst {
    param(
        [Parameter(Mandatory = $true)][object] $Properties,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)] $Expected,
        [Parameter(Mandatory = $true)][string] $Path
    )

    $definition = Get-JsonProperty -Object $Properties -Name $Name -Path $Path
    Assert-ExactProperties -Object $definition -Expected @('const') -Path "$Path.$Name"
    $actual = Get-JsonProperty -Object $definition -Name 'const' -Path "$Path.$Name"
    if ($Expected -is [bool]) {
        Assert-Boolean -Actual $actual -Expected ([bool]$Expected) -Path "$Path.$Name.const"
    } else {
        Assert-Equal -Actual $actual -Expected $Expected -Path "$Path.$Name.const"
    }
}

function Assert-SchemaRef {
    param(
        [Parameter(Mandatory = $true)][object] $Properties,
        [Parameter(Mandatory = $true)][string] $Name,
        [Parameter(Mandatory = $true)][string] $Expected,
        [Parameter(Mandatory = $true)][string] $Path
    )

    $definition = Get-JsonProperty -Object $Properties -Name $Name -Path $Path
    Assert-ExactProperties -Object $definition -Expected @('$ref') -Path "$Path.$Name"
    Assert-Equal -Actual (Get-JsonProperty -Object $definition -Name '$ref' -Path "$Path.$Name") -Expected $Expected -Path "$Path.$Name.`$ref"
}

function Assert-SchemaObjectDefinition {
    param(
        [Parameter(Mandatory = $true)][object] $Definition,
        [Parameter(Mandatory = $true)][string[]] $PropertyNames,
        [Parameter(Mandatory = $true)][string] $Path
    )

    Assert-ExactProperties -Object $Definition -Expected @('type', 'additionalProperties', 'required', 'properties') -Path $Path
    Assert-Equal -Actual (Get-JsonProperty -Object $Definition -Name 'type' -Path $Path) -Expected 'object' -Path "$Path.type"
    Assert-Boolean -Actual (Get-JsonProperty -Object $Definition -Name 'additionalProperties' -Path $Path) -Expected $false -Path "$Path.additionalProperties"
    Assert-ExactSet -Actual @((Get-JsonProperty -Object $Definition -Name 'required' -Path $Path)) -Expected $PropertyNames -Path "$Path.required"
    $properties = Get-JsonProperty -Object $Definition -Name 'properties' -Path $Path
    Assert-ExactProperties -Object $properties -Expected $PropertyNames -Path "$Path.properties"
    return $properties
}

function Assert-ScalarSchema {
    param(
        [Parameter(Mandatory = $true)][object] $Definition,
        [Parameter(Mandatory = $true)][hashtable] $Expected,
        [Parameter(Mandatory = $true)][string] $Path
    )

    Assert-ExactProperties -Object $Definition -Expected @($Expected.Keys) -Path $Path
    foreach ($key in $Expected.Keys) {
        $actual = Get-JsonProperty -Object $Definition -Name ([string]$key) -Path $Path
        $expectedValue = $Expected[$key]
        if ($expectedValue -is [bool]) {
            Assert-Boolean -Actual $actual -Expected ([bool]$expectedValue) -Path "$Path.$key"
        } else {
            Assert-Equal -Actual $actual -Expected $expectedValue -Path "$Path.$key"
        }
    }
}

function Assert-FixtureSchemaContract {
    param([Parameter(Mandatory = $true)][object] $Schema)

    $rootNames = @('$schema', '$id', 'title', 'type', 'additionalProperties', 'required', 'properties', '$defs')
    Assert-ExactProperties -Object $Schema -Expected $rootNames -Path 'fixtureSchema'
    Assert-Equal -Actual (Get-JsonProperty -Object $Schema -Name '$schema' -Path 'fixtureSchema') -Expected 'https://json-schema.org/draft/2020-12/schema' -Path 'fixtureSchema.$schema'
    Assert-Equal -Actual (Get-JsonProperty -Object $Schema -Name '$id' -Path 'fixtureSchema') -Expected 'https://personal-memo.local/schemas/ai-preferred-product-smoke-fixture-v1.json' -Path 'fixtureSchema.$id'
    Assert-Equal -Actual (Get-JsonProperty -Object $Schema -Name 'title' -Path 'fixtureSchema') -Expected 'AiPreferredProductSmokeFixtureV1' -Path 'fixtureSchema.title'
    Assert-Equal -Actual (Get-JsonProperty -Object $Schema -Name 'type' -Path 'fixtureSchema') -Expected 'object' -Path 'fixtureSchema.type'
    Assert-Boolean -Actual (Get-JsonProperty -Object $Schema -Name 'additionalProperties' -Path 'fixtureSchema') -Expected $false -Path 'fixtureSchema.additionalProperties'

    $fixturePropertyNames = @('schemaVersion', 'fixtureId', 'dataClass', 'clientRecordedAt', 'timeZone', 'cases')
    Assert-ExactSet -Actual @((Get-JsonProperty -Object $Schema -Name 'required' -Path 'fixtureSchema')) -Expected $fixturePropertyNames -Path 'fixtureSchema.required'
    $properties = Get-JsonProperty -Object $Schema -Name 'properties' -Path 'fixtureSchema'
    Assert-ExactProperties -Object $properties -Expected $fixturePropertyNames -Path 'fixtureSchema.properties'
    Assert-SchemaConst -Properties $properties -Name 'schemaVersion' -Expected 1 -Path 'fixtureSchema.properties'
    Assert-SchemaConst -Properties $properties -Name 'fixtureId' -Expected 'ai-preferred-product-smoke-v1' -Path 'fixtureSchema.properties'
    Assert-SchemaConst -Properties $properties -Name 'dataClass' -Expected 'PUBLIC_SYNTHETIC_ONLY' -Path 'fixtureSchema.properties'
    Assert-SchemaConst -Properties $properties -Name 'timeZone' -Expected 'Asia/Seoul' -Path 'fixtureSchema.properties'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $properties -Name 'clientRecordedAt' -Path 'fixtureSchema.properties') -Expected @{ type = 'string'; format = 'date-time' } -Path 'fixtureSchema.properties.clientRecordedAt'

    $cases = Get-JsonProperty -Object $properties -Name 'cases' -Path 'fixtureSchema.properties'
    Assert-ExactProperties -Object $cases -Expected @('type', 'minItems', 'maxItems', 'uniqueItems', 'items', 'allOf') -Path 'fixtureSchema.properties.cases'
    Assert-Equal -Actual (Get-JsonProperty -Object $cases -Name 'type' -Path 'fixtureSchema.properties.cases') -Expected 'array' -Path 'fixtureSchema.properties.cases.type'
    Assert-Equal -Actual (Get-JsonProperty -Object $cases -Name 'minItems' -Path 'fixtureSchema.properties.cases') -Expected 3 -Path 'fixtureSchema.properties.cases.minItems'
    Assert-Equal -Actual (Get-JsonProperty -Object $cases -Name 'maxItems' -Path 'fixtureSchema.properties.cases') -Expected 3 -Path 'fixtureSchema.properties.cases.maxItems'
    Assert-Boolean -Actual (Get-JsonProperty -Object $cases -Name 'uniqueItems' -Path 'fixtureSchema.properties.cases') -Expected $true -Path 'fixtureSchema.properties.cases.uniqueItems'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $cases -Name 'items' -Path 'fixtureSchema.properties.cases') -Expected @{ '$ref' = '#/$defs/case' } -Path 'fixtureSchema.properties.cases.items'

    $expectedExpectations = @('AFFIRMATIVE_TASK_UNKNOWN_TIME', 'NEGATED_NON_TASK', 'DESCRIPTIVE_NON_TASK')
    $containsExpectations = @()
    $allOf = @((Get-JsonProperty -Object $cases -Name 'allOf' -Path 'fixtureSchema.properties.cases'))
    if ($allOf.Count -ne 3) {
        Stop-ContractValidation 'fixtureSchema.properties.cases.allOf must contain exactly three clauses.'
    }
    foreach ($clause in $allOf) {
        Assert-ExactProperties -Object $clause -Expected @('contains', 'minContains', 'maxContains') -Path 'fixtureSchema.properties.cases.allOf[]'
        Assert-Equal -Actual (Get-JsonProperty -Object $clause -Name 'minContains' -Path 'fixtureSchema.properties.cases.allOf[]') -Expected 1 -Path 'fixtureSchema.properties.cases.allOf[].minContains'
        Assert-Equal -Actual (Get-JsonProperty -Object $clause -Name 'maxContains' -Path 'fixtureSchema.properties.cases.allOf[]') -Expected 1 -Path 'fixtureSchema.properties.cases.allOf[].maxContains'
        $contains = Get-JsonProperty -Object $clause -Name 'contains' -Path 'fixtureSchema.properties.cases.allOf[]'
        Assert-ExactProperties -Object $contains -Expected @('type', 'required', 'properties') -Path 'fixtureSchema.properties.cases.allOf[].contains'
        Assert-Equal -Actual (Get-JsonProperty -Object $contains -Name 'type' -Path 'fixtureSchema.properties.cases.allOf[].contains') -Expected 'object' -Path 'fixtureSchema.properties.cases.allOf[].contains.type'
        Assert-ExactSet -Actual @((Get-JsonProperty -Object $contains -Name 'required' -Path 'fixtureSchema.properties.cases.allOf[].contains')) -Expected @('expectation') -Path 'fixtureSchema.properties.cases.allOf[].contains.required'
        $containsProperties = Get-JsonProperty -Object $contains -Name 'properties' -Path 'fixtureSchema.properties.cases.allOf[].contains'
        Assert-ExactProperties -Object $containsProperties -Expected @('expectation') -Path 'fixtureSchema.properties.cases.allOf[].contains.properties'
        $expectationDefinition = Get-JsonProperty -Object $containsProperties -Name 'expectation' -Path 'fixtureSchema.properties.cases.allOf[].contains.properties'
        Assert-ExactProperties -Object $expectationDefinition -Expected @('const') -Path 'fixtureSchema.properties.cases.allOf[].contains.properties.expectation'
        $containsExpectations += [string](Get-JsonProperty -Object $expectationDefinition -Name 'const' -Path 'fixtureSchema.properties.cases.allOf[].contains.properties.expectation')
    }
    Assert-ExactSet -Actual $containsExpectations -Expected $expectedExpectations -Path 'fixtureSchema.properties.cases.allOf expectation constants'

    $defs = Get-JsonProperty -Object $Schema -Name '$defs' -Path 'fixtureSchema'
    Assert-ExactProperties -Object $defs -Expected @('case') -Path 'fixtureSchema.$defs'
    $caseDefinition = Get-JsonProperty -Object $defs -Name 'case' -Path 'fixtureSchema.$defs'
    $caseProperties = Assert-SchemaObjectDefinition -Definition $caseDefinition -PropertyNames @('id', 'content', 'expectation') -Path 'fixtureSchema.$defs.case'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $caseProperties -Name 'id' -Path 'fixtureSchema.$defs.case.properties') -Expected @{ type = 'string'; pattern = '^[a-z0-9]+(?:-[a-z0-9]+)*$'; maxLength = 64 } -Path 'fixtureSchema.$defs.case.properties.id'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $caseProperties -Name 'content' -Path 'fixtureSchema.$defs.case.properties') -Expected @{ type = 'string'; minLength = 1; maxLength = 100 } -Path 'fixtureSchema.$defs.case.properties.content'
    $expectation = Get-JsonProperty -Object $caseProperties -Name 'expectation' -Path 'fixtureSchema.$defs.case.properties'
    Assert-ExactProperties -Object $expectation -Expected @('enum') -Path 'fixtureSchema.$defs.case.properties.expectation'
    Assert-ExactSet -Actual @((Get-JsonProperty -Object $expectation -Name 'enum' -Path 'fixtureSchema.$defs.case.properties.expectation')) -Expected $expectedExpectations -Path 'fixtureSchema.$defs.case.properties.expectation.enum'
}

function Assert-FixtureContract {
    param([Parameter(Mandatory = $true)][object] $Fixture)

    $fixturePropertyNames = @('schemaVersion', 'fixtureId', 'dataClass', 'clientRecordedAt', 'timeZone', 'cases')
    Assert-ExactProperties -Object $Fixture -Expected $fixturePropertyNames -Path 'fixture'
    Assert-Equal -Actual (Get-JsonProperty -Object $Fixture -Name 'schemaVersion' -Path 'fixture') -Expected 1 -Path 'fixture.schemaVersion'
    Assert-Equal -Actual (Get-JsonProperty -Object $Fixture -Name 'fixtureId' -Path 'fixture') -Expected 'ai-preferred-product-smoke-v1' -Path 'fixture.fixtureId'
    Assert-Equal -Actual (Get-JsonProperty -Object $Fixture -Name 'dataClass' -Path 'fixture') -Expected 'PUBLIC_SYNTHETIC_ONLY' -Path 'fixture.dataClass'
    Assert-DateTimeValue -Actual (Get-JsonProperty -Object $Fixture -Name 'clientRecordedAt' -Path 'fixture') -Path 'fixture.clientRecordedAt'
    Assert-Equal -Actual (Get-JsonProperty -Object $Fixture -Name 'timeZone' -Path 'fixture') -Expected 'Asia/Seoul' -Path 'fixture.timeZone'

    $cases = @((Get-JsonProperty -Object $Fixture -Name 'cases' -Path 'fixture'))
    if ($cases.Count -ne 3) {
        Stop-ContractValidation 'fixture.cases must contain exactly three cases.'
    }

    $expectedCases = @{
        'affirmative-task-unknown-time' = @('363602fd00efcd9e502dc78d8ecfafde68f1f9888b76f0893aeb30794d5535cb', 'AFFIRMATIVE_TASK_UNKNOWN_TIME')
        'negated-non-task' = @('fa13d80a87d35737c1c7ae7dfcaa15ac11e15132be86483f16db4d5df7d9f46b', 'NEGATED_NON_TASK')
        'descriptive-non-task' = @('6d230b78ac3d5d0ecdbe52372efc6936bfc780cd4c23d4c39c66db318dd0908e', 'DESCRIPTIVE_NON_TASK')
    }
    $seenIds = @()
    $seenExpectations = @()
    foreach ($case in $cases) {
        Assert-ExactProperties -Object $case -Expected @('id', 'content', 'expectation') -Path 'fixture.cases[]'
        $id = [string](Get-JsonProperty -Object $case -Name 'id' -Path 'fixture.cases[]')
        $content = [string](Get-JsonProperty -Object $case -Name 'content' -Path "fixture.cases[$id]")
        $expectation = [string](Get-JsonProperty -Object $case -Name 'expectation' -Path "fixture.cases[$id]")
        if (-not $expectedCases.ContainsKey($id)) {
            Stop-ContractValidation "fixture contains unexpected case id '$id'."
        }
        Assert-Equal -Actual (Get-Utf8Sha256 -Value $content) -Expected $expectedCases[$id][0] -Path "fixture.cases[$id].contentSha256"
        Assert-Equal -Actual $expectation -Expected $expectedCases[$id][1] -Path "fixture.cases[$id].expectation"
        $seenIds += $id
        $seenExpectations += $expectation
    }
    Assert-ExactSet -Actual $seenIds -Expected @($expectedCases.Keys) -Path 'fixture case ids'
    Assert-ExactSet -Actual $seenExpectations -Expected @('AFFIRMATIVE_TASK_UNKNOWN_TIME', 'NEGATED_NON_TASK', 'DESCRIPTIVE_NON_TASK') -Path 'fixture expectations'
}

function Assert-ReceiptSchemaContract {
    param([Parameter(Mandatory = $true)][object] $Schema)

    Assert-ExactProperties -Object $Schema -Expected @('$schema', '$id', 'title', 'type', 'additionalProperties', 'required', 'properties', '$defs') -Path 'receiptSchema'
    Assert-Equal -Actual (Get-JsonProperty -Object $Schema -Name '$schema' -Path 'receiptSchema') -Expected 'https://json-schema.org/draft/2020-12/schema' -Path 'receiptSchema.$schema'
    Assert-Equal -Actual (Get-JsonProperty -Object $Schema -Name '$id' -Path 'receiptSchema') -Expected 'https://personal-memo.local/schemas/ai-preferred-product-smoke-receipt-v1.json' -Path 'receiptSchema.$id'
    Assert-Equal -Actual (Get-JsonProperty -Object $Schema -Name 'title' -Path 'receiptSchema') -Expected 'AiPreferredProductSmokeReceiptV1' -Path 'receiptSchema.title'
    Assert-Equal -Actual (Get-JsonProperty -Object $Schema -Name 'type' -Path 'receiptSchema') -Expected 'object' -Path 'receiptSchema.type'
    Assert-Boolean -Actual (Get-JsonProperty -Object $Schema -Name 'additionalProperties' -Path 'receiptSchema') -Expected $false -Path 'receiptSchema.additionalProperties'

    $rootNames = @('schemaVersion', 'fixtureId', 'status', 'classification', 'decision', 'trainingDecision', 'loraDecision', 'ragStatus', 'automaticApply', 'recordedAt', 'scope', 'source', 'model', 'fake', 'liquidAi', 'comparison', 'gpu', 'cleanup')
    Assert-ExactSet -Actual @((Get-JsonProperty -Object $Schema -Name 'required' -Path 'receiptSchema')) -Expected $rootNames -Path 'receiptSchema.required'
    $properties = Get-JsonProperty -Object $Schema -Name 'properties' -Path 'receiptSchema'
    Assert-ExactProperties -Object $properties -Expected $rootNames -Path 'receiptSchema.properties'
    $rootConstants = @{
        schemaVersion = 1
        fixtureId = 'ai-preferred-product-smoke-v1'
        status = 'PASS_NARROW_PRODUCT_PATH'
        classification = 'SOLO_PROVISIONAL/REPORT_ONLY'
        decision = 'NO_GO'
        trainingDecision = 'NO_GO_FOR_TRAINING'
        loraDecision = 'NO_GO'
        ragStatus = 'NOT_USED'
        automaticApply = $false
    }
    foreach ($name in $rootConstants.Keys) {
        Assert-SchemaConst -Properties $properties -Name ([string]$name) -Expected $rootConstants[$name] -Path 'receiptSchema.properties'
    }
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $properties -Name 'recordedAt' -Path 'receiptSchema.properties') -Expected @{ type = 'string'; format = 'date-time' } -Path 'receiptSchema.properties.recordedAt'
    foreach ($name in @('scope', 'source', 'model', 'comparison', 'gpu', 'cleanup')) {
        Assert-SchemaRef -Properties $properties -Name $name -Expected (('#/$defs/{0}' -f $name)) -Path 'receiptSchema.properties'
    }

    foreach ($armName in @('fake', 'liquidAi')) {
        $armSchema = Get-JsonProperty -Object $properties -Name $armName -Path 'receiptSchema.properties'
        Assert-ExactProperties -Object $armSchema -Expected @('allOf') -Path "receiptSchema.properties.$armName"
        $allOf = @((Get-JsonProperty -Object $armSchema -Name 'allOf' -Path "receiptSchema.properties.$armName"))
        if ($allOf.Count -ne 2) {
            Stop-ContractValidation "receiptSchema.properties.$armName.allOf must contain exactly two clauses."
        }
        Assert-ScalarSchema -Definition $allOf[0] -Expected @{ '$ref' = '#/$defs/arm' } -Path "receiptSchema.properties.$armName.allOf[0]"
        Assert-ExactProperties -Object $allOf[1] -Expected @('properties') -Path "receiptSchema.properties.$armName.allOf[1]"
        $overrideProperties = Get-JsonProperty -Object $allOf[1] -Name 'properties' -Path "receiptSchema.properties.$armName.allOf[1]"
        Assert-ExactProperties -Object $overrideProperties -Expected @('invocationMode', 'transferMode', 'modelTokenEvidence') -Path "receiptSchema.properties.$armName.allOf[1].properties"
        if ($armName -eq 'fake') {
            $armConstants = @{ invocationMode = 'UNCERTAINTY_ONLY'; transferMode = 'NO_NETWORK'; modelTokenEvidence = 'NOT_APPLICABLE_ONLY' }
        } else {
            $armConstants = @{ invocationMode = 'AI_PREFERRED'; transferMode = 'LOCAL_MACHINE_MEMO_CONTENT'; modelTokenEvidence = 'NOT_REPORTED_OR_FALLBACK' }
        }
        foreach ($name in $armConstants.Keys) {
            Assert-SchemaConst -Properties $overrideProperties -Name ([string]$name) -Expected $armConstants[$name] -Path "receiptSchema.properties.$armName.allOf[1].properties"
        }
    }

    $defs = Get-JsonProperty -Object $Schema -Name '$defs' -Path 'receiptSchema'
    $defNames = @('scope', 'source', 'model', 'arm', 'latency', 'safety', 'canonicalWriteDelta', 'comparison', 'gpu', 'cleanup', 'threeCount', 'memoryMiB', 'sha256')
    Assert-ExactProperties -Object $defs -Expected $defNames -Path 'receiptSchema.$defs'

    $scopeNames = @('dataClass', 'personalMemoAccessed', 'personalPostgresAccessed', 'personalCanonicalDataAccessed', 'productApplyEndpointCalled', 'externalProductServiceAccessed', 'alarmReminderCalled')
    $scopeProperties = Assert-SchemaObjectDefinition -Definition (Get-JsonProperty -Object $defs -Name 'scope' -Path 'receiptSchema.$defs') -PropertyNames $scopeNames -Path 'receiptSchema.$defs.scope'
    Assert-SchemaConst -Properties $scopeProperties -Name 'dataClass' -Expected 'PUBLIC_SYNTHETIC_ONLY' -Path 'receiptSchema.$defs.scope.properties'
    foreach ($name in $scopeNames | Where-Object { $_ -ne 'dataClass' }) {
        Assert-SchemaConst -Properties $scopeProperties -Name $name -Expected $false -Path 'receiptSchema.$defs.scope.properties'
    }

    $sourceNames = @('gitCommit', 'dirty', 'backendImageId', 'composeSha256', 'fixtureSha256', 'fixtureSchemaSha256', 'receiptSchemaSha256', 'orchestratorSha256', 'samplerSha256')
    $sourceProperties = Assert-SchemaObjectDefinition -Definition (Get-JsonProperty -Object $defs -Name 'source' -Path 'receiptSchema.$defs') -PropertyNames $sourceNames -Path 'receiptSchema.$defs.source'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $sourceProperties -Name 'gitCommit' -Path 'receiptSchema.$defs.source.properties') -Expected @{ type = 'string'; pattern = '^[0-9a-f]{40}$' } -Path 'receiptSchema.$defs.source.properties.gitCommit'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $sourceProperties -Name 'dirty' -Path 'receiptSchema.$defs.source.properties') -Expected @{ type = 'boolean' } -Path 'receiptSchema.$defs.source.properties.dirty'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $sourceProperties -Name 'backendImageId' -Path 'receiptSchema.$defs.source.properties') -Expected @{ type = 'string'; pattern = '^sha256:[0-9a-f]{64}$' } -Path 'receiptSchema.$defs.source.properties.backendImageId'
    foreach ($name in @('composeSha256', 'fixtureSha256', 'fixtureSchemaSha256', 'receiptSchemaSha256', 'orchestratorSha256', 'samplerSha256')) {
        Assert-SchemaRef -Properties $sourceProperties -Name $name -Expected '#/$defs/sha256' -Path 'receiptSchema.$defs.source.properties'
    }

    $modelNames = @('endpointClass', 'ollamaVersion', 'tag', 'digest', 'gateway', 'contextLength', 'preloadedModelCount', 'postloadedModelCount')
    $modelProperties = Assert-SchemaObjectDefinition -Definition (Get-JsonProperty -Object $defs -Name 'model' -Path 'receiptSchema.$defs') -PropertyNames $modelNames -Path 'receiptSchema.$defs.model'
    $modelConstants = @{
        endpointClass = 'OWNED_LOOPBACK_127_0_0_1_11435'
        ollamaVersion = '0.32.7'
        tag = 'hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0'
        digest = '677b7229e7816d6bbdf3f7b777a5321f9719ecd3ab6e2658a2ff3798d3185822'
        gateway = 'ollama-local-gateway-v2+local-semantic-patch-v2'
        contextLength = 4096
        preloadedModelCount = 0
        postloadedModelCount = 0
    }
    foreach ($name in $modelConstants.Keys) {
        Assert-SchemaConst -Properties $modelProperties -Name ([string]$name) -Expected $modelConstants[$name] -Path 'receiptSchema.$defs.model.properties'
    }

    $armNames = @('invocationMode', 'transferMode', 'caseCount', 'reviewRequiredCount', 'schemaDomainAcceptedCount', 'cloudSuccessCount', 'acceptedChangedCount', 'acceptedUnchangedCount', 'localFallbackCount', 'toolCallCount', 'mutationCallCount', 'automaticApplyRequestCount', 'wallLatencyMilliseconds', 'attemptLatencyMilliseconds', 'safety', 'canonicalWriteDelta', 'modelTokenEvidence')
    $armProperties = Assert-SchemaObjectDefinition -Definition (Get-JsonProperty -Object $defs -Name 'arm' -Path 'receiptSchema.$defs') -PropertyNames $armNames -Path 'receiptSchema.$defs.arm'
    foreach ($name in @('caseCount', 'reviewRequiredCount', 'schemaDomainAcceptedCount')) {
        Assert-SchemaConst -Properties $armProperties -Name $name -Expected 3 -Path 'receiptSchema.$defs.arm.properties'
    }
    foreach ($name in @('toolCallCount', 'mutationCallCount', 'automaticApplyRequestCount')) {
        Assert-SchemaConst -Properties $armProperties -Name $name -Expected 0 -Path 'receiptSchema.$defs.arm.properties'
    }
    foreach ($name in @('cloudSuccessCount', 'acceptedChangedCount', 'acceptedUnchangedCount', 'localFallbackCount')) {
        Assert-SchemaRef -Properties $armProperties -Name $name -Expected '#/$defs/threeCount' -Path 'receiptSchema.$defs.arm.properties'
    }
    foreach ($name in @('wallLatencyMilliseconds', 'attemptLatencyMilliseconds')) {
        Assert-SchemaRef -Properties $armProperties -Name $name -Expected '#/$defs/latency' -Path 'receiptSchema.$defs.arm.properties'
    }
    Assert-SchemaRef -Properties $armProperties -Name 'safety' -Expected '#/$defs/safety' -Path 'receiptSchema.$defs.arm.properties'
    Assert-SchemaRef -Properties $armProperties -Name 'canonicalWriteDelta' -Expected '#/$defs/canonicalWriteDelta' -Path 'receiptSchema.$defs.arm.properties'
    foreach ($enumContract in @(
            @{ Name = 'invocationMode'; Values = @('UNCERTAINTY_ONLY', 'AI_PREFERRED') },
            @{ Name = 'transferMode'; Values = @('NO_NETWORK', 'LOCAL_MACHINE_MEMO_CONTENT') },
            @{ Name = 'modelTokenEvidence'; Values = @('NOT_APPLICABLE_ONLY', 'NOT_REPORTED_OR_FALLBACK') }
        )) {
        $enumDefinition = Get-JsonProperty -Object $armProperties -Name $enumContract.Name -Path 'receiptSchema.$defs.arm.properties'
        Assert-ExactProperties -Object $enumDefinition -Expected @('enum') -Path (('receiptSchema.$defs.arm.properties.{0}' -f $enumContract.Name))
        Assert-ExactSet -Actual @((Get-JsonProperty -Object $enumDefinition -Name 'enum' -Path (('receiptSchema.$defs.arm.properties.{0}' -f $enumContract.Name)))) -Expected @($enumContract.Values) -Path (('receiptSchema.$defs.arm.properties.{0}.enum' -f $enumContract.Name))
    }

    $latencyNames = @('min', 'median', 'max', 'mean')
    $latencyProperties = Assert-SchemaObjectDefinition -Definition (Get-JsonProperty -Object $defs -Name 'latency' -Path 'receiptSchema.$defs') -PropertyNames $latencyNames -Path 'receiptSchema.$defs.latency'
    foreach ($name in $latencyNames) {
        Assert-ScalarSchema -Definition (Get-JsonProperty -Object $latencyProperties -Name $name -Path 'receiptSchema.$defs.latency.properties') -Expected @{ type = 'integer'; minimum = 0; maximum = 60000 } -Path (('receiptSchema.$defs.latency.properties.{0}' -f $name))
    }

    $safetyNames = @('affirmativeTaskPassCount', 'negativeTaskPromotionCount', 'inventedPreciseDateCount', 'unresolvedHallucinationCount')
    $safetyProperties = Assert-SchemaObjectDefinition -Definition (Get-JsonProperty -Object $defs -Name 'safety' -Path 'receiptSchema.$defs') -PropertyNames $safetyNames -Path 'receiptSchema.$defs.safety'
    Assert-SchemaConst -Properties $safetyProperties -Name 'affirmativeTaskPassCount' -Expected 1 -Path 'receiptSchema.$defs.safety.properties'
    foreach ($name in $safetyNames | Where-Object { $_ -ne 'affirmativeTaskPassCount' }) {
        Assert-SchemaConst -Properties $safetyProperties -Name $name -Expected 0 -Path 'receiptSchema.$defs.safety.properties'
    }

    $canonicalNames = @('applications', 'memoItems', 'taskDetails', 'eventDetails', 'tags', 'tagAliases', 'itemTags', 'relations', 'calendarFeeds', 'calendarFeedEntries')
    $canonicalProperties = Assert-SchemaObjectDefinition -Definition (Get-JsonProperty -Object $defs -Name 'canonicalWriteDelta' -Path 'receiptSchema.$defs') -PropertyNames $canonicalNames -Path 'receiptSchema.$defs.canonicalWriteDelta'
    foreach ($name in $canonicalNames) {
        Assert-SchemaConst -Properties $canonicalProperties -Name $name -Expected 0 -Path 'receiptSchema.$defs.canonicalWriteDelta.properties'
    }

    $comparisonNames = @('pairedCaseCount', 'medianWallDeltaMilliseconds', 'liquidToFakeMedianWallRatio', 'semanticImprovement')
    $comparisonProperties = Assert-SchemaObjectDefinition -Definition (Get-JsonProperty -Object $defs -Name 'comparison' -Path 'receiptSchema.$defs') -PropertyNames $comparisonNames -Path 'receiptSchema.$defs.comparison'
    Assert-SchemaConst -Properties $comparisonProperties -Name 'pairedCaseCount' -Expected 3 -Path 'receiptSchema.$defs.comparison.properties'
    Assert-SchemaConst -Properties $comparisonProperties -Name 'semanticImprovement' -Expected 'NOT_DEMONSTRATED' -Path 'receiptSchema.$defs.comparison.properties'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $comparisonProperties -Name 'medianWallDeltaMilliseconds' -Path 'receiptSchema.$defs.comparison.properties') -Expected @{ type = 'integer'; minimum = 0; maximum = 60000 } -Path 'receiptSchema.$defs.comparison.properties.medianWallDeltaMilliseconds'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $comparisonProperties -Name 'liquidToFakeMedianWallRatio' -Path 'receiptSchema.$defs.comparison.properties') -Expected @{ type = 'number'; minimum = 0; maximum = 10000 } -Path 'receiptSchema.$defs.comparison.properties.liquidToFakeMedianWallRatio'

    $gpuNames = @('scope', 'sampleCount', 'sampleMissCount', 'baselineUsedMiB', 'maxUsedMiB', 'postUsedMiB', 'maxUtilizationPercent', 'loadedModelObserved', 'maxOllamaVramBytes', 'contextLength')
    $gpuProperties = Assert-SchemaObjectDefinition -Definition (Get-JsonProperty -Object $defs -Name 'gpu' -Path 'receiptSchema.$defs') -PropertyNames $gpuNames -Path 'receiptSchema.$defs.gpu'
    Assert-SchemaConst -Properties $gpuProperties -Name 'scope' -Expected 'DEVICE_WIDE_NON_EXCLUSIVE' -Path 'receiptSchema.$defs.gpu.properties'
    Assert-SchemaConst -Properties $gpuProperties -Name 'loadedModelObserved' -Expected $true -Path 'receiptSchema.$defs.gpu.properties'
    Assert-SchemaConst -Properties $gpuProperties -Name 'contextLength' -Expected 4096 -Path 'receiptSchema.$defs.gpu.properties'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $gpuProperties -Name 'sampleCount' -Path 'receiptSchema.$defs.gpu.properties') -Expected @{ type = 'integer'; minimum = 1; maximum = 100000 } -Path 'receiptSchema.$defs.gpu.properties.sampleCount'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $gpuProperties -Name 'sampleMissCount' -Path 'receiptSchema.$defs.gpu.properties') -Expected @{ type = 'integer'; minimum = 0; maximum = 100000 } -Path 'receiptSchema.$defs.gpu.properties.sampleMissCount'
    foreach ($name in @('baselineUsedMiB', 'maxUsedMiB', 'postUsedMiB')) {
        Assert-SchemaRef -Properties $gpuProperties -Name $name -Expected '#/$defs/memoryMiB' -Path 'receiptSchema.$defs.gpu.properties'
    }
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $gpuProperties -Name 'maxUtilizationPercent' -Path 'receiptSchema.$defs.gpu.properties') -Expected @{ type = 'integer'; minimum = 0; maximum = 100 } -Path 'receiptSchema.$defs.gpu.properties.maxUtilizationPercent'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $gpuProperties -Name 'maxOllamaVramBytes' -Path 'receiptSchema.$defs.gpu.properties') -Expected @{ type = 'integer'; minimum = 1 } -Path 'receiptSchema.$defs.gpu.properties.maxOllamaVramBytes'

    $cleanupNames = @('fakeProjectContainerCount', 'fakeProjectNetworkCount', 'fakeProjectVolumeCount', 'liquidProjectContainerCount', 'liquidProjectNetworkCount', 'liquidProjectVolumeCount', 'ownedOllamaProcessCount', 'ownedOllamaListenerCount', 'tempArtifactCount', 'personalProjectContainerCountBefore', 'personalProjectContainerCountAfter', 'personalProjectContainerCountUnchanged', 'defaultOllamaEndpointAccessed', 'restored')
    $cleanupProperties = Assert-SchemaObjectDefinition -Definition (Get-JsonProperty -Object $defs -Name 'cleanup' -Path 'receiptSchema.$defs') -PropertyNames $cleanupNames -Path 'receiptSchema.$defs.cleanup'
    foreach ($name in @('fakeProjectContainerCount', 'fakeProjectNetworkCount', 'fakeProjectVolumeCount', 'liquidProjectContainerCount', 'liquidProjectNetworkCount', 'liquidProjectVolumeCount', 'ownedOllamaProcessCount', 'ownedOllamaListenerCount', 'tempArtifactCount')) {
        Assert-SchemaConst -Properties $cleanupProperties -Name $name -Expected 0 -Path 'receiptSchema.$defs.cleanup.properties'
    }
    foreach ($name in @('personalProjectContainerCountUnchanged', 'restored')) {
        Assert-SchemaConst -Properties $cleanupProperties -Name $name -Expected $true -Path 'receiptSchema.$defs.cleanup.properties'
    }
    Assert-SchemaConst -Properties $cleanupProperties -Name 'defaultOllamaEndpointAccessed' -Expected $false -Path 'receiptSchema.$defs.cleanup.properties'
    foreach ($name in @('personalProjectContainerCountBefore', 'personalProjectContainerCountAfter')) {
        Assert-ScalarSchema -Definition (Get-JsonProperty -Object $cleanupProperties -Name $name -Path 'receiptSchema.$defs.cleanup.properties') -Expected @{ type = 'integer'; minimum = 0; maximum = 100 } -Path (('receiptSchema.$defs.cleanup.properties.{0}' -f $name))
    }

    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $defs -Name 'threeCount' -Path 'receiptSchema.$defs') -Expected @{ type = 'integer'; minimum = 0; maximum = 3 } -Path 'receiptSchema.$defs.threeCount'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $defs -Name 'memoryMiB' -Path 'receiptSchema.$defs') -Expected @{ type = 'integer'; minimum = 0; maximum = 262144 } -Path 'receiptSchema.$defs.memoryMiB'
    Assert-ScalarSchema -Definition (Get-JsonProperty -Object $defs -Name 'sha256' -Path 'receiptSchema.$defs') -Expected @{ type = 'string'; pattern = '^[0-9a-f]{64}$' } -Path 'receiptSchema.$defs.sha256'
}

function Assert-TextMatches {
    param(
        [Parameter(Mandatory = $true)][string] $Text,
        [Parameter(Mandatory = $true)][string] $Pattern,
        [Parameter(Mandatory = $true)][string] $Message
    )

    $options = [Text.RegularExpressions.RegexOptions]::IgnoreCase -bor [Text.RegularExpressions.RegexOptions]::Multiline -bor [Text.RegularExpressions.RegexOptions]::CultureInvariant
    if (-not [regex]::IsMatch($Text, $Pattern, $options)) {
        Stop-ContractValidation $Message
    }
}

function Assert-TextDoesNotMatch {
    param(
        [Parameter(Mandatory = $true)][string] $Text,
        [Parameter(Mandatory = $true)][string] $Pattern,
        [Parameter(Mandatory = $true)][string] $Message
    )

    $options = [Text.RegularExpressions.RegexOptions]::IgnoreCase -bor [Text.RegularExpressions.RegexOptions]::Multiline -bor [Text.RegularExpressions.RegexOptions]::CultureInvariant
    if ([regex]::IsMatch($Text, $Pattern, $options)) {
        Stop-ContractValidation $Message
    }
}

function Assert-ComposeSourceSafety {
    param([Parameter(Mandatory = $true)][string] $ComposePath)

    $text = [IO.File]::ReadAllText($ComposePath, [Text.Encoding]::UTF8)
    $serviceMatches = @([regex]::Matches($text, '(?m)^  (?<name>[a-z][a-z0-9_-]*):\s*$'))
    $serviceNames = @($serviceMatches | ForEach-Object { $_.Groups['name'].Value })
    Assert-ExactSet -Actual $serviceNames -Expected @('postgres', 'backend') -Path 'compose services'

    Assert-TextMatches -Text $text -Pattern '(?m)^\s+image:\s*postgres:17\.6-alpine\s*$' -Message 'compose must pin the disposable PostgreSQL image.'
    Assert-TextMatches -Text $text -Pattern '(?m)^\s+tmpfs:\s*\r?$[\s\S]*?^\s+-\s*/var/lib/postgresql/data\s*$' -Message 'compose PostgreSQL storage must use tmpfs.'
    Assert-TextMatches -Text $text -Pattern '127\.0\.0\.1:\$\{AI_PRODUCT_SMOKE_BACKEND_PORT:\?[^}]+\}:8080' -Message 'compose backend port must bind to loopback only.'
    Assert-TextMatches -Text $text -Pattern 'http://host\.docker\.internal:11435' -Message 'compose must use the dedicated Ollama relay port.'
    Assert-TextMatches -Text $text -Pattern 'APP_CALENDAR_FEED_PUBLICATION_ENABLED:\s*"false"' -Message 'compose must disable public calendar-feed publication.'
    Assert-TextMatches -Text $text -Pattern 'APP_CALENDAR_FEED_PUBLIC_ORIGIN:\s*""' -Message 'compose public calendar-feed origin must be blank.'
    Assert-TextMatches -Text $text -Pattern '(?m)^\s+pull_policy:\s*never\s*$' -Message 'compose must not pull a replacement backend image.'
    Assert-TextMatches -Text $text -Pattern '(?m)^\s+read_only:\s*true\s*$' -Message 'compose backend must use a read-only root filesystem.'

    $postgresBlock = [regex]::Match($text, '(?ms)^  postgres:\s*\r?\n(?<body>.*?)(?=^  backend:\s*$)')
    if (-not $postgresBlock.Success) {
        Stop-ContractValidation 'compose postgres service block could not be bounded.'
    }
    Assert-TextDoesNotMatch -Text $postgresBlock.Groups['body'].Value -Pattern '(?m)^\s+ports:\s*$' -Message 'compose PostgreSQL must not publish a host port.'
    Assert-TextDoesNotMatch -Text $postgresBlock.Groups['body'].Value -Pattern '(?m)^\s+volumes:\s*$' -Message 'compose PostgreSQL must not use a persistent volume.'

    $defaultPort = '114' + '34'
    $forbiddenPatterns = @(
        [regex]::Escape($defaultPort),
        '(?i)env_file\s*:',
        '(?i)\.env\.(?:personal|private|production)',
        '(?i)compose\.personal',
        '(?i)PrivateTls',
        '(?i)cloudflared?',
        '(?i)calendar\.junpyo\.net',
        '(?i)network_mode\s*:\s*host',
        '(?i)^\s*volumes:\s*$'
    )
    foreach ($pattern in $forbiddenPatterns) {
        Assert-TextDoesNotMatch -Text $text -Pattern $pattern -Message "compose contains a forbidden personal, public, persistent, or default-endpoint reference ($pattern)."
    }
}

function Assert-PowerShellSources {
    param([Parameter(Mandatory = $true)][string] $AnalysisDirectory)

    $scripts = @(Get-ChildItem -LiteralPath $AnalysisDirectory -Filter '*.ps1' -File | Sort-Object Name)
    if ($scripts.Count -lt 3) {
        Stop-ContractValidation 'scripts/analysis must contain the orchestrator, GPU sampler, and source-contract validator.'
    }

    foreach ($script in $scripts) {
        $tokens = $null
        $errors = $null
        [void][Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$tokens, [ref]$errors)
        if (@($errors).Count -ne 0) {
            $locations = @($errors | ForEach-Object { "$($_.Extent.StartLineNumber):$($_.Message)" }) -join '; '
            Stop-ContractValidation "$($script.Name) has PowerShell parse errors: $locations"
        }
    }

    $orchestratorPath = Join-Path $AnalysisDirectory 'Invoke-PersonalMemoAiPreferredSyntheticSmoke.ps1'
    $samplerPath = Join-Path $AnalysisDirectory 'Measure-PersonalMemoAiProductSmokeGpu.ps1'
    foreach ($requiredPath in @($orchestratorPath, $samplerPath)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            Stop-ContractValidation "required analysis script is missing: $([IO.Path]::GetFileName($requiredPath))."
        }
    }

    $orchestrator = [IO.File]::ReadAllText($orchestratorPath, [Text.Encoding]::UTF8)
    $sampler = [IO.File]::ReadAllText($samplerPath, [Text.Encoding]::UTF8)
    $scanText = $orchestrator + [Environment]::NewLine + $sampler
    $dedicatedPort = '114' + '35'
    $defaultPort = '114' + '34'

    foreach ($required in @(
            @{ Pattern = [regex]::Escape($dedicatedPort); Message = 'orchestrator must bind the owned dedicated Ollama port.' },
            @{ Pattern = 'hf\.co/LiquidAI/LFM2\.5-2\.6B-GGUF:Q8_0'; Message = 'orchestrator must pin the exact LiquidAI model tag.' },
            @{ Pattern = '677b7229e7816d6bbdf3f7b777a5321f9719ecd3ab6e2658a2ff3798d3185822'; Message = 'orchestrator must pin the exact LiquidAI digest.' },
            @{ Pattern = 'SupportsShouldProcess\s*=\s*\$true'; Message = 'orchestrator must require explicit high-impact confirmation support.' },
            @{ Pattern = 'ConfirmImpact\s*=\s*["'']High["'']'; Message = 'orchestrator confirmation impact must be High.' },
            @{ Pattern = '(?m)^\s*finally\s*\{'; Message = 'orchestrator must use a finally cleanup boundary.' },
            @{ Pattern = '(?:--project-name|-p)'; Message = 'orchestrator must scope Compose commands to an explicit project.' },
            @{ Pattern = '(?i)\bfake\w*project|project\w*fake'; Message = 'orchestrator must maintain a distinct Fake project identity.' },
            @{ Pattern = '(?i)\bliquid\w*project|project\w*liquid'; Message = 'orchestrator must maintain a distinct LiquidAI project identity.' },
            @{ Pattern = '(?i)\bdown\b'; Message = 'orchestrator must tear down owned Compose projects.' },
            @{ Pattern = '--volumes'; Message = 'orchestrator cleanup must remove owned Compose volumes.' },
            @{ Pattern = '--remove-orphans'; Message = 'orchestrator cleanup must remove owned Compose orphans.' },
            @{ Pattern = '(?i)Stop-Process'; Message = 'orchestrator must stop its owned Ollama process.' },
            @{ Pattern = '(?i)Remove-Item'; Message = 'orchestrator must remove its exact temporary artifacts.' },
            @{ Pattern = 'externalProductServiceAccessed\s*=\s*\$false'; Message = 'orchestrator receipt scope must deny external product-service access.' },
            @{ Pattern = 'NON_TASK_EXPECTATION_FAILED'; Message = 'orchestrator must retain a bounded negative-case failure gate.' },
            @{ Pattern = '\bactionableItems\b'; Message = 'negative-case gate must reject actionable TASK or EVENT items.' },
            @{ Pattern = '\bpopulatedNonTaskFields\b'; Message = 'negative-case gate must reject populated action, object, or due-date fields.' }
        )) {
        Assert-TextMatches -Text $orchestrator -Pattern $required.Pattern -Message $required.Message
    }

    # Fresh checkouts normalize PowerShell sources to CRLF; consume the optional
    # carriage return so the bounded-line contract is identical for LF and CRLF.
    $composeInvocationLines = @([regex]::Matches($orchestrator, '(?im)^[^\r\n]*["'']compose["''][^\r\n]*\r?$'))
    if ($composeInvocationLines.Count -eq 0) {
        Stop-ContractValidation 'orchestrator must contain a bounded Compose invocation.'
    }
    foreach ($composeInvocationLine in $composeInvocationLines) {
        if ($composeInvocationLine.Value -notmatch '--project-name') {
            Stop-ContractValidation 'every literal Compose invocation must carry --project-name on the same bounded argument line.'
        }
    }
    Assert-TextMatches -Text $orchestrator -Pattern 'pm-ai-product-smoke-f-' -Message 'orchestrator Fake project names must use the owned synthetic prefix.'
    Assert-TextMatches -Text $orchestrator -Pattern 'pm-ai-product-smoke-l-' -Message 'orchestrator LiquidAI project names must use the owned synthetic prefix.'
    Assert-TextMatches -Text $orchestrator -Pattern 'Assert-OwnedProcessIdentity' -Message 'orchestrator must verify owned-process identity before process cleanup.'

    foreach ($required in @(
            @{ Pattern = 'sampleCount'; Message = 'GPU sampler must emit only a bounded aggregate sample count.' },
            @{ Pattern = 'sampleMissCount'; Message = 'GPU sampler must aggregate sample misses.' },
            @{ Pattern = 'maxUsedMiB'; Message = 'GPU sampler must aggregate device memory.' },
            @{ Pattern = 'maxUtilizationPercent'; Message = 'GPU sampler must aggregate utilization.' },
            @{ Pattern = 'maxOllamaVramBytes'; Message = 'GPU sampler must aggregate Ollama VRAM.' }
        )) {
        Assert-TextMatches -Text $sampler -Pattern $required.Pattern -Message $required.Message
    }

    $forbiddenPatterns = @(
        [regex]::Escape($defaultPort),
        '(?i)\.env\.(?:personal|private|production)',
        '(?i)compose\.personal',
        '(?i)PrivateTls',
        '(?i)scripts[\\/]+personal',
        '(?i)cloudflared?',
        '(?i)calendar\.junpyo\.net',
        '(?i)/api/v1/calendar-feeds',
        '(?i)/(?:apply|reject|postpone)(?:[/?"''\s]|$)',
        '(?i)docker\s+(?:system|container|volume|network|image)\s+prune',
        '(?i)Get-Process\s+(?:-Name\s+)?["'']?ollama["'']?\s*\|\s*Stop-Process',
        '(?i)Stop-Process\s+-Name\s+["'']?ollama',
        '(?i)\bpublicServiceAccessed\b',
        '(?i)Write-(?:Host|Output|Verbose|Debug|Information)[^\r\n]*(?:memo\s*body|content|bearer|password|session|csrf)'
    )
    foreach ($pattern in $forbiddenPatterns) {
        Assert-TextDoesNotMatch -Text $scanText -Pattern $pattern -Message "analysis scripts contain a forbidden personal, public, mutation, broad-cleanup, raw-output, or default-endpoint pattern ($pattern)."
    }
}

function Assert-LatencyReceipt {
    param(
        [Parameter(Mandatory = $true)][object] $Latency,
        [Parameter(Mandatory = $true)][string] $Path
    )

    Assert-ExactProperties -Object $Latency -Expected @('min', 'median', 'max', 'mean') -Path $Path
    foreach ($name in @('min', 'median', 'max', 'mean')) {
        Assert-IntegerRange -Actual (Get-JsonProperty -Object $Latency -Name $name -Path $Path) -Minimum 0 -Maximum 60000 -Path "$Path.$name"
    }
    $minimum = [long](Get-JsonProperty -Object $Latency -Name 'min' -Path $Path)
    $median = [long](Get-JsonProperty -Object $Latency -Name 'median' -Path $Path)
    $maximum = [long](Get-JsonProperty -Object $Latency -Name 'max' -Path $Path)
    $mean = [long](Get-JsonProperty -Object $Latency -Name 'mean' -Path $Path)
    if ($minimum -gt $median -or $median -gt $maximum -or $mean -lt $minimum -or $mean -gt $maximum) {
        Stop-ContractValidation "$Path aggregate ordering is invalid."
    }
}

function Assert-ArmReceipt {
    param(
        [Parameter(Mandatory = $true)][object] $Arm,
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $InvocationMode,
        [Parameter(Mandatory = $true)][string] $TransferMode,
        [Parameter(Mandatory = $true)][string] $ModelTokenEvidence
    )

    $names = @('invocationMode', 'transferMode', 'caseCount', 'reviewRequiredCount', 'schemaDomainAcceptedCount', 'cloudSuccessCount', 'acceptedChangedCount', 'acceptedUnchangedCount', 'localFallbackCount', 'toolCallCount', 'mutationCallCount', 'automaticApplyRequestCount', 'wallLatencyMilliseconds', 'attemptLatencyMilliseconds', 'safety', 'canonicalWriteDelta', 'modelTokenEvidence')
    Assert-ExactProperties -Object $Arm -Expected $names -Path $Path
    Assert-Equal -Actual (Get-JsonProperty -Object $Arm -Name 'invocationMode' -Path $Path) -Expected $InvocationMode -Path "$Path.invocationMode"
    Assert-Equal -Actual (Get-JsonProperty -Object $Arm -Name 'transferMode' -Path $Path) -Expected $TransferMode -Path "$Path.transferMode"
    Assert-Equal -Actual (Get-JsonProperty -Object $Arm -Name 'modelTokenEvidence' -Path $Path) -Expected $ModelTokenEvidence -Path "$Path.modelTokenEvidence"
    foreach ($name in @('caseCount', 'reviewRequiredCount', 'schemaDomainAcceptedCount')) {
        Assert-Equal -Actual (Get-JsonProperty -Object $Arm -Name $name -Path $Path) -Expected 3 -Path "$Path.$name"
    }
    foreach ($name in @('cloudSuccessCount', 'acceptedChangedCount', 'acceptedUnchangedCount', 'localFallbackCount')) {
        Assert-IntegerRange -Actual (Get-JsonProperty -Object $Arm -Name $name -Path $Path) -Minimum 0 -Maximum 3 -Path "$Path.$name"
    }
    foreach ($name in @('toolCallCount', 'mutationCallCount', 'automaticApplyRequestCount')) {
        Assert-Equal -Actual (Get-JsonProperty -Object $Arm -Name $name -Path $Path) -Expected 0 -Path "$Path.$name"
    }
    Assert-LatencyReceipt -Latency (Get-JsonProperty -Object $Arm -Name 'wallLatencyMilliseconds' -Path $Path) -Path "$Path.wallLatencyMilliseconds"
    Assert-LatencyReceipt -Latency (Get-JsonProperty -Object $Arm -Name 'attemptLatencyMilliseconds' -Path $Path) -Path "$Path.attemptLatencyMilliseconds"

    $safety = Get-JsonProperty -Object $Arm -Name 'safety' -Path $Path
    Assert-ExactProperties -Object $safety -Expected @('affirmativeTaskPassCount', 'negativeTaskPromotionCount', 'inventedPreciseDateCount', 'unresolvedHallucinationCount') -Path "$Path.safety"
    Assert-Equal -Actual (Get-JsonProperty -Object $safety -Name 'affirmativeTaskPassCount' -Path "$Path.safety") -Expected 1 -Path "$Path.safety.affirmativeTaskPassCount"
    foreach ($name in @('negativeTaskPromotionCount', 'inventedPreciseDateCount', 'unresolvedHallucinationCount')) {
        Assert-Equal -Actual (Get-JsonProperty -Object $safety -Name $name -Path "$Path.safety") -Expected 0 -Path "$Path.safety.$name"
    }

    $canonical = Get-JsonProperty -Object $Arm -Name 'canonicalWriteDelta' -Path $Path
    $canonicalNames = @('applications', 'memoItems', 'taskDetails', 'eventDetails', 'tags', 'tagAliases', 'itemTags', 'relations', 'calendarFeeds', 'calendarFeedEntries')
    Assert-ExactProperties -Object $canonical -Expected $canonicalNames -Path "$Path.canonicalWriteDelta"
    foreach ($name in $canonicalNames) {
        Assert-Equal -Actual (Get-JsonProperty -Object $canonical -Name $name -Path "$Path.canonicalWriteDelta") -Expected 0 -Path "$Path.canonicalWriteDelta.$name"
    }
}

function Assert-NoSensitiveReceiptMaterial {
    param(
        [Parameter()] $Node,
        [Parameter(Mandatory = $true)][string] $Path
    )

    if ($null -eq $Node) {
        return
    }

    if ($Node -is [Management.Automation.PSCustomObject]) {
        $forbiddenKeys = @('content', 'text', 'memo', 'memoBody', 'rawMemo', 'rawMemoBody', 'email', 'password', 'secret', 'session', 'sessionId', 'csrfToken', 'accessToken', 'refreshToken', 'authorization', 'bearer', 'cookie', 'oauthCode', 'providerToken')
        foreach ($property in $Node.PSObject.Properties) {
            if ($forbiddenKeys -contains $property.Name) {
                Stop-ContractValidation "$Path contains forbidden sensitive or raw-content key '$($property.Name)'."
            }
            Assert-NoSensitiveReceiptMaterial -Node $property.Value -Path "$Path.$($property.Name)"
        }
        return
    }

    if ($Node -is [Collections.IEnumerable] -and $Node -isnot [string]) {
        $index = 0
        foreach ($item in $Node) {
            Assert-NoSensitiveReceiptMaterial -Node $item -Path "$Path[$index]"
            $index++
        }
    }
}

function Assert-ReceiptContract {
    param(
        [Parameter(Mandatory = $true)][object] $Receipt,
        [Parameter(Mandatory = $true)][string] $RawReceipt,
        [Parameter(Mandatory = $true)][object] $Fixture
    )

    $rootNames = @('schemaVersion', 'fixtureId', 'status', 'classification', 'decision', 'trainingDecision', 'loraDecision', 'ragStatus', 'automaticApply', 'recordedAt', 'scope', 'source', 'model', 'fake', 'liquidAi', 'comparison', 'gpu', 'cleanup')
    Assert-ExactProperties -Object $Receipt -Expected $rootNames -Path 'receipt'
    $constants = @{
        schemaVersion = 1
        fixtureId = 'ai-preferred-product-smoke-v1'
        status = 'PASS_NARROW_PRODUCT_PATH'
        classification = 'SOLO_PROVISIONAL/REPORT_ONLY'
        decision = 'NO_GO'
        trainingDecision = 'NO_GO_FOR_TRAINING'
        loraDecision = 'NO_GO'
        ragStatus = 'NOT_USED'
    }
    foreach ($name in $constants.Keys) {
        Assert-Equal -Actual (Get-JsonProperty -Object $Receipt -Name ([string]$name) -Path 'receipt') -Expected $constants[$name] -Path "receipt.$name"
    }
    Assert-Boolean -Actual (Get-JsonProperty -Object $Receipt -Name 'automaticApply' -Path 'receipt') -Expected $false -Path 'receipt.automaticApply'
    Assert-DateTimeValue -Actual (Get-JsonProperty -Object $Receipt -Name 'recordedAt' -Path 'receipt') -Path 'receipt.recordedAt'

    $scope = Get-JsonProperty -Object $Receipt -Name 'scope' -Path 'receipt'
    $scopeNames = @('dataClass', 'personalMemoAccessed', 'personalPostgresAccessed', 'personalCanonicalDataAccessed', 'productApplyEndpointCalled', 'externalProductServiceAccessed', 'alarmReminderCalled')
    Assert-ExactProperties -Object $scope -Expected $scopeNames -Path 'receipt.scope'
    Assert-Equal -Actual (Get-JsonProperty -Object $scope -Name 'dataClass' -Path 'receipt.scope') -Expected 'PUBLIC_SYNTHETIC_ONLY' -Path 'receipt.scope.dataClass'
    foreach ($name in $scopeNames | Where-Object { $_ -ne 'dataClass' }) {
        Assert-Boolean -Actual (Get-JsonProperty -Object $scope -Name $name -Path 'receipt.scope') -Expected $false -Path "receipt.scope.$name"
    }

    $source = Get-JsonProperty -Object $Receipt -Name 'source' -Path 'receipt'
    $sourceNames = @('gitCommit', 'dirty', 'backendImageId', 'composeSha256', 'fixtureSha256', 'fixtureSchemaSha256', 'receiptSchemaSha256', 'orchestratorSha256', 'samplerSha256')
    Assert-ExactProperties -Object $source -Expected $sourceNames -Path 'receipt.source'
    Assert-RegexValue -Actual (Get-JsonProperty -Object $source -Name 'gitCommit' -Path 'receipt.source') -Pattern '^[0-9a-f]{40}$' -Path 'receipt.source.gitCommit'
    Assert-Boolean -Actual (Get-JsonProperty -Object $source -Name 'dirty' -Path 'receipt.source') -Expected ([bool](Get-JsonProperty -Object $source -Name 'dirty' -Path 'receipt.source')) -Path 'receipt.source.dirty'
    Assert-RegexValue -Actual (Get-JsonProperty -Object $source -Name 'backendImageId' -Path 'receipt.source') -Pattern '^sha256:[0-9a-f]{64}$' -Path 'receipt.source.backendImageId'
    foreach ($name in @('composeSha256', 'fixtureSha256', 'fixtureSchemaSha256', 'receiptSchemaSha256', 'orchestratorSha256', 'samplerSha256')) {
        Assert-RegexValue -Actual (Get-JsonProperty -Object $source -Name $name -Path 'receipt.source') -Pattern '^[0-9a-f]{64}$' -Path "receipt.source.$name"
    }

    $model = Get-JsonProperty -Object $Receipt -Name 'model' -Path 'receipt'
    $modelNames = @('endpointClass', 'ollamaVersion', 'tag', 'digest', 'gateway', 'contextLength', 'preloadedModelCount', 'postloadedModelCount')
    Assert-ExactProperties -Object $model -Expected $modelNames -Path 'receipt.model'
    $modelConstants = @{
        endpointClass = 'OWNED_LOOPBACK_127_0_0_1_11435'
        ollamaVersion = '0.32.7'
        tag = 'hf.co/LiquidAI/LFM2.5-2.6B-GGUF:Q8_0'
        digest = '677b7229e7816d6bbdf3f7b777a5321f9719ecd3ab6e2658a2ff3798d3185822'
        gateway = 'ollama-local-gateway-v2+local-semantic-patch-v2'
        contextLength = 4096
        preloadedModelCount = 0
        postloadedModelCount = 0
    }
    foreach ($name in $modelConstants.Keys) {
        Assert-Equal -Actual (Get-JsonProperty -Object $model -Name ([string]$name) -Path 'receipt.model') -Expected $modelConstants[$name] -Path "receipt.model.$name"
    }

    Assert-ArmReceipt -Arm (Get-JsonProperty -Object $Receipt -Name 'fake' -Path 'receipt') -Path 'receipt.fake' -InvocationMode 'UNCERTAINTY_ONLY' -TransferMode 'NO_NETWORK' -ModelTokenEvidence 'NOT_APPLICABLE_ONLY'
    Assert-ArmReceipt -Arm (Get-JsonProperty -Object $Receipt -Name 'liquidAi' -Path 'receipt') -Path 'receipt.liquidAi' -InvocationMode 'AI_PREFERRED' -TransferMode 'LOCAL_MACHINE_MEMO_CONTENT' -ModelTokenEvidence 'NOT_REPORTED_OR_FALLBACK'

    $comparison = Get-JsonProperty -Object $Receipt -Name 'comparison' -Path 'receipt'
    Assert-ExactProperties -Object $comparison -Expected @('pairedCaseCount', 'medianWallDeltaMilliseconds', 'liquidToFakeMedianWallRatio', 'semanticImprovement') -Path 'receipt.comparison'
    Assert-Equal -Actual (Get-JsonProperty -Object $comparison -Name 'pairedCaseCount' -Path 'receipt.comparison') -Expected 3 -Path 'receipt.comparison.pairedCaseCount'
    Assert-IntegerRange -Actual (Get-JsonProperty -Object $comparison -Name 'medianWallDeltaMilliseconds' -Path 'receipt.comparison') -Minimum 0 -Maximum 60000 -Path 'receipt.comparison.medianWallDeltaMilliseconds'
    Assert-NumberRange -Actual (Get-JsonProperty -Object $comparison -Name 'liquidToFakeMedianWallRatio' -Path 'receipt.comparison') -Minimum 0 -Maximum 10000 -Path 'receipt.comparison.liquidToFakeMedianWallRatio'
    Assert-Equal -Actual (Get-JsonProperty -Object $comparison -Name 'semanticImprovement' -Path 'receipt.comparison') -Expected 'NOT_DEMONSTRATED' -Path 'receipt.comparison.semanticImprovement'

    $gpu = Get-JsonProperty -Object $Receipt -Name 'gpu' -Path 'receipt'
    $gpuNames = @('scope', 'sampleCount', 'sampleMissCount', 'baselineUsedMiB', 'maxUsedMiB', 'postUsedMiB', 'maxUtilizationPercent', 'loadedModelObserved', 'maxOllamaVramBytes', 'contextLength')
    Assert-ExactProperties -Object $gpu -Expected $gpuNames -Path 'receipt.gpu'
    Assert-Equal -Actual (Get-JsonProperty -Object $gpu -Name 'scope' -Path 'receipt.gpu') -Expected 'DEVICE_WIDE_NON_EXCLUSIVE' -Path 'receipt.gpu.scope'
    Assert-IntegerRange -Actual (Get-JsonProperty -Object $gpu -Name 'sampleCount' -Path 'receipt.gpu') -Minimum 1 -Maximum 100000 -Path 'receipt.gpu.sampleCount'
    Assert-IntegerRange -Actual (Get-JsonProperty -Object $gpu -Name 'sampleMissCount' -Path 'receipt.gpu') -Minimum 0 -Maximum 100000 -Path 'receipt.gpu.sampleMissCount'
    foreach ($name in @('baselineUsedMiB', 'maxUsedMiB', 'postUsedMiB')) {
        Assert-IntegerRange -Actual (Get-JsonProperty -Object $gpu -Name $name -Path 'receipt.gpu') -Minimum 0 -Maximum 262144 -Path "receipt.gpu.$name"
    }
    Assert-IntegerRange -Actual (Get-JsonProperty -Object $gpu -Name 'maxUtilizationPercent' -Path 'receipt.gpu') -Minimum 0 -Maximum 100 -Path 'receipt.gpu.maxUtilizationPercent'
    Assert-Boolean -Actual (Get-JsonProperty -Object $gpu -Name 'loadedModelObserved' -Path 'receipt.gpu') -Expected $true -Path 'receipt.gpu.loadedModelObserved'
    Assert-IntegerRange -Actual (Get-JsonProperty -Object $gpu -Name 'maxOllamaVramBytes' -Path 'receipt.gpu') -Minimum 1 -Maximum ([long]::MaxValue) -Path 'receipt.gpu.maxOllamaVramBytes'
    Assert-Equal -Actual (Get-JsonProperty -Object $gpu -Name 'contextLength' -Path 'receipt.gpu') -Expected 4096 -Path 'receipt.gpu.contextLength'

    $cleanup = Get-JsonProperty -Object $Receipt -Name 'cleanup' -Path 'receipt'
    $cleanupNames = @('fakeProjectContainerCount', 'fakeProjectNetworkCount', 'fakeProjectVolumeCount', 'liquidProjectContainerCount', 'liquidProjectNetworkCount', 'liquidProjectVolumeCount', 'ownedOllamaProcessCount', 'ownedOllamaListenerCount', 'tempArtifactCount', 'personalProjectContainerCountBefore', 'personalProjectContainerCountAfter', 'personalProjectContainerCountUnchanged', 'defaultOllamaEndpointAccessed', 'restored')
    Assert-ExactProperties -Object $cleanup -Expected $cleanupNames -Path 'receipt.cleanup'
    foreach ($name in @('fakeProjectContainerCount', 'fakeProjectNetworkCount', 'fakeProjectVolumeCount', 'liquidProjectContainerCount', 'liquidProjectNetworkCount', 'liquidProjectVolumeCount', 'ownedOllamaProcessCount', 'ownedOllamaListenerCount', 'tempArtifactCount')) {
        Assert-Equal -Actual (Get-JsonProperty -Object $cleanup -Name $name -Path 'receipt.cleanup') -Expected 0 -Path "receipt.cleanup.$name"
    }
    foreach ($name in @('personalProjectContainerCountBefore', 'personalProjectContainerCountAfter')) {
        Assert-IntegerRange -Actual (Get-JsonProperty -Object $cleanup -Name $name -Path 'receipt.cleanup') -Minimum 0 -Maximum 100 -Path "receipt.cleanup.$name"
    }
    $before = Get-JsonProperty -Object $cleanup -Name 'personalProjectContainerCountBefore' -Path 'receipt.cleanup'
    $after = Get-JsonProperty -Object $cleanup -Name 'personalProjectContainerCountAfter' -Path 'receipt.cleanup'
    if ($before -ne $after) {
        Stop-ContractValidation 'receipt.cleanup personal project container count changed.'
    }
    Assert-Boolean -Actual (Get-JsonProperty -Object $cleanup -Name 'personalProjectContainerCountUnchanged' -Path 'receipt.cleanup') -Expected $true -Path 'receipt.cleanup.personalProjectContainerCountUnchanged'
    Assert-Boolean -Actual (Get-JsonProperty -Object $cleanup -Name 'defaultOllamaEndpointAccessed' -Path 'receipt.cleanup') -Expected $false -Path 'receipt.cleanup.defaultOllamaEndpointAccessed'
    Assert-Boolean -Actual (Get-JsonProperty -Object $cleanup -Name 'restored' -Path 'receipt.cleanup') -Expected $true -Path 'receipt.cleanup.restored'

    foreach ($case in @((Get-JsonProperty -Object $Fixture -Name 'cases' -Path 'fixture'))) {
        $rawContent = [string](Get-JsonProperty -Object $case -Name 'content' -Path 'fixture.cases[]')
        if ($RawReceipt.Contains($rawContent)) {
            Stop-ContractValidation 'receipt contains a raw synthetic memo body; receipts must remain aggregate-only.'
        }
    }
    Assert-NoSensitiveReceiptMaterial -Node $Receipt -Path 'receipt'
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$fixtureSchemaPath = Join-Path $repositoryRoot 'contracts/ai-preferred-product-smoke-fixture.schema.json'
$receiptSchemaPath = Join-Path $repositoryRoot 'contracts/ai-preferred-product-smoke-receipt.schema.json'
$fixturePath = Join-Path $repositoryRoot 'fixtures/ai-preferred-product-smoke-cases.json'
$composePath = Join-Path $repositoryRoot 'compose.ai-product-smoke.yaml'
$analysisDirectory = Join-Path $repositoryRoot 'scripts/analysis'

$fixtureSchemaJson = Read-StrictJsonDocument -Path $fixtureSchemaPath -Label 'fixture schema'
$receiptSchemaJson = Read-StrictJsonDocument -Path $receiptSchemaPath -Label 'receipt schema'
$fixtureJson = Read-StrictJsonDocument -Path $fixturePath -Label 'fixture'

Assert-FixtureSchemaContract -Schema $fixtureSchemaJson.Document
Assert-ReceiptSchemaContract -Schema $receiptSchemaJson.Document
Assert-FixtureContract -Fixture $fixtureJson.Document
Assert-ComposeSourceSafety -ComposePath $composePath
Assert-PowerShellSources -AnalysisDirectory $analysisDirectory

if ($PSBoundParameters.ContainsKey('ReceiptPath')) {
    $resolvedReceiptPath = (Resolve-Path -LiteralPath $ReceiptPath -ErrorAction Stop).Path
    $receiptJson = Read-StrictJsonDocument -Path $resolvedReceiptPath -Label 'receipt'
    Assert-ReceiptContract -Receipt $receiptJson.Document -RawReceipt $receiptJson.Raw -Fixture $fixtureJson.Document
}

Write-Output 'AI product smoke source contracts: PASS'

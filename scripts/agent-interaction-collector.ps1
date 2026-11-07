<#
.SYNOPSIS
    执行 Agent 真实多轮交互评测场景，并生成 agentInteractionEvaluation 可消费的 JSONL。

.DESCRIPTION
    场景覆盖普通多轮对话、缺少日程信息后的同一 Run 续跑，以及动作确认后的同一 Run 恢复。
    脚本只创建自己使用的 Agent 线程；传入 -CreateInterview 时也只删除自己创建的临时面试。
    真实 Provider 调用会产生费用，建议只在候选环境执行。
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18083',
    [string]$Email = '',
    [string]$Password = 'StrongPass123',
    [string]$InterviewSessionId = '',
    [string]$ScenarioPath = 'app/src/test/resources/agent-eval/interaction-matrix-v1.jsonl',
    [string]$OutputPath = 'build/agent-eval/interaction-run.jsonl',
    [int]$MaxScenarios = 0,
    [string]$ProviderId = 'pixel',
    [switch]$CreateInterview,
    [switch]$RunQualityGate
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$jar = Join-Path $env:TEMP "agent-interaction-$([guid]::NewGuid()).cookie"
$createdInterview = $false
$createdThreads = [System.Collections.Generic.List[string]]::new()

function Assert-Pass([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Parse-Json([string]$Raw, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Raw)) { throw "$Description 返回空响应" }
    try { return $Raw | ConvertFrom-Json -Depth 50 }
    catch { throw "$Description 返回非 JSON：$Raw" }
}

function Invoke-JsonApi(
    [string]$Path,
    [string]$Method = 'GET',
    [object]$Body = $null
) {
    $args = @('-sS', '--max-time', '60', '-b', $jar, '-X', $Method, "$BaseUrl$Path")
    if ($Method -ne 'GET') {
        $args += @('-H', "X-XSRF-TOKEN: $script:xsrf")
        $args += @('-H', 'Content-Type: application/json')
    }
    if ($null -ne $Body) {
        $args += @('--data-raw', ($Body | ConvertTo-Json -Depth 50 -Compress))
    }
    $lastResponse = ''
    for ($attempt = 1; $attempt -le 8; $attempt++) {
        $raw = & curl.exe @args 2>$null
        $lastResponse = ($raw -join [Environment]::NewLine)
        $transient = $LASTEXITCODE -ne 0 -or $lastResponse -match '<h1>50[234] Bad Gateway|<h1>503 Service Unavailable'
        if (-not $transient) {
            return Parse-Json $lastResponse "$Method $Path"
        }
        if ($attempt -lt 8) { Start-Sleep -Seconds 3 }
    }
    throw "$Method $Path 连续请求失败：$lastResponse"
}

function Parse-SseEvents([string]$Raw) {
    $events = [System.Collections.Generic.List[object]]::new()
    foreach ($block in ($Raw -split "\r?\n\r?\n")) {
        if ([string]::IsNullOrWhiteSpace($block)) { continue }
        $eventName = (($block -split "\r?\n") |
            Where-Object { $_ -like 'event:*' } | Select-Object -First 1)
        $eventName = if ($eventName) { $eventName.Substring(6).Trim() } else { '' }
        $dataLines = @($block -split "\r?\n" |
            Where-Object { $_ -like 'data:*' } |
            ForEach-Object { $_.Substring(5).TrimStart() })
        if ($dataLines.Count -eq 0) { continue }
        try {
            $data = ($dataLines -join "`n") | ConvertFrom-Json -Depth 50
            $events.Add([pscustomobject]@{ name = $eventName; data = $data })
        } catch {
            Write-Warning "跳过无法解析的 SSE 事件：$($_.Exception.Message)"
        }
    }
    return $events
}

function Invoke-AgentStream([string]$SessionId, [object]$Body) {
    $output = Join-Path $env:TEMP "agent-interaction-stream-$([guid]::NewGuid()).txt"
    try {
        $args = @(
            '-sS', '--max-time', '300', '-b', $jar, '-X', 'POST',
            "$BaseUrl/api/agent/sessions/$SessionId/messages/stream",
            '-H', "X-XSRF-TOKEN:$script:xsrf", '-H', 'Content-Type: application/json',
            '--data-raw', ($Body | ConvertTo-Json -Depth 50 -Compress), '-o', $output
        )
        & curl.exe @args 2>$null | Out-Null
        $raw = if (Test-Path -LiteralPath $output) { Get-Content -LiteralPath $output -Raw } else { '' }
        if ([string]::IsNullOrWhiteSpace($raw)) { throw 'Agent SSE 返回为空' }
        return $raw
    } finally {
        Remove-Item -LiteralPath $output -Force -ErrorAction SilentlyContinue
    }
}

function Wait-Conversation(
    [string]$SessionId,
    [string]$AgentSessionId,
    [bool]$AcceptWaitingUser = $false
) {
    $query = "?agentSessionId=$([Uri]::EscapeDataString($AgentSessionId))"
    $last = $null
    for ($attempt = 1; $attempt -le 90; $attempt++) {
        $last = Invoke-JsonApi "/api/agent/interview-sessions/$SessionId/conversation$query"
        $status = [string]$last.data.runStatus
        $terminal = $status -eq 'COMPLETED' -or $status -eq 'FAILED' -or $status -eq 'CANCELLED'
        $terminal = $terminal -or ($AcceptWaitingUser -and $status -eq 'WAITING_USER')
        if ($terminal) {
            return $last
        }
        Start-Sleep -Seconds 3
    }
    throw "Agent 运行超过等待窗口：$($last.data.runStatus)"
}

function Get-PendingType($Conversation) {
    if ($null -eq $Conversation.data.pendingInteraction) { return '' }
    return [string]$Conversation.data.pendingInteraction.type
}

function Get-ConversationAnswer($Conversation) {
    $assistant = @($Conversation.data.messages |
        Where-Object { [string]$_.role -eq 'assistant' } | Select-Object -Last 1)
    if ($assistant.Count -eq 0) { return '' }
    return [string]$assistant[0].content
}

function Get-CodeVersion {
    try {
        $version = (& git rev-parse --short HEAD 2>$null).Trim()
        if ($version) { return $version }
        return 'unknown'
    } catch { return 'unknown' }
}

function New-Interview {
    $requestId = "agent-interaction-$([guid]::NewGuid().ToString('N'))"
    $created = Invoke-JsonApi '/api/interview/sessions' 'POST' @{
        resumeText = ''
        questionCount = 3
        forceCreate = $true
        skillId = 'java-backend'
        difficulty = 'mid'
        requestId = $requestId
    }
    Assert-Pass ($created.code -eq 200 -and $null -ne $created.data.sessionId) '创建交互评测临时面试成功'
    $script:createdInterview = $true
    return [string]$created.data.sessionId
}

function Assert-Step(
    [object]$ScenarioStep,
    [string]$ActualStatus,
    [string]$ActualPendingType,
    [string]$ActualActionStatus,
    [int]$Index
) {
    $expectedStatus = [string]$ScenarioStep.expectedStatus
    $expectedPending = [string]$ScenarioStep.expectedPendingType
    Assert-Pass ($ActualStatus -eq $expectedStatus) "第 $Index 步状态为 $ActualStatus（预期 $expectedStatus）"
    Assert-Pass ($ActualPendingType -eq $expectedPending) "第 $Index 步 pending 为 '$ActualPendingType'（预期 '$expectedPending'）"
    if ($ScenarioStep.type -ne 'message') {
        $expectedAction = [string]$ScenarioStep.expectedActionStatus
        Assert-Pass ($ActualActionStatus -eq $expectedAction) "第 $Index 步动作状态为 $ActualActionStatus（预期 $expectedAction）"
    }
}

function Invoke-ScenarioMessage(
    [object]$Scenario,
    [object]$Step,
    [string]$InterviewId,
    [string]$AgentSessionId,
    [string]$MemoryKey,
    [string]$Provider
) {
    $payload = @{
        goal = [string]$Scenario.goal
        message = [string]$Step.message
        history = @()
        providerId = $Provider
        memoryKey = $MemoryKey
        agentSessionId = $AgentSessionId
    }
    $stream = Invoke-AgentStream $AgentSessionId $payload
    $events = @(Parse-SseEvents $stream)
    $errors = @($events | Where-Object { $_.data.type -eq 'error' })
    if ($errors.Count -gt 0) { throw "SSE 返回错误：$([string]$errors[0].data.message)" }
    $acceptWaiting = [string]$Step.expectedStatus -eq 'WAITING_USER'
    $conversation = Wait-Conversation $InterviewId $AgentSessionId $acceptWaiting
    $usage = $conversation.data.runUsage
    $stepResult = [ordered]@{
        type = 'message'
        status = [string]$conversation.data.runStatus
        pendingType = Get-PendingType $conversation
        actionStatus = ''
        modelCalls = if ($usage) { [int]$usage.modelCalls } else { 0 }
        durationMillis = if ($usage) { [long]$usage.durationMillis } else { 0 }
        answer = Get-ConversationAnswer $conversation
    }
    Assert-Step $Step $stepResult.status $stepResult.pendingType '' $script:stepIndex
    return [pscustomobject]@{ result = $stepResult; conversation = $conversation; usage = $usage }
}

function Invoke-ScenarioConfirm(
    [object]$Scenario,
    [object]$Step,
    [string]$InterviewId,
    [string]$AgentSessionId
) {
    $actions = Invoke-JsonApi "/api/agent/sessions/$AgentSessionId/actions"
    $action = @($actions.data | Where-Object { [string]$_.status -eq 'PROPOSED' } | Select-Object -First 1)
    if ($action.Count -eq 0) { throw '没有找到可确认的 Agent 动作草稿' }
    $confirmed = Invoke-JsonApi "/api/agent/actions/$([Uri]::EscapeDataString([string]$action[0].actionId))/confirm" 'POST'
    $actionStatus = [string]$confirmed.data.status
    $conversation = Wait-Conversation $InterviewId $AgentSessionId $false
    $usage = $conversation.data.runUsage
    $stepResult = [ordered]@{
        type = [string]$Step.type
        status = [string]$conversation.data.runStatus
        pendingType = Get-PendingType $conversation
        actionStatus = $actionStatus
        modelCalls = if ($usage) { [int]$usage.modelCalls } else { 0 }
        durationMillis = if ($usage) { [long]$usage.durationMillis } else { 0 }
        answer = Get-ConversationAnswer $conversation
    }
    Assert-Step $Step $stepResult.status $stepResult.pendingType $stepResult.actionStatus $script:stepIndex
    return [pscustomobject]@{ result = $stepResult; conversation = $conversation; usage = $usage }
}

try {
    if ([string]::IsNullOrWhiteSpace($Email)) {
        $suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
        $Email = "agent-interaction-$suffix@example.com"
        $register = & curl.exe -sS -X POST "$BaseUrl/api/auth/register" `
            -H 'Content-Type: application/json' `
            --data-raw (@{ email = $Email; password = $Password; nickname = 'Agent Interaction Acceptance' } | ConvertTo-Json -Compress)
        $registerJson = Parse-Json ($register -join [Environment]::NewLine) '注册交互评测账号'
        Assert-Pass ($registerJson.code -eq 200) '注册交互评测账号成功'
    }

    $login = & curl.exe -sS -c $jar -H 'Content-Type: application/json' `
        -d (@{ email = $Email; password = $Password } | ConvertTo-Json -Compress) `
        "$BaseUrl/api/auth/login"
    $loginJson = Parse-Json ($login -join [Environment]::NewLine) '登录交互评测账号'
    Assert-Pass ($loginJson.code -eq 200) '登录交互评测账号成功'
    $xsrfLine = Get-Content -LiteralPath $jar |
        Where-Object { $_ -match '\sXSRF-TOKEN\s' } | Select-Object -Last 1
    if ($null -eq $xsrfLine) { throw '登录后没有获取到 XSRF-TOKEN' }
    $xsrfParts = $xsrfLine -split '\s+'
    $script:xsrf = $xsrfParts[$xsrfParts.Length - 1]

    if ([string]::IsNullOrWhiteSpace($InterviewSessionId)) {
        if (-not $CreateInterview) { throw '没有面试会话；请传入 -InterviewSessionId 或 -CreateInterview' }
        $InterviewSessionId = New-Interview
    }
    if (-not (Test-Path -LiteralPath $ScenarioPath)) { throw "找不到交互语料：$ScenarioPath" }
    $scenarios = @(Get-Content -LiteralPath $ScenarioPath -Encoding utf8 |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_ | ConvertFrom-Json -Depth 50 })
    if ($MaxScenarios -gt 0) { $scenarios = @($scenarios | Select-Object -First $MaxScenarios) }
    if ($scenarios.Count -eq 0) { throw '交互语料为空' }

    $parent = Split-Path -Parent $OutputPath
    if ($parent) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    if (Test-Path -LiteralPath $OutputPath) { Remove-Item -LiteralPath $OutputPath -Force }
    $codeVersion = Get-CodeVersion
    $selectedProviderId = $ProviderId.Trim()
    $scenarioIndex = 0
    foreach ($scenario in $scenarios) {
        $scenarioIndex++
        $scenarioId = [string]$scenario.id
        $thread = Invoke-JsonApi "/api/agent/interview-sessions/$InterviewSessionId/conversations" 'POST' @{
            title = "交互评测 $scenarioId"
        }
        Assert-Pass ($thread.code -eq 200 -and $null -ne $thread.data.agentSessionId) "创建交互线程成功（$scenarioId）"
        $agentSessionId = [string]$thread.data.agentSessionId
        $createdThreads.Add($agentSessionId)
        $memoryKey = "aiq-$([guid]::NewGuid().ToString('N').Substring(0, 16))"
        $stepResults = [System.Collections.Generic.List[object]]::new()
        $lastConversation = $null
        $lastUsage = $null
        $script:stepIndex = 0
        foreach ($step in @($scenario.steps)) {
            $script:stepIndex++
            Write-Host "[$scenarioIndex/$($scenarios.Count)] $scenarioId 第 $script:stepIndex 步：$($step.type)" -ForegroundColor Cyan
            $execution = if ([string]$step.type -eq 'message') {
                Invoke-ScenarioMessage $scenario $step $InterviewSessionId $agentSessionId $memoryKey $selectedProviderId
            } elseif ([string]$step.type -eq 'confirm_latest_action') {
                Invoke-ScenarioConfirm $scenario $step $InterviewSessionId $agentSessionId
            } else {
                throw "暂不支持的交互步骤：$($step.type)"
            }
            $stepResults.Add($execution.result)
            $lastConversation = $execution.conversation
            $lastUsage = $execution.usage
        }
        $record = [ordered]@{
            scenarioId = $scenarioId
            provider = if ($lastUsage.provider) { [string]$lastUsage.provider } else { 'unknown' }
            model = if ($lastUsage.model) { [string]$lastUsage.model } else { 'unknown' }
            codeVersion = $codeVersion
            finalStatus = [string]$lastConversation.data.runStatus
            finalPendingType = Get-PendingType $lastConversation
            steps = @($stepResults)
        }
        $record | ConvertTo-Json -Depth 50 -Compress | Add-Content -LiteralPath $OutputPath -Encoding utf8
        Invoke-JsonApi "/api/agent/interview-sessions/$InterviewSessionId/conversations/$agentSessionId" 'DELETE' | Out-Null
    }

    Write-Host "[PASS] 已生成真实多轮交互 JSONL：$OutputPath" -ForegroundColor Green
    if ($RunQualityGate) {
        & .\gradlew.bat :app:agentInteractionEvaluation `
            "-PagentInteractionEvalInput=$OutputPath" `
            "-PagentInteractionEvalReportDir=build/reports/agent-eval" `
            "-PagentInteractionEvalRunId=interaction-$codeVersion" `
            --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'Agent 多轮交互评测门禁失败' }
    }
} finally {
    foreach ($agentSessionId in $createdThreads) {
        try {
            Invoke-JsonApi "/api/agent/interview-sessions/$InterviewSessionId/conversations/$agentSessionId" 'DELETE' | Out-Null
        } catch { }
    }
    if ($createdInterview -and -not [string]::IsNullOrWhiteSpace($InterviewSessionId)) {
        try { Invoke-JsonApi "/api/interview/sessions/$InterviewSessionId" 'DELETE' | Out-Null } catch { }
    }
    Remove-Item -LiteralPath $jar -Force -ErrorAction SilentlyContinue
}

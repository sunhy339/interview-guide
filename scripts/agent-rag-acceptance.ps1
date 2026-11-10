<#!
.SYNOPSIS
    Candidate environment real-model RAG acceptance.

.DESCRIPTION
    Logs in a temporary account, selects a completed shared knowledge base,
    binds it to a new general Agent conversation, and performs one real SSE
    model request. The test requires a real provider and verifies that the
    persisted run contains a RAG source and an assistant answer.
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18083',
    [long]$KnowledgeBaseId = 3,
    [string]$Email = '',
    [string]$Password = 'StrongPass123'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$xsrfToken = ''
$cookieHeader = ''

function Parse-Json([string]$Raw, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Raw)) { throw "$Description 返回空响应" }
    try { return $Raw | ConvertFrom-Json -Depth 60 }
    catch { throw "$Description 返回非 JSON：$Raw" }
}

function Assert-Pass([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Invoke-JsonApi(
    [string]$Path,
    [string]$Method = 'GET',
    [object]$Body = $null
) {
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($cookieHeader)) { $headers['Cookie'] = $cookieHeader }
    if ($Method -ne 'GET' -and -not [string]::IsNullOrWhiteSpace($xsrfToken)) {
        $headers['X-XSRF-TOKEN'] = $xsrfToken
    }
    $params = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = $headers
        UseBasicParsing = $true
        SkipHttpErrorCheck = $true
        TimeoutSec = if ($Path -like '*messages/stream') { 300 } else { 60 }
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json; charset=utf-8'
        $params.Body = $Body | ConvertTo-Json -Depth 60 -Compress
    }
    $response = Invoke-WebRequest @params
    $json = $null
    if (-not [string]::IsNullOrWhiteSpace([string]$response.Content)) {
        try { $json = ([string]$response.Content | ConvertFrom-Json -Depth 60) } catch { }
    }
    return [pscustomobject]@{ Response = $response; Json = $json }
}

function Get-RunEvents([string]$AgentSessionId, [string]$RunId) {
    if ([string]::IsNullOrWhiteSpace($RunId)) { return @() }
    $events = Invoke-JsonApi "/api/agent/sessions/$AgentSessionId/run-events?runId=$([Uri]::EscapeDataString($RunId))&limit=200"
    Assert-Pass ($events.Response.StatusCode -eq 200 -and $events.Json.code -eq 200) '读取 RAG Agent Run 事件'
    return @($events.Json.data)
}

function Get-Trace([string]$AgentSessionId, [string]$RunId) {
    if ([string]::IsNullOrWhiteSpace($RunId)) { return @() }
    $trace = Invoke-JsonApi "/api/agent/sessions/$AgentSessionId/trace?runId=$([Uri]::EscapeDataString($RunId))&limit=100"
    Assert-Pass ($trace.Response.StatusCode -eq 200 -and $trace.Json.code -eq 200) '读取 RAG Agent Trace'
    return @($trace.Json.data)
}

try {
    if ([string]::IsNullOrWhiteSpace($Email)) {
        $Email = "agent-rag-$([guid]::NewGuid().ToString('N').Substring(0, 12))@example.com"
        $register = Invoke-WebRequest -Uri "$BaseUrl/api/auth/register" -Method POST `
            -WebSession $session -UseBasicParsing -SkipHttpErrorCheck `
            -ContentType 'application/json; charset=utf-8' `
            -Body (@{ email = $Email; password = $Password; nickname = 'Agent RAG Acceptance' } | ConvertTo-Json -Compress)
        $registerJson = Parse-Json ([string]$register.Content) '注册 RAG 验收账号'
        Assert-Pass ($registerJson.code -eq 200) '注册 RAG 验收账号'
    }

    $login = Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method POST `
        -WebSession $session -UseBasicParsing -SkipHttpErrorCheck `
        -ContentType 'application/json; charset=utf-8' `
        -Body (@{ email = $Email; password = $Password } | ConvertTo-Json -Compress)
    $loginJson = Parse-Json ([string]$login.Content) "登录 $Email"
    Assert-Pass ($loginJson.code -eq 200) "登录 $Email"
    $xsrfCookie = $session.Cookies.GetCookies($BaseUrl) | Where-Object Name -eq 'XSRF-TOKEN' | Select-Object -Last 1
    Assert-Pass ($null -ne $xsrfCookie) '登录后取得 XSRF-TOKEN'
    $xsrfToken = [Uri]::UnescapeDataString([string]$xsrfCookie.Value)
    $cookieHeader = ($session.Cookies.GetCookies($BaseUrl) | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join '; '

    $knowledgeBases = Invoke-JsonApi '/api/agent/knowledge-bases'
    Assert-Pass ($knowledgeBases.Response.StatusCode -eq 200 -and $knowledgeBases.Json.code -eq 200) '读取候选环境知识库列表'
    $knowledgeBase = @($knowledgeBases.Json.data | Where-Object { [long]$_.id -eq $KnowledgeBaseId } | Select-Object -First 1)
    Assert-Pass ($knowledgeBase.Count -eq 1 -and $knowledgeBase[0].vectorStatus -eq 'COMPLETED') "知识库 $KnowledgeBaseId 已完成向量化"

    $created = Invoke-JsonApi '/api/agent/sessions' 'POST' @{ title = '真实 RAG 验收会话' }
    Assert-Pass ($created.Response.StatusCode -eq 200 -and $created.Json.code -eq 200) '创建通用 Agent RAG 会话'
    $agentSessionId = [string]$created.Json.data.agentSessionId
    Assert-Pass (-not [string]::IsNullOrWhiteSpace($agentSessionId)) '通用 Agent 会话返回 ID'

    $attached = Invoke-JsonApi "/api/agent/sessions/$agentSessionId/contexts" 'POST' @{
        sourceType = 'KNOWLEDGE_BASE'
        resourceId = [string]$KnowledgeBaseId
        scope = 'SESSION'
    }
    Assert-Pass ($attached.Response.StatusCode -eq 200 -and $attached.Json.code -eq 200) '绑定知识库到 Agent 会话'

    $context = Invoke-JsonApi "/api/agent/sessions/$agentSessionId/context"
    Assert-Pass (@($context.Json.data | Where-Object { $_.contextType -eq 'KNOWLEDGE_BASE' -and [string]$_.resourceId -eq [string]$KnowledgeBaseId }).Count -eq 1) '会话上下文持久化知识库绑定'

    $payload = @{
        goal = 'review'
        message = '请结合当前绑定的知识库，说明 Agent Loop 中规划、工具调用和最终回答之间的关系，并引用实际检索到的知识库来源。不要编造没有检索到的内容。'
        history = @()
        providerId = ''
        memoryKey = $Email
        agentSessionId = $agentSessionId
    }
    $stream = Invoke-JsonApi "/api/agent/sessions/$agentSessionId/messages/stream" 'POST' $payload
    $body = [string]$stream.Response.Content
    Assert-Pass ($stream.Response.StatusCode -eq 200) "真实 Agent RAG SSE 请求成功（HTTP $($stream.Response.StatusCode)）"
    Assert-Pass ($body -match 'event:\s*agent-sources') 'SSE 返回来源事件'
    Assert-Pass ($body -match '"kind"\s*:\s*"rag"') 'SSE 返回真实 RAG 来源'
    Assert-Pass ($body -match 'event:\s*agent-delta') 'SSE 返回模型回答增量'
    Assert-Pass ($body -notmatch 'event:\s*agent-error') 'SSE 未返回 Agent 错误'

    $conversation = $null
    for ($attempt = 1; $attempt -le 12; $attempt++) {
        Start-Sleep -Seconds 2
        $conversation = Invoke-JsonApi "/api/agent/sessions/$agentSessionId"
        if ([string]$conversation.Json.data.runStatus -in @('COMPLETED', 'FAILED', 'CANCELLED')) { break }
    }
    Assert-Pass ($conversation.Response.StatusCode -eq 200 -and $conversation.Json.code -eq 200) '读取 RAG Agent 会话终态'
    Assert-Pass ([string]$conversation.Json.data.runStatus -eq 'COMPLETED') "RAG Agent 运行完成（状态=$($conversation.Json.data.runStatus)）"
    Assert-Pass (@($conversation.Json.data.messages | Where-Object { $_.role -eq 'assistant' -and -not [string]::IsNullOrWhiteSpace([string]$_.content) }).Count -ge 1) 'RAG Agent 持久化了助手回答'
    $ragSources = @($conversation.Json.data.runSources | Where-Object { $_.kind -eq 'rag' })
    Assert-Pass ($ragSources.Count -ge 1) 'RAG 来源已持久化到运行记录'
    Assert-Pass (@($ragSources | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_.sourceId) -and
        [string]$_.version -eq 'rag-tool-v1'
    }).Count -ge 1) 'RAG 来源包含工具版本和稳定 sourceId'

    $runId = [string]$conversation.Json.data.runId
    Assert-Pass (-not [string]::IsNullOrWhiteSpace($runId)) 'RAG Agent 运行返回稳定 runId'
    $runEvents = @(Get-RunEvents $agentSessionId $runId)
    $eventTypes = @($runEvents | ForEach-Object { [string]$_.eventType } | Where-Object { $_ } | Select-Object -Unique)
    foreach ($requiredEvent in @('MODEL_TURN_STARTED', 'MODEL_TURN_COMPLETED', 'TOOL_STARTED', 'TOOL_COMPLETED')) {
        Assert-Pass ($eventTypes -contains $requiredEvent) "RAG Run 事件包含 $requiredEvent"
    }
    Assert-Pass (@($runEvents | Where-Object { $_.toolName -eq 'knowledge_base_search' -and $_.eventType -eq 'TOOL_STARTED' }).Count -ge 1) 'Run 事件确认实际启动 knowledge_base_search'
    Assert-Pass (@($runEvents | Where-Object { $_.toolName -eq 'knowledge_base_search' -and $_.eventType -eq 'TOOL_COMPLETED' -and $_.status -eq 'completed' }).Count -ge 1) 'Run 事件确认 knowledge_base_search 成功完成'

    $trace = @(Get-Trace $agentSessionId $runId)
    $traceActions = @($trace | ForEach-Object { [string]$_.action } | Where-Object { $_ } | Select-Object -Unique)
    foreach ($requiredTraceAction in @('GROUNDING_CHECK', 'SOURCE_INTEGRITY_CHECK', 'EVIDENCE_ASSESSMENT')) {
        Assert-Pass ($traceActions -contains $requiredTraceAction) "RAG Trace 包含 $requiredTraceAction"
    }
    Write-Host "Agent RAG acceptance: PASS (knowledgeBase=$KnowledgeBaseId, agentSession=$agentSessionId)" -ForegroundColor Cyan
} finally {
    # The temporary account/session are intentionally retained for auditability
    # in the candidate database; no production data is touched.
}

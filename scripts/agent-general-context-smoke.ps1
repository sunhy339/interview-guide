<#!
.SYNOPSIS
    通用 Agent 与面试报告按需绑定的真实模型烟测。

.DESCRIPTION
    创建临时账号、通用 Agent 会话和临时面试，连续执行：
    1. 不绑定报告的普通问题；
    2. 绑定报告后明确引用报告的问题；
    3. 清除报告后再次发送普通问题；
    4. 明确的日程动作请求，确认只生成待确认动作而不直接落库。

    该脚本只操作候选环境，临时面试会在结束时删除；临时账号不会影响已有账号。
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:18084',
    [string]$Email = '',
    [string]$Password = 'StrongPass123',
    [switch]$ScheduleOnly
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$script:baseUri = [Uri]$BaseUrl
$script:cookieContainer = New-Object System.Net.CookieContainer
$script:httpHandler = New-Object System.Net.Http.HttpClientHandler
$script:httpHandler.CookieContainer = $script:cookieContainer
$script:httpHandler.UseCookies = $true
$script:httpClient = New-Object System.Net.Http.HttpClient($script:httpHandler)
$script:httpClient.BaseAddress = $script:baseUri
$script:xsrf = ''
$createdInterview = ''

function Parse-Json([string]$Raw, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Raw)) { throw "$Description 返回空响应" }
    try { return $Raw | ConvertFrom-Json -Depth 60 }
    catch { throw "$Description 返回非 JSON：$Raw" }
}

function Assert-Pass([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Sync-Xsrf {
    $cookie = $script:cookieContainer.GetCookies($script:baseUri) |
        Where-Object Name -eq 'XSRF-TOKEN' | Select-Object -Last 1
    if ($null -ne $cookie) {
        $script:xsrf = [Uri]::UnescapeDataString([string]$cookie.Value)
    }
}

function Refresh-Xsrf([bool]$RequireAuth = $false) {
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, '/api/auth/me')
    try {
        $response = $script:httpClient.SendAsync($request).GetAwaiter().GetResult()
        $auth = Parse-Json ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult()) 'GET /api/auth/me'
        if ($RequireAuth -and $auth.data.authenticated -ne $true) {
            throw "GET /api/auth/me 显示登录已失效：$($auth.message)"
        }
        Sync-Xsrf
        if ([string]::IsNullOrWhiteSpace($script:xsrf)) {
            throw 'GET /api/auth/me 未返回 XSRF-TOKEN'
        }
    } finally {
        if ($null -ne $request) { $request.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
    }
}

function Invoke-JsonApi([string]$Path, [string]$Method = 'GET', [object]$Body = $null) {
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::$Method, $Path)
    $response = $null
    if ($Method -ne 'GET') {
        Refresh-Xsrf ($Path -notin @('/api/auth/register', '/api/auth/login'))
        if ($script:xsrf) { $request.Headers.Add('X-XSRF-TOKEN', $script:xsrf) }
    }
    try {
        if ($null -ne $Body) {
            $jsonBody = $Body | ConvertTo-Json -Depth 60 -Compress
            $request.Content = New-Object System.Net.Http.StringContent($jsonBody, [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $script:httpClient.SendAsync($request).GetAwaiter().GetResult()
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        Sync-Xsrf
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Json = Parse-Json $raw "$Method $Path"
        }
    } finally {
        if ($null -ne $request) { $request.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
    }
}

function Invoke-Stream([string]$Path, [object]$Body) {
    Refresh-Xsrf
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, $Path)
    $request.Headers.Add('Accept', 'text/event-stream')
    $request.Headers.Add('X-XSRF-TOKEN', $script:xsrf)
    $request.Content = New-Object System.Net.Http.StringContent(($Body | ConvertTo-Json -Depth 60 -Compress), [Text.Encoding]::UTF8, 'application/json')
    $response = $null
    try {
        $response = $script:httpClient.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
        ).GetAwaiter().GetResult()
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    } finally {
        if ($null -ne $request) { $request.Dispose() }
    }
    $setCookies = @()
    if ($null -ne $response -and $response.Headers.Contains('Set-Cookie')) {
        $setCookies = @($response.Headers.GetValues('Set-Cookie') | ForEach-Object {
            [regex]::Replace([string]$_, '=([^;]*)', '=REDACTED')
        })
    }
    Write-Host "[INFO] SSE Set-Cookie: $($setCookies -join ' | ')" -ForegroundColor DarkGray
    return [pscustomobject]@{
        StatusCode = [int]$response.StatusCode
        Body = [string]$raw
    }
}

function New-StreamPayload([string]$Message, [string]$AgentSessionId) {
    return @{
        goal = 'general'
        message = $Message
        history = @()
        providerId = ''
        memoryKey = "agent-general-smoke-$Email"
        agentSessionId = $AgentSessionId
    }
}

function Assert-CompletedStream($Result, [string]$Label) {
    Assert-Pass ($Result.StatusCode -eq 200) "$Label HTTP $($Result.StatusCode)：$($Result.Body.Substring(0, [Math]::Min(300, $Result.Body.Length)))"
    Assert-Pass ($Result.Body -match 'event:\s*agent-done' -and $Result.Body -notmatch 'event:\s*agent-error') "$Label 正常完成"
    Assert-Pass ($Result.Body -match 'event:\s*agent-delta') "$Label 有流式输出"
}

try {
    if ([string]::IsNullOrWhiteSpace($Email)) {
        $suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
        $Email = "agent-general-smoke-$suffix@example.com"
        $registered = Invoke-JsonApi '/api/auth/register' 'POST' @{
            email = $Email
            password = $Password
            nickname = 'General Agent Smoke'
        }
        Assert-Pass ($registered.Json.code -eq 200) '注册临时验收账号成功'
    }

    $login = Invoke-JsonApi '/api/auth/login' 'POST' @{ email = $Email; password = $Password }
    Assert-Pass ($login.Json.code -eq 200) '登录候选环境成功'

    $interview = Invoke-JsonApi '/api/interview/sessions' 'POST' @{
        resumeText = ''
        questionCount = 3
        forceCreate = $true
        skillId = 'java-backend'
        difficulty = 'mid'
        requestId = "agent-general-smoke-$([guid]::NewGuid().ToString('N'))"
    }
    Assert-Pass ($interview.Json.code -eq 200 -and $interview.Json.data.sessionId) '创建临时面试资料成功'
    $createdInterview = [string]$interview.Json.data.sessionId

    $thread = Invoke-JsonApi '/api/agent/sessions' 'POST' @{ title = '通用 Agent 上下文烟测' }
    Assert-Pass ($thread.Json.code -eq 200 -and $thread.Json.data.agentSessionId) '创建通用 Agent 会话成功'
    $agentSessionId = [string]$thread.Json.data.agentSessionId

    if (-not $ScheduleOnly) {
        $ordinary1 = Invoke-Stream "/api/agent/sessions/$agentSessionId/messages/stream" `
            (New-StreamPayload '什么是 Java 的 HashMap？请用三点简洁说明。' $agentSessionId)
        Assert-CompletedStream $ordinary1 '未绑定报告的普通问题'
        Assert-Pass ($ordinary1.Body -notmatch '本场面试复盘|面试报告未绑定|无法完成本场面试复盘') '普通问题未误触发面试复盘'

        $attached = Invoke-JsonApi "/api/agent/sessions/$agentSessionId/contexts" 'POST' @{
            sourceType = 'INTERVIEW_REPORT'
            resourceId = $createdInterview
            scope = 'SESSION'
        }
        Assert-Pass ($attached.Json.code -eq 200) "绑定面试报告成功：$($attached.StatusCode) $($attached.Json.message)"

        $reportQuestion = Invoke-Stream "/api/agent/sessions/$agentSessionId/messages/stream" `
            (New-StreamPayload '请根据刚刚绑定的面试报告，概括本次面试涉及的主题；如果报告没有回答内容，不要编造我的表现。' $agentSessionId)
        Assert-CompletedStream $reportQuestion '明确引用面试报告的问题'

        $bindings = Invoke-JsonApi "/api/agent/sessions/$agentSessionId/context"
        Assert-Pass (@($bindings.Json.data).Count -eq 1) '会话中存在一份面试报告上下文'

        $cleared = Invoke-JsonApi "/api/agent/sessions/$agentSessionId/context" 'DELETE'
        Assert-Pass ($cleared.Json.code -eq 200) '清除面试报告上下文成功'

        $ordinary2 = Invoke-Stream "/api/agent/sessions/$agentSessionId/messages/stream" `
            (New-StreamPayload '现在不要使用任何面试报告，解释一下 Tool Calling 和 RAG 的区别。' $agentSessionId)
        Assert-CompletedStream $ordinary2 '清除报告后的普通问题'
        Assert-Pass ($ordinary2.Body -notmatch '本场面试复盘|当前无法完成本场面试复盘') '清除报告后未继续沿用复盘路径'
    }

    $tomorrow = (Get-Date).Date.AddDays(1).ToString('yyyy-MM-dd')
    $schedule = Invoke-Stream "/api/agent/sessions/$agentSessionId/messages/stream" `
        (New-StreamPayload "请安排 $tomorrow 12:00（北京时间）的面试会议，公司是浪速公司，职位是 Agent 开发工程师。时间、公司和职位都已确定，请生成待确认草稿，不要直接写入日程。" $agentSessionId)
    Assert-CompletedStream $schedule '明确日程动作请求'
    Assert-Pass ($schedule.Body -match 'interview.schedule|日程|会议|确认') '日程请求返回了动作或确认信息'
    Write-Host "[INFO] 日程请求 SSE 内容：$($schedule.Body)" -ForegroundColor DarkGray
    $actions = Invoke-JsonApi "/api/agent/sessions/$agentSessionId/actions"
    Assert-Pass ($actions.Json.code -eq 200) '读取通用 Agent 动作列表成功'
    Write-Host "[INFO] 日程请求动作列表：$($actions.Json.data | ConvertTo-Json -Depth 20 -Compress)" -ForegroundColor DarkGray
    Write-Host "[INFO] 日程请求 SSE 关键事件：$(($schedule.Body -split "`n" | Where-Object { $_ -match 'agent-(loop|step|sources|done|error)' } | Select-Object -Last 12) -join ' | ')" -ForegroundColor DarkGray
    $scheduleActions = @($actions.Json.data | Where-Object { [string]$_.toolId -match 'schedule' })
    Assert-Pass ($scheduleActions.Count -ge 1 -and [string]$scheduleActions[-1].status -eq 'PROPOSED') '日程只生成待确认草稿，没有直接写入'
    $schedules = Invoke-JsonApi '/api/interview-schedule'
    Assert-Pass ($schedules.Json.code -eq 200 -and @($schedules.Json.data).Count -eq 0) '未确认前没有新增面试日程'

    $conversation = Invoke-JsonApi "/api/agent/sessions/$agentSessionId"
    Assert-Pass ($conversation.Json.code -eq 200) '读取通用 Agent 会话成功'
    $messages = @($conversation.Json.data.messages)
    $expectedUserMessages = if ($ScheduleOnly) { 1 } else { 4 }
    Assert-Pass ($messages.Count -ge ($expectedUserMessages * 2)) "多轮消息全部持久化（当前 $($messages.Count) 条）"
    Assert-Pass (@($messages | Where-Object { $_.role -eq 'user' }).Count -eq $expectedUserMessages) "$expectedUserMessages 条用户消息没有丢失或重复"

    Write-Host "General Agent context smoke: PASS (session=$agentSessionId, interview=$createdInterview)" -ForegroundColor Cyan
} finally {
    if ($createdInterview) {
        try { Invoke-JsonApi "/api/interview/sessions/$createdInterview" 'DELETE' | Out-Null } catch { }
    }
    if ($null -ne $script:httpClient) { $script:httpClient.Dispose() }
    if ($null -ne $script:httpHandler) { $script:httpHandler.Dispose() }
}

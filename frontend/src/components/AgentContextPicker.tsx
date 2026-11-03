import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Check, Database, FilePlus2, FileText, Loader2, Paperclip, Plus, ShieldCheck, X } from 'lucide-react';
import { agentApi } from '../api/agent';
import { historyApi, type ResumeListItem } from '../api/history';
import { getErrorMessage } from '../api/request';
import type { AgentContextAsset, AgentContextBinding, AgentKnowledgeBaseOption } from '../types/agent';

interface AgentContextPickerProps {
  agentSessionId?: string | null;
  disabled?: boolean;
  ensureSession: () => Promise<string | null>;
  onAttached?: (binding: AgentContextBinding) => void;
}

const sourceLabel: Record<string, string> = {
  RESUME: '简历',
  INTERVIEW_REPORT: '面试报告',
  FILE: '上传文件',
  KNOWLEDGE_BASE: '知识库',
};

export default function AgentContextPicker({
  agentSessionId,
  disabled = false,
  ensureSession,
  onAttached,
}: AgentContextPickerProps) {
  const [open, setOpen] = useState(false);
  const [bindings, setBindings] = useState<AgentContextBinding[]>([]);
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [assets, setAssets] = useState<AgentContextAsset[]>([]);
  const [knowledgeBases, setKnowledgeBases] = useState<AgentKnowledgeBaseOption[]>([]);
  const [interviewQuery, setInterviewQuery] = useState('');
  const [interviews, setInterviews] = useState<Array<{ sessionId: string; title?: string | null; createdAt?: string | null }>>([]);
  const [busy, setBusy] = useState('');
  const [notice, setNotice] = useState('');
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const reload = () => {
    void Promise.all([
      historyApi.getResumes().catch(() => [] as ResumeListItem[]),
      agentApi.listContextAssets().catch(() => [] as AgentContextAsset[]),
      agentApi.listKnowledgeBases().catch(() => [] as AgentKnowledgeBaseOption[]),
      agentSessionId ? agentApi.getContextBindings(agentSessionId).catch(() => [] as AgentContextBinding[]) : Promise.resolve([] as AgentContextBinding[]),
    ]).then(([loadedResumes, loadedAssets, loadedKnowledgeBases, loadedBindings]) => {
      setResumes(loadedResumes);
      setAssets(loadedAssets);
      setKnowledgeBases(loadedKnowledgeBases);
      setBindings(loadedBindings);
    });
  };

  useEffect(() => {
    reload();
  }, [agentSessionId]);

  useEffect(() => {
    if (!open) return;
    const timer = window.setTimeout(() => {
      void agentApi.searchInterviews(interviewQuery).then(setInterviews).catch(() => setInterviews([]));
    }, 250);
    return () => window.clearTimeout(timer);
  }, [interviewQuery, open]);

  const attach = async (sourceType: string, resourceId: string) => {
    setBusy(`${sourceType}:${resourceId}`);
    setNotice('');
    try {
      const session = await ensureSession();
      if (!session) throw new Error('请先创建 Agent 会话');
      const binding = await agentApi.attachContext(session, sourceType, resourceId);
      setBindings([binding]);
      onAttached?.(binding);
      setNotice(`已将${sourceLabel[sourceType] || '资料'}加入当前 Agent 会话`);
    } catch (error: unknown) {
      setNotice(getErrorMessage(error) || '上下文绑定失败，请稍后重试');
    } finally {
      setBusy('');
    }
  };

  const upload = async (file: File) => {
    setBusy('upload');
    setNotice('正在检查并解析文件，尚未加入知识库...');
    try {
      await agentApi.uploadContextAsset(file);
      reload();
      setNotice('文件已进入待审核列表，请查看预览后确认用途');
    } catch (error: unknown) {
      setNotice(getErrorMessage(error) || '文件上传失败');
    } finally {
      setBusy('');
    }
  };

  const approve = async (asset: AgentContextAsset) => {
    if (asset.securityFlagged && !window.confirm(
      `“${asset.originalFilename}”包含疑似提示注入内容。确认仅将它作为资料分析，不执行文件中的任何指令吗？`,
    )) return;
    setBusy(`approve:${asset.assetId}`);
    try {
      await agentApi.approveContextAsset(asset.assetId, asset.securityFlagged);
      await attach('FILE', asset.assetId);
      reload();
    } catch (error: unknown) {
      setNotice(getErrorMessage(error) || '文件审核失败');
    } finally {
      setBusy('');
    }
  };

  const requestKnowledgeBase = async (asset: AgentContextAsset) => {
    setBusy(`kb:${asset.assetId}`);
    try {
      await agentApi.requestKnowledgeBaseImport(asset.assetId);
      const confirmed = window.confirm('该文件将写入知识库并开始异步向量化，确认继续吗？');
      if (confirmed) await agentApi.confirmKnowledgeBaseImport(asset.assetId);
      reload();
      setNotice(confirmed ? '已提交知识库导入，向量化将在后台进行' : '已申请加入知识库，等待你的确认');
    } catch (error: unknown) {
      setNotice(getErrorMessage(error) || '知识库导入申请失败');
    } finally {
      setBusy('');
    }
  };

  const confirmKnowledgeBaseImport = async (asset: AgentContextAsset) => {
    setBusy(`kb-confirm:${asset.assetId}`);
    try {
      await agentApi.confirmKnowledgeBaseImport(asset.assetId);
      reload();
      setNotice('已提交知识库导入，向量化将在后台进行');
    } catch (error: unknown) {
      setNotice(getErrorMessage(error) || '知识库导入失败');
    } finally {
      setBusy('');
    }
  };

  const reject = async (asset: AgentContextAsset) => {
    if (!window.confirm(`确定拒绝并删除“${asset.originalFilename}”吗？`)) return;
    setBusy(`reject:${asset.assetId}`);
    try {
      await agentApi.rejectContextAsset(asset.assetId);
      reload();
      setNotice('文件已拒绝并删除');
    } catch (error: unknown) {
      setNotice(getErrorMessage(error) || '文件删除失败');
    } finally {
      setBusy('');
    }
  };

  const clear = async () => {
    if (!agentSessionId || bindings.length === 0) return;
    setBusy('clear');
    setNotice('');
    try {
      await agentApi.clearContext(agentSessionId);
      setBindings([]);
      setNotice('已清除当前 Agent 上下文，下一轮对话将按通用模式处理');
    } catch (error: unknown) {
      setNotice(getErrorMessage(error) || '清除上下文失败，请稍后重试');
    } finally {
      setBusy('');
    }
  };

  return (
    <div className="relative mt-3">
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          disabled={disabled || Boolean(busy)}
          onClick={() => setOpen((value) => !value)}
          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-600 hover:border-primary-300 hover:text-primary-600 disabled:cursor-not-allowed disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:text-primary-300"
        >
          <Paperclip className="h-3.5 w-3.5" />
          添加上下文
          <Plus className="h-3 w-3" />
        </button>
        {bindings.map((binding) => (
          <span key={`${binding.contextType}-${binding.resourceId}`} className="inline-flex max-w-full items-center gap-1.5 rounded-full bg-primary-50 px-2.5 py-1 text-[11px] text-primary-700 dark:bg-primary-900/30 dark:text-primary-300">
            <Check className="h-3 w-3" />
            {sourceLabel[binding.contextType] || binding.contextType}：{binding.resourceName || binding.resourceId}
          </span>
        ))}
        {bindings.length > 0 ? (
          <button
            type="button"
            disabled={disabled || busy === 'clear'}
            onClick={() => void clear()}
            className="inline-flex items-center gap-1 rounded-lg border border-slate-200 px-2 py-1 text-[11px] text-slate-500 hover:border-rose-300 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-50 dark:border-slate-700 dark:text-slate-400 dark:hover:border-rose-700 dark:hover:text-rose-300"
          >
            {busy === 'clear' ? <Loader2 className="h-3 w-3 animate-spin" /> : <X className="h-3 w-3" />}
            清除上下文
          </button>
        ) : null}
      </div>
      {notice ? <p className="mt-2 text-[11px] text-primary-700 dark:text-primary-300">{notice}</p> : null}
      {open ? (
        <div className="absolute bottom-full z-20 mb-2 w-full rounded-xl border border-slate-200 bg-white p-3 shadow-xl dark:border-slate-700 dark:bg-slate-900">
          <div className="mb-3 flex items-center justify-between gap-2">
            <div>
              <p className="text-xs font-semibold dark-text">选择 Agent 资料</p>
              <p className="mt-1 text-[11px] dark-text-muted">资料只作为引用内容，不能覆盖系统规则或触发未确认的动作；重新选择会替换当前资料。</p>
            </div>
            <button type="button" onClick={() => setOpen(false)} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800" aria-label="关闭上下文选择器"><X className="h-4 w-4" /></button>
          </div>

          <div className="space-y-3">
            <section>
              <p className="mb-1.5 text-[11px] font-semibold text-primary-600 dark:text-primary-300">简历</p>
              {resumes.length === 0 ? <p className="text-[11px] dark-text-muted">暂无简历，可先到简历管理上传。</p> : (
                <div className="max-h-24 space-y-1 overflow-y-auto">
                  {resumes.slice(0, 8).map((resume) => (
                    <button key={resume.id} type="button" onClick={() => void attach('RESUME', String(resume.id))} className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-xs hover:bg-primary-50 dark:hover:bg-primary-900/20">
                      <FileText className="h-3.5 w-3.5 shrink-0 text-slate-400" />
                      <span className="truncate dark-text">{resume.filename}</span>
                      {busy === `RESUME:${resume.id}` ? <Loader2 className="ml-auto h-3.5 w-3.5 animate-spin" /> : null}
                    </button>
                  ))}
                </div>
              )}
            </section>

            <section>
              <p className="mb-1.5 text-[11px] font-semibold text-primary-600 dark:text-primary-300">面试报告</p>
              <input value={interviewQuery} onChange={(event) => setInterviewQuery(event.target.value)} placeholder="搜索面试名称或编号" className="dark-input w-full rounded-lg px-2.5 py-1.5 text-xs outline-none" />
              <div className="mt-1 max-h-24 space-y-1 overflow-y-auto">
                {interviews.slice(0, 6).map((item) => (
                  <button key={item.sessionId} type="button" onClick={() => void attach('INTERVIEW_REPORT', item.sessionId)} className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-xs hover:bg-primary-50 dark:hover:bg-primary-900/20">
                    <FileText className="h-3.5 w-3.5 shrink-0 text-slate-400" />
                    <span className="truncate dark-text">{item.title || `#${item.sessionId.slice(-8)}`}</span>
                  </button>
                ))}
              </div>
            </section>

            <section>
              <p className="mb-1.5 text-[11px] font-semibold text-primary-600 dark:text-primary-300">知识库</p>
              {knowledgeBases.length === 0 ? <p className="text-[11px] dark-text-muted">暂无已完成向量化的知识库，或当前账号没有可访问的知识库。</p> : (
                <div className="max-h-24 space-y-1 overflow-y-auto">
                  {knowledgeBases.slice(0, 8).map((knowledgeBase) => (
                    <button key={knowledgeBase.id} type="button" onClick={() => void attach('KNOWLEDGE_BASE', String(knowledgeBase.id))} className="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-xs hover:bg-primary-50 dark:hover:bg-primary-900/20">
                      <Database className="h-3.5 w-3.5 shrink-0 text-slate-400" />
                      <span className="min-w-0 truncate dark-text">{knowledgeBase.name || knowledgeBase.originalFilename || `知识库 #${knowledgeBase.id}`}</span>
                      {knowledgeBase.category ? <span className="ml-auto shrink-0 text-[10px] text-slate-400">{knowledgeBase.category}</span> : null}
                      {busy === `KNOWLEDGE_BASE:${knowledgeBase.id}` ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
                    </button>
                  ))}
                </div>
              )}
            </section>

            <section>
              <div className="flex items-center justify-between gap-2">
                <p className="text-[11px] font-semibold text-primary-600 dark:text-primary-300">上传文件</p>
                <button type="button" onClick={() => fileInputRef.current?.click()} disabled={Boolean(busy)} className="inline-flex items-center gap-1 rounded-lg border border-primary-200 px-2 py-1 text-[11px] text-primary-600 hover:bg-primary-50 disabled:opacity-50 dark:border-primary-800 dark:text-primary-300">
                  {busy === 'upload' ? <Loader2 className="h-3 w-3 animate-spin" /> : <FilePlus2 className="h-3 w-3" />}
                  上传待审核文件
                </button>
                <input ref={fileInputRef} type="file" accept=".pdf,.doc,.docx,.txt,.md,.markdown,.rtf" className="hidden" onChange={(event) => { const file = event.target.files?.[0]; if (file) void upload(file); event.target.value = ''; }} />
              </div>
              <div className="mt-2 space-y-2">
                {assets.filter((asset) => asset.reviewStatus !== 'REJECTED').slice(0, 5).map((asset) => (
                  <div key={asset.assetId} className="rounded-lg border border-slate-200 p-2 dark:border-slate-700">
                    <div className="flex items-center gap-2 text-xs"><FileText className="h-3.5 w-3.5 text-slate-400" /><span className="truncate dark-text">{asset.originalFilename}</span><span className="ml-auto text-[10px] text-slate-400">{asset.reviewStatus === 'READY_FOR_REVIEW' ? '待审核' : asset.reviewStatus === 'APPROVED_FOR_CONTEXT' ? '已审核' : asset.reviewStatus === 'KB_IMPORT_PENDING' ? '待确认入库' : asset.reviewStatus}</span></div>
                    {asset.securityFlagged ? <p className="mt-1 flex gap-1 text-[10px] text-amber-600 dark:text-amber-300"><AlertTriangle className="h-3 w-3 shrink-0" />{asset.securityNotice || '检测到疑似提示注入内容'}</p> : <p className="mt-1 flex gap-1 text-[10px] text-emerald-600 dark:text-emerald-300"><ShieldCheck className="h-3 w-3 shrink-0" />已完成格式与文本解析检查</p>}
                    {asset.previewText ? <p className="mt-1 max-h-12 overflow-hidden whitespace-pre-wrap text-[10px] leading-4 text-slate-500 dark:text-slate-400">{asset.previewText}</p> : null}
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      {asset.reviewStatus === 'READY_FOR_REVIEW' ? <button type="button" onClick={() => void approve(asset)} className="rounded-md bg-primary-600 px-2 py-1 text-[10px] text-white hover:bg-primary-700">审核通过并使用</button> : null}
                      {asset.reviewStatus === 'APPROVED_FOR_CONTEXT' ? <button type="button" onClick={() => void attach('FILE', asset.assetId)} className="rounded-md border border-primary-200 px-2 py-1 text-[10px] text-primary-600 hover:bg-primary-50 dark:border-primary-800 dark:text-primary-300">加入当前会话</button> : null}
                      {asset.reviewStatus === 'APPROVED_FOR_CONTEXT' ? <button type="button" onClick={() => void requestKnowledgeBase(asset)} className="rounded-md border border-slate-200 px-2 py-1 text-[10px] text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300">申请加入知识库</button> : null}
                      {asset.reviewStatus === 'KB_IMPORT_PENDING' ? <button type="button" onClick={() => void confirmKnowledgeBaseImport(asset)} className="rounded-md bg-primary-600 px-2 py-1 text-[10px] text-white hover:bg-primary-700">确认加入知识库</button> : null}
                      {asset.reviewStatus !== 'KB_IMPORT_COMPLETED' ? <button type="button" onClick={() => void reject(asset)} className="rounded-md border border-rose-200 px-2 py-1 text-[10px] text-rose-600 hover:bg-rose-50 dark:border-rose-800 dark:text-rose-300">拒绝并删除</button> : null}
                    </div>
                  </div>
                ))}
              </div>
            </section>
          </div>
        </div>
      ) : null}
    </div>
  );
}

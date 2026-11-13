import { useEffect, useMemo, useRef, useState } from 'react';
import { CheckCircle2, Eye, FileUp, Loader2, Power, ShieldAlert, Sparkles, XCircle } from 'lucide-react';
import { agentSkillApi } from '../api/agentSkill';
import { skillApi, type SkillDTO } from '../api/skill';
import type { AgentUserSkill } from '../types/agentSkill';
import AgentNavigation from '../components/AgentNavigation';
import { getErrorMessage } from '../api/request';

function statusLabel(status: AgentUserSkill['status']): string {
  return status === 'PENDING_REVIEW' ? '待审核'
    : status === 'ENABLED' ? '已启用'
      : status === 'DISABLED' ? '已停用' : '已拒绝';
}

function statusClass(status: AgentUserSkill['status']): string {
  return status === 'ENABLED'
    ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300'
    : status === 'PENDING_REVIEW'
      ? 'bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'
      : 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300';
}

export default function AgentSkillLibraryPage() {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [userSkills, setUserSkills] = useState<AgentUserSkill[]>([]);
  const [builtInSkills, setBuiltInSkills] = useState<SkillDTO[]>([]);
  const [selectedSkill, setSelectedSkill] = useState<AgentUserSkill | null>(null);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const [installed, builtIn] = await Promise.all([agentSkillApi.list(), skillApi.listSkills()]);
      setUserSkills(installed);
      setBuiltInSkills(builtIn);
    } catch (reason) {
      setError(getErrorMessage(reason));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const resumeSkills = useMemo(
    () => builtInSkills.filter(skill => skill.id.startsWith('resume-')),
    [builtInSkills],
  );

  const upload = async (file?: File) => {
    if (!file) return;
    setUploading(true);
    setError('');
    setNotice('');
    try {
      await agentSkillApi.upload(file);
      setNotice('Skill 已上传，审核通过并启用后，Agent 才会在需要时使用。');
      await load();
    } catch (reason) {
      setError(getErrorMessage(reason));
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const updateStatus = async (skill: AgentUserSkill, action: 'approve' | 'enable' | 'disable' | 'reject') => {
    setBusyId(skill.skillId);
    setError('');
    setNotice('');
    try {
      if (action === 'approve') {
        const confirmSecurity = skill.securityFlagged
          ? window.confirm('该 Skill 检测到潜在提示注入。你确认已查看正文，并允许它作为任务方法参考吗？')
          : false;
        if (skill.securityFlagged && !confirmSecurity) return;
        await agentSkillApi.approve(skill.skillId, confirmSecurity);
      } else if (action === 'enable') {
        await agentSkillApi.enable(skill.skillId);
      } else if (action === 'disable') {
        await agentSkillApi.disable(skill.skillId);
      } else {
        await agentSkillApi.reject(skill.skillId);
      }
      setNotice(action === 'reject' ? 'Skill 已拒绝。' : 'Skill 状态已更新。');
      await load();
    } catch (reason) {
      setError(getErrorMessage(reason));
    } finally {
      setBusyId(null);
    }
  };

  const preview = async (skill: AgentUserSkill) => {
    setError('');
    try {
      setSelectedSkill(await agentSkillApi.get(skill.skillId));
    } catch (reason) {
      setError(getErrorMessage(reason));
    }
  };

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.2em] text-primary-500">SKILL LIBRARY</p>
          <h1 className="mt-2 text-2xl font-bold dark-text">Skill 管理</h1>
          <p className="mt-2 text-sm dark-text-muted">管理任务方法资料。启用的 Skill 只会在当前问题需要时被 Agent 按需加载。</p>
        </div>
        <div>
          <input
            ref={fileInputRef}
            type="file"
            accept=".md,.markdown,.txt,text/markdown,text/plain"
            className="hidden"
            onChange={event => void upload(event.target.files?.[0])}
          />
          <button
            type="button"
            disabled={uploading}
            onClick={() => fileInputRef.current?.click()}
            className="btn-primary inline-flex items-center gap-2 rounded-xl px-4 py-2.5 text-sm disabled:opacity-60"
          >
            {uploading ? <Loader2 className="h-4 w-4 animate-spin" /> : <FileUp className="h-4 w-4" />}
            上传 Skill
          </button>
        </div>
      </div>

      <AgentNavigation />

      {error ? <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-950/30 dark:text-red-300">{error}</div> : null}
      {notice ? <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700 dark:border-emerald-800 dark:bg-emerald-950/30 dark:text-emerald-300">{notice}</div> : null}

      <section className="rounded-2xl border border-indigo-100 bg-white/80 p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900/70">
        <div className="flex items-center gap-3">
          <div className="rounded-xl bg-indigo-100 p-2 text-indigo-600 dark:bg-indigo-900/30 dark:text-indigo-300"><Sparkles className="h-5 w-5" /></div>
          <div>
            <h2 className="font-semibold dark-text">内置 Skill</h2>
            <p className="mt-1 text-xs dark-text-muted">系统预置 Skill 已经可以直接使用，不需要上传或审核。</p>
          </div>
        </div>
        <div className="mt-4 grid gap-3 md:grid-cols-2">
          {resumeSkills.map(skill => (
            <div key={skill.id} className="rounded-xl border border-slate-200 bg-slate-50/80 p-4 dark:border-slate-700 dark:bg-slate-800/60">
              <div className="flex items-center gap-2"><CheckCircle2 className="h-4 w-4 text-emerald-500" /><span className="text-sm font-medium dark-text">{skill.name}</span></div>
              <p className="mt-2 text-xs leading-5 dark-text-muted">{skill.description}</p>
            </div>
          ))}
          {resumeSkills.length === 0 ? <p className="text-sm dark-text-muted">正在加载内置简历 Skill。</p> : null}
        </div>
      </section>

      <section className="rounded-2xl border border-slate-200 bg-white/80 p-5 shadow-sm dark:border-slate-700 dark:bg-slate-900/70">
        <div className="flex items-center justify-between gap-3">
          <div><h2 className="font-semibold dark-text">我的 Skill</h2><p className="mt-1 text-xs dark-text-muted">上传 Markdown 或 TXT 后，先查看内容再审核启用。</p></div>
          {loading ? <Loader2 className="h-4 w-4 animate-spin text-primary-500" /> : null}
        </div>
        {!loading && userSkills.length === 0 ? <p className="mt-5 rounded-xl bg-slate-50 p-4 text-sm dark-text-muted dark:bg-slate-800/60">还没有上传的 Skill。可以上传一份简历优化或文档排版 Skill。</p> : null}
        <div className="mt-4 grid gap-3 lg:grid-cols-2">
          {userSkills.map(skill => (
            <div key={skill.skillId} className="rounded-xl border border-slate-200 p-4 dark:border-slate-700">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0"><h3 className="truncate text-sm font-semibold dark-text">{skill.name}</h3><p className="mt-1 text-xs dark-text-muted">v{skill.version} · {skill.originalFilename}</p></div>
                <span className={`shrink-0 rounded-full px-2 py-1 text-[11px] font-medium ${statusClass(skill.status)}`}>{statusLabel(skill.status)}</span>
              </div>
              <p className="mt-3 line-clamp-2 text-xs leading-5 dark-text-muted">{skill.description || '暂无说明'}</p>
              {skill.securityFlagged ? <p className="mt-2 flex items-center gap-1 text-xs text-amber-600 dark:text-amber-300"><ShieldAlert className="h-3.5 w-3.5" />{skill.securityNotice}</p> : null}
              <div className="mt-4 flex flex-wrap gap-2">
                <button type="button" onClick={() => void preview(skill)} className="btn-secondary inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs"><Eye className="h-3.5 w-3.5" />查看内容</button>
                {skill.status === 'PENDING_REVIEW' ? <button type="button" disabled={busyId === skill.skillId} onClick={() => void updateStatus(skill, 'approve')} className="btn-primary inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs disabled:opacity-50"><CheckCircle2 className="h-3.5 w-3.5" />审核并启用</button> : null}
                {skill.status === 'ENABLED' ? <button type="button" disabled={busyId === skill.skillId} onClick={() => void updateStatus(skill, 'disable')} className="btn-secondary inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs disabled:opacity-50"><Power className="h-3.5 w-3.5" />停用</button> : null}
                {skill.status === 'DISABLED' ? <button type="button" disabled={busyId === skill.skillId} onClick={() => void updateStatus(skill, 'enable')} className="btn-primary inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs disabled:opacity-50"><Power className="h-3.5 w-3.5" />启用</button> : null}
                {skill.status !== 'REJECTED' ? <button type="button" disabled={busyId === skill.skillId} onClick={() => void updateStatus(skill, 'reject')} className="inline-flex items-center gap-1.5 rounded-lg border border-red-200 px-2.5 py-1.5 text-xs text-red-600 hover:bg-red-50 disabled:opacity-50 dark:border-red-800 dark:text-red-300 dark:hover:bg-red-950/30"><XCircle className="h-3.5 w-3.5" />拒绝</button> : null}
              </div>
            </div>
          ))}
        </div>
      </section>

      {selectedSkill ? (
        <div className="fixed inset-0 z-[70] flex items-center justify-center bg-slate-950/40 p-4" role="dialog" aria-modal="true" aria-label="Skill 内容预览" onClick={() => setSelectedSkill(null)}>
          <div className="max-h-[80vh] w-full max-w-3xl overflow-hidden rounded-2xl bg-white shadow-xl dark:bg-slate-900" onClick={event => event.stopPropagation()}>
            <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4 dark:border-slate-700"><div><h2 className="font-semibold dark-text">{selectedSkill.name}</h2><p className="mt-1 text-xs dark-text-muted">v{selectedSkill.version} · {statusLabel(selectedSkill.status)}</p></div><button type="button" className="btn-secondary rounded-lg px-3 py-1.5 text-xs" onClick={() => setSelectedSkill(null)}>关闭</button></div>
            <pre className="max-h-[68vh] overflow-auto whitespace-pre-wrap p-5 text-xs leading-6 text-slate-700 dark:text-slate-200">{selectedSkill.content || '暂无内容'}</pre>
          </div>
        </div>
      ) : null}
    </div>
  );
}

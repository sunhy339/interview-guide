import { useEffect, useState, lazy, Suspense } from 'react';
import { Check, Copy } from 'lucide-react';
import {
  isHighlightableCodeLanguage,
  normalizeCodeLanguage,
} from './codeLanguage';

// Prism 全量语言包接近 700KB。只在首次出现代码块时加载产品实际覆盖的语言。
const SyntaxHighlighter = lazy(async () => {
  const [highlighterModule, ...languageModules] = await Promise.all([
    import('react-syntax-highlighter/dist/esm/prism-light'),
    import('react-syntax-highlighter/dist/esm/languages/prism/bash'),
    import('react-syntax-highlighter/dist/esm/languages/prism/cpp'),
    import('react-syntax-highlighter/dist/esm/languages/prism/csharp'),
    import('react-syntax-highlighter/dist/esm/languages/prism/java'),
    import('react-syntax-highlighter/dist/esm/languages/prism/javascript'),
    import('react-syntax-highlighter/dist/esm/languages/prism/json'),
    import('react-syntax-highlighter/dist/esm/languages/prism/markdown'),
    import('react-syntax-highlighter/dist/esm/languages/prism/powershell'),
    import('react-syntax-highlighter/dist/esm/languages/prism/python'),
    import('react-syntax-highlighter/dist/esm/languages/prism/sql'),
    import('react-syntax-highlighter/dist/esm/languages/prism/typescript'),
    import('react-syntax-highlighter/dist/esm/languages/prism/markup'),
    import('react-syntax-highlighter/dist/esm/languages/prism/yaml'),
  ]);
  const highlighter = highlighterModule.default;
  const registrations: Array<[string, unknown]> = [
    ['bash', languageModules[0].default],
    ['cpp', languageModules[1].default],
    ['csharp', languageModules[2].default],
    ['java', languageModules[3].default],
    ['javascript', languageModules[4].default],
    ['json', languageModules[5].default],
    ['markdown', languageModules[6].default],
    ['powershell', languageModules[7].default],
    ['python', languageModules[8].default],
    ['sql', languageModules[9].default],
    ['typescript', languageModules[10].default],
    ['xml', languageModules[11].default],
    ['yaml', languageModules[12].default],
  ];
  registrations.forEach(([name, grammar]) => highlighter.registerLanguage(name, grammar));
  return { default: highlighter };
});

interface CodeBlockProps {
  language?: string;
  children: string;
}

export default function CodeBlock({ language, children }: CodeBlockProps) {
  const [copied, setCopied] = useState(false);
  const [syntaxTheme, setSyntaxTheme] = useState<Record<string, unknown> | null>(null);
  const normalizedLanguage = normalizeCodeLanguage(language);
  const shouldHighlight = isHighlightableCodeLanguage(language);

  useEffect(() => {
    if (!shouldHighlight) {
      return undefined;
    }
    let active = true;
    void import('react-syntax-highlighter/dist/esm/styles/prism').then(({ oneDark }) => {
      if (active) setSyntaxTheme(oneDark as Record<string, unknown>);
    });
    return () => {
      active = false;
    };
  }, [shouldHighlight]);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(children);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('复制失败:', err);
    }
  };

  // 清理代码内容
  const code = children?.trim() || '';

  return (
    <div className="relative group my-3">
      {/* 语言标签和复制按钮 */}
      <div className="flex items-center justify-between px-4 py-2 bg-slate-700 rounded-t-xl border-b border-slate-600">
        <span className="text-xs text-slate-400 font-mono">
          {language || 'code'}
        </span>
        <button
          onClick={handleCopy}
          className="flex items-center gap-1.5 px-2 py-1 text-xs text-slate-400 hover:text-white hover:bg-slate-600 rounded transition-colors"
          title="复制代码"
        >
          {copied ? (
            <>
              <Check className="w-3.5 h-3.5 text-green-400" />
              <span className="text-green-400">已复制</span>
            </>
          ) : (
            <>
              <Copy className="w-3.5 h-3.5" />
              <span>复制</span>
            </>
          )}
        </button>
      </div>

      {/* 代码内容 */}
      <div className="bg-[#282c34] rounded-b-xl text-sm leading-6">
        {shouldHighlight ? (
          <Suspense fallback={
            <pre className="overflow-x-auto p-4 text-slate-200 font-mono text-xs">{code}</pre>
          }>
            <SyntaxHighlighter
              language={normalizedLanguage}
              style={syntaxTheme || {}}
              customStyle={{
                margin: 0,
                borderTopLeftRadius: 0,
                borderTopRightRadius: 0,
                borderBottomLeftRadius: '0.75rem',
                borderBottomRightRadius: '0.75rem',
                fontSize: '0.875rem',
                lineHeight: '1.5',
              }}
              showLineNumbers={code.split('\n').length > 3}
              wrapLines
            >
              {code}
            </SyntaxHighlighter>
          </Suspense>
        ) : (
          <pre className="overflow-x-auto p-4 text-slate-200 font-mono text-xs">{code}</pre>
        )}
      </div>
    </div>
  );
}

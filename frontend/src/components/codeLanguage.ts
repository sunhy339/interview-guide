export const HIGHLIGHTABLE_LANGUAGES: ReadonlySet<string> = new Set([
  'bash', 'cpp', 'csharp', 'java', 'javascript', 'json', 'markdown',
  'powershell', 'python', 'sql', 'typescript', 'xml', 'yaml',
]);

const LANGUAGE_ALIASES: Record<string, string> = {
  'c++': 'cpp',
  'c#': 'csharp',
  'cs': 'csharp',
  'html': 'xml',
  'js': 'javascript',
  'jsx': 'javascript',
  'md': 'markdown',
  'ps': 'powershell',
  'ps1': 'powershell',
  'py': 'python',
  'sh': 'bash',
  'shell': 'bash',
  'ts': 'typescript',
  'tsx': 'typescript',
  'yml': 'yaml',
};

export function normalizeCodeLanguage(language?: string): string {
  const normalized = language?.trim().toLowerCase() || '';
  return LANGUAGE_ALIASES[normalized] || normalized;
}

export function isHighlightableCodeLanguage(language?: string): boolean {
  return HIGHLIGHTABLE_LANGUAGES.has(normalizeCodeLanguage(language));
}

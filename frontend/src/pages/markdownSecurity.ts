/**
 * Markdown from the model, RAG and uploaded files is untrusted display data.
 * Keep links useful, but never allow a rendered message to become a script
 * launcher through a dangerous URL scheme.
 */
export function sanitizeMarkdownHref(href: string | undefined) {
  const value = href?.trim();
  if (!value || value.startsWith('//')) return undefined;

  try {
    const parsed = new URL(value, 'https://agent.local');
    if (!['http:', 'https:', 'mailto:'].includes(parsed.protocol)) return undefined;
    return value;
  } catch {
    return undefined;
  }
}

export function isExternalMarkdownHref(href: string | undefined) {
  const safeHref = sanitizeMarkdownHref(href);
  if (!safeHref) return false;
  return /^https?:\/\//i.test(safeHref);
}

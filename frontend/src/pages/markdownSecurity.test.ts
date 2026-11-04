import assert from 'node:assert/strict';
import test from 'node:test';

import { isExternalMarkdownHref, sanitizeMarkdownHref } from './markdownSecurity.ts';

test('Markdown link only allows safe web, mail and relative URL schemes', () => {
  assert.equal(sanitizeMarkdownHref('https://example.com/docs'), 'https://example.com/docs');
  assert.equal(sanitizeMarkdownHref('/interviews/1'), '/interviews/1');
  assert.equal(sanitizeMarkdownHref('mailto:hello@example.com'), 'mailto:hello@example.com');
  assert.equal(sanitizeMarkdownHref('javascript:alert(1)'), undefined);
  assert.equal(sanitizeMarkdownHref('data:text/html,<script>alert(1)</script>'), undefined);
  assert.equal(sanitizeMarkdownHref('//attacker.example/redirect'), undefined);
});

test('only absolute web links open as external links', () => {
  assert.equal(isExternalMarkdownHref('https://example.com'), true);
  assert.equal(isExternalMarkdownHref('/interviews/1'), false);
  assert.equal(isExternalMarkdownHref('mailto:hello@example.com'), false);
  assert.equal(isExternalMarkdownHref('javascript:alert(1)'), false);
});

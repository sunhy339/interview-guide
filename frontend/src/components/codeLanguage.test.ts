import assert from 'node:assert/strict';
import test from 'node:test';
import {
  HIGHLIGHTABLE_LANGUAGES,
  isHighlightableCodeLanguage,
  normalizeCodeLanguage,
} from './codeLanguage.ts';

test('normalizes common code fence aliases', () => {
  assert.equal(normalizeCodeLanguage(' JS '), 'javascript');
  assert.equal(normalizeCodeLanguage('tsx'), 'typescript');
  assert.equal(normalizeCodeLanguage('HTML'), 'xml');
  assert.equal(normalizeCodeLanguage(undefined), '');
});

test('unknown languages fall back to plain text', () => {
  assert.equal(isHighlightableCodeLanguage('java'), true);
  assert.equal(isHighlightableCodeLanguage('unknown-language'), false);
  assert.equal(HIGHLIGHTABLE_LANGUAGES.has('unknown-language'), false);
});

import { describe, expect, it } from 'vitest';
import { embeddingInput, toStoredVector } from './embedding.js';

describe('embeddingInput', () => {
  it.each([
    [
      'qwen3-embedding-0.6b',
      'Instruct: Given a search query, retrieve the documents that answer it\nQuery:rent',
      'rent',
    ],
    ['embeddinggemma-2', 'task: search result | query: rent', 'title: none | text: rent'],
    ['multilingual-e5-small', 'query: rent', 'passage: rent'],
    ['multilingual-e5-base', 'query: rent', 'passage: rent'],
    ['bge-m3', 'rent', 'rent'],
    ['text-embedding-3-small', 'rent', 'rent'],
  ])('formats %s as its model card prescribes', (model, query, document) => {
    expect(embeddingInput(model, 'query', 'rent')).toBe(query);
    expect(embeddingInput(model, 'document', 'rent')).toBe(document);
  });

  it('does not mistake other names for e5', () => {
    expect(embeddingInput('model-e50', 'query', 'x')).toBe('x');
    expect(embeddingInput('sentence-e5x', 'query', 'x')).toBe('x');
  });
});

describe('toStoredVector', () => {
  it('truncates to the leading dimensions and re-normalises', () => {
    const vector = toStoredVector([3, 4, 12, 0], 2)!;
    expect(Array.from(vector)).toEqual([0.6000000238418579, 0.800000011920929]);
  });

  it('refuses vectors that are too short, empty or not finite', () => {
    expect(toStoredVector([1], 2)).toBeNull();
    expect(toStoredVector([0, 0, 0], 2)).toBeNull();
    expect(toStoredVector([Number.NaN, 1], 2)).toBeNull();
  });
});

/**
 * Model-specific input formats and vector handling for embeddings.
 *
 * Retrieval embedding models are asymmetric: queries and documents are encoded
 * with different prefixes, as their model cards prescribe. The format is chosen
 * from the configured model name (`AI_EMBED_MODEL`, the llama.cpp `--alias`),
 * so the alias must contain the model family. tools/ai-eval/ai_eval/embedding.py
 * mirrors this file; change both together.
 */

export type EmbeddingKind = 'query' | 'document';

interface InputFormat {
  readonly pattern: RegExp;
  readonly query: (text: string) => string;
  readonly document: (text: string) => string;
}

/** English instruction, as the Qwen3-Embedding card advises for multilingual use. */
const QWEN_TASK = 'Given a search query, retrieve the documents that answer it';

const FORMATS: readonly InputFormat[] = [
  {
    // https://huggingface.co/Qwen/Qwen3-Embedding-0.6B (instruction on the query side only)
    pattern: /qwen3[-_.]?(vl[-_.]?)?embedding/iu,
    query: (text) => `Instruct: ${QWEN_TASK}\nQuery:${text}`,
    document: (text) => text,
  },
  {
    // https://huggingface.co/google/embeddinggemma-2 ("SearchQuery" / "Document" prompts)
    pattern: /embedding[-_.]?gemma/iu,
    query: (text) => `task: search result | query: ${text}`,
    document: (text) => `title: none | text: ${text}`,
  },
  {
    // https://huggingface.co/intfloat/multilingual-e5-small ("query: " / "passage: ")
    pattern: /(^|[^a-z0-9])e5([^a-z0-9]|$)/iu,
    query: (text) => `query: ${text}`,
    document: (text) => `passage: ${text}`,
  },
];

/** The text to send for one input. Models without a known format (bge-m3, cloud APIs) get it unchanged. */
export function embeddingInput(model: string, kind: EmbeddingKind, text: string): string {
  const format = FORMATS.find((candidate) => candidate.pattern.test(model));
  if (format === undefined) {
    return text;
  }
  return kind === 'query' ? format.query(text) : format.document(text);
}

/**
 * Keeps the first `dimensions` values (Matryoshka truncation) and scales the
 * result to unit length, so a dot product is the cosine similarity. Returns
 * null for a vector that is too short or has no length; the caller decides.
 */
export function toStoredVector(values: readonly number[], dimensions: number): Float32Array | null {
  if (values.length < dimensions) {
    return null;
  }
  const vector = Float32Array.from(values.slice(0, dimensions));
  let norm = 0;
  for (const value of vector) {
    if (!Number.isFinite(value)) {
      return null;
    }
    norm += value * value;
  }
  norm = Math.sqrt(norm);
  if (norm === 0) {
    return null;
  }
  return vector.map((value) => value / norm);
}

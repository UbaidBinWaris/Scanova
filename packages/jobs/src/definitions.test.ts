import { JOB_NAMES } from '@scanova/contracts/jobs';
import { describe, expect, it } from 'vitest';
import { JOB_DEFINITIONS, JOB_NAME_LIST, parseJobPayload } from './definitions.js';

const ID = '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f';

describe('job definitions', () => {
  it('defines every job exactly once', () => {
    expect(Object.keys(JOB_DEFINITIONS).sort()).toEqual([...JOB_NAME_LIST].sort());
    for (const name of JOB_NAME_LIST) {
      expect(JOB_DEFINITIONS[name].name).toBe(name);
      expect(JOB_DEFINITIONS[name].retryLimit).toBeGreaterThanOrEqual(1);
    }
  });

  it('schedules exactly the periodic jobs', () => {
    const recurring = JOB_NAME_LIST.filter((name) => JOB_DEFINITIONS[name].cron !== null).sort();
    expect(recurring).toEqual(
      [JOB_NAMES.keyGarbageCollect, JOB_NAMES.rotationSweep, JOB_NAMES.trashPurge].sort(),
    );
  });

  it('deduplicates by the id in the payload', () => {
    expect(JOB_DEFINITIONS[JOB_NAMES.documentOcr].dedupeKey?.({ documentId: ID })).toBe(ID);
    expect(JOB_DEFINITIONS[JOB_NAMES.rotationRun].dedupeKey?.({ runId: ID })).toBe(ID);
    expect(JOB_DEFINITIONS[JOB_NAMES.userPurge].dedupeKey?.({ userId: ID })).toBe(ID);
  });

  it('accepts ids only as payload', () => {
    expect(parseJobPayload(JOB_NAMES.documentOcr, { documentId: ID.toUpperCase() })).toEqual({
      documentId: ID,
    });
    expect(() =>
      parseJobPayload(JOB_NAMES.documentOcr, { documentId: ID, title: 'Tax return' }),
    ).toThrow();
    expect(() => parseJobPayload(JOB_NAMES.documentOcr, { documentId: 'not-an-id' })).toThrow();
    expect(() => parseJobPayload(JOB_NAMES.rotationSweep, { anything: 1 })).toThrow();
  });
});

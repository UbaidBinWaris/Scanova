const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;

function uuid(value: string, name: string): string {
  if (!UUID_PATTERN.test(value)) {
    throw new Error(`${name} must be a lower-case UUID.`);
  }
  return value;
}

/** Everything a user has stored lives under this prefix, which makes a purge one listing. */
export function userObjectPrefix(userId: string): string {
  return `u/${uuid(userId, 'User id')}/`;
}

export function documentObjectPrefix(userId: string, documentId: string): string {
  return `${userObjectPrefix(userId)}${uuid(documentId, 'Document id')}/`;
}

/**
 * Bucket key of one stored object. It is built from random ids only, so it
 * reveals nothing about the content.
 */
export function objectKeyFor(ids: {
  readonly userId: string;
  readonly documentId: string;
  readonly objectId: string;
}): string {
  return `${documentObjectPrefix(ids.userId, ids.documentId)}${uuid(ids.objectId, 'Object id')}`;
}

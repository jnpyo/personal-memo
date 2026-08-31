import { describe, expect, it } from 'vitest';
import { assertDestructiveCleanupAllowed } from './destructiveCleanupGuard';

describe('destructive E2E cleanup guard', () => {
  it.each([undefined, '', 'false', 'TRUE'])('rejects permission value %s', (permission) => {
    expect(() => assertDestructiveCleanupAllowed(permission)).toThrow(
      'E2E_ALLOW_DESTRUCTIVE_CLEANUP=true',
    );
  });

  it('accepts only the explicit true opt-in', () => {
    expect(() => assertDestructiveCleanupAllowed('true')).not.toThrow();
  });
});

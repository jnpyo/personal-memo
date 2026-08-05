const REQUIRED_VALUE = 'true';

export function assertDestructiveCleanupAllowed(permission: string | undefined): void {
  if (permission === REQUIRED_VALUE) return;
  throw new Error(
    'E2E cleanup is destructive. Run only against an isolated test server and set E2E_ALLOW_DESTRUCTIVE_CLEANUP=true.',
  );
}

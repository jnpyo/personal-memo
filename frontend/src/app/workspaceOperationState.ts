import type { AuthOperation } from '../features/auth/authState';

export function hasPendingServerOperation(input: {
  workspaceBusy: boolean;
  pendingTaskId: string | null;
  authOperation: AuthOperation | null;
}): boolean {
  return input.workspaceBusy || input.pendingTaskId !== null || input.authOperation !== null;
}

export function isLatestWorkspaceRequest(request: number, latestStarted: number): boolean {
  return request === latestStarted;
}

export function isCurrentScopedRequest(input: {
  request: number;
  latestStarted: number;
  aborted: boolean;
  expectedScope: string;
  currentScope: string | null;
}): boolean {
  return input.request === input.latestStarted &&
    !input.aborted &&
    input.expectedScope === input.currentScope;
}

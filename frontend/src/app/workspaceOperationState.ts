import type { AuthOperation } from '../features/auth/authState';

type RefreshAction = () => Promise<unknown>;

export function hasPendingServerOperation(input: {
  workspaceBusy: boolean;
  pendingTaskId: string | null;
  authOperation: AuthOperation | null;
  calendarSharingPending?: boolean;
}): boolean {
  return input.workspaceBusy || input.pendingTaskId !== null || input.authOperation !== null ||
    input.calendarSharingPending === true;
}

export function isLatestWorkspaceRequest(request: number, latestStarted: number): boolean {
  return request === latestStarted;
}

export async function refreshAfterMemoSourceEdit(input: {
  refreshMemos: RefreshAction;
  refreshEvents: RefreshAction;
  refreshRecovery: RefreshAction;
}): Promise<void> {
  await Promise.all([
    input.refreshMemos(),
    input.refreshEvents(),
    input.refreshRecovery(),
  ]);
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

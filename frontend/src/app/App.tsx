import { useEffect, useReducer, useRef, useState, type ComponentProps } from 'react';
import { AccountPanel } from '../features/auth/AccountPanel';
import { AuthScreen } from '../features/auth/AuthScreen';
import type { LocalAuthInput } from '../features/auth/authModel';
import { authNoticeReducer, createAuthNotices } from '../features/auth/authNotices';
import { useAuthSession } from '../features/auth/useAuthSession';
import { MemoCapture } from '../features/capture/MemoCapture';
import {
  canSubmitMemo,
  LOCAL_DRAFT_STORAGE_FAILED_PROMPT,
  OFFLINE_CAPTURE_PROMPT,
} from '../features/capture/captureAvailability';
import { MemoTagGraph } from '../features/graph/MemoTagGraph';
import { ConnectionStatus } from '../features/health/ConnectionStatus';
import { MemoLibrary } from '../features/memos/MemoLibrary';
import { PwaUpdateManager } from '../features/pwa/PwaUpdateManager';
import { PostponedReview } from '../features/review/PostponedReview';
import { ProposalReview } from '../features/review/ProposalReview';
import {
  confirmReviewDiscard,
  hasUnsavedWorkspaceChanges,
  SOURCE_CHANGE_DISCARDS_REVIEW_MESSAGE,
  UNSAVED_REVIEW_NAVIGATION_MESSAGE,
  UNSAVED_REVIEW_POSTPONE_MESSAGE,
  UNSAVED_WORKSPACE_NAVIGATION_MESSAGE,
} from '../features/review/unsavedReviewGuard';
import { TaskList } from '../features/tasks/TaskList';
import { FeedbackBanner } from '../shared/ui/FeedbackBanner';
import { useMemoWorkspace } from './useMemoWorkspace';
import { hasPendingServerOperation } from './workspaceOperationState';

export function App() {
  const auth = useAuthSession();
  const [notices, dispatchNotice] = useReducer(
    authNoticeReducer,
    window.location.search,
    createAuthNotices,
  );
  const previousUserId = useRef<string | null>(null);

  useEffect(() => {
    const currentUserId = auth.session?.userId ?? null;
    dispatchNotice({
      type: 'SESSION_TRANSITION',
      previousUserId: previousUserId.current,
      currentUserId,
    });
    previousUserId.current = currentUserId;
  }, [auth.session?.userId]);

  useEffect(() => {
    if (!auth.session || (!notices.googleLinked && !notices.redirectError)) return;
    const url = new URL(window.location.href);
    url.searchParams.delete('linked');
    url.searchParams.delete('error');
    if (url.pathname === '/login') url.pathname = '/';
    window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`);
  }, [auth.session, notices.googleLinked, notices.redirectError]);

  function clearAuthNotices() {
    auth.clearError();
    dispatchNotice({ type: 'CLEAR' });
    const url = new URL(window.location.href);
    url.searchParams.delete('linked');
    url.searchParams.delete('error');
    window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`);
  }

  async function login(input: Pick<LocalAuthInput, 'email' | 'password'>) {
    clearAuthNotices();
    await auth.login(input);
  }

  async function register(input: LocalAuthInput) {
    clearAuthNotices();
    await auth.register(input);
  }

  function logout() {
    clearAuthNotices();
    auth.logout();
  }

  function linkGoogle() {
    clearAuthNotices();
    auth.linkGoogle();
  }

  function unlinkGoogle() {
    clearAuthNotices();
    auth.unlinkGoogle();
  }

  const logoutPending = auth.status === 'LOGOUT_PENDING';
  const canRetainWorkspace = auth.session !== null &&
    (auth.status === 'AUTHENTICATED' || logoutPending);

  if (!canRetainWorkspace || !auth.session) {
    return (
      <>
        <PwaUpdateManager
          hasUnsavedChanges={false}
          operationPending={auth.pending !== null}
        />
        <AuthScreen
          capabilities={auth.capabilities}
          connection={auth.connection}
          pending={auth.pending}
          logoutPending={logoutPending}
          error={auth.error}
          redirectError={notices.redirectError}
          onLogin={login}
          onRegister={register}
          onRetry={auth.retryBootstrap}
          onClearError={clearAuthNotices}
        />
      </>
    );
  }

  const workspaceAccount: WorkspaceAccountProps = {
    session: auth.session,
    capabilities: auth.capabilities,
    pending: auth.pending,
    error: auth.error ?? notices.redirectError,
    googleLinked: notices.googleLinked,
    onLinkGoogle: linkGoogle,
    onUnlinkGoogle: unlinkGoogle,
    onLogout: logout,
    onClearError: clearAuthNotices,
  };

  return (
    <>
      <div hidden={logoutPending} aria-hidden={logoutPending}>
        <WorkspaceApp key={auth.session.userId} account={workspaceAccount} />
      </div>
      {logoutPending && (
        <AuthScreen
          capabilities={auth.capabilities}
          connection={auth.connection}
          pending={auth.pending}
          logoutPending
          error={auth.error}
          redirectError={notices.redirectError}
          onLogin={login}
          onRegister={register}
          onRetry={auth.retryBootstrap}
          onClearError={clearAuthNotices}
        />
      )}
    </>
  );
}

type WorkspaceAccountProps = ComponentProps<typeof AccountPanel>;

function WorkspaceApp({ account }: { account: WorkspaceAccountProps }) {
  const workspace = useMemoWorkspace(account.session.userId);
  const [memoEditDirty, setMemoEditDirty] = useState(false);
  const [transientReviewDirty, setTransientReviewDirty] = useState(false);
  const [pwaUpdating, setPwaUpdating] = useState(false);
  const [navigationApproved, setNavigationApproved] = useState(false);
  const hasUnsavedProposal = workspace.hasUnsavedReview || transientReviewDirty;
  const hasUnsavedChanges = hasUnsavedWorkspaceChanges({
    reviewEdited: workspace.hasUnsavedReview,
    transientReviewInput: transientReviewDirty,
    memoEdit: memoEditDirty,
    unpersistedCapture: workspace.hasUnpersistedCapture,
  });
  const serverOperationPending = hasPendingServerOperation({
    workspaceBusy: workspace.busy,
    pendingTaskId: workspace.pendingTaskId,
    authOperation: account.pending,
  });
  const interactionLocked = serverOperationPending || pwaUpdating;

  useEffect(() => {
    if (account.pending === null) setNavigationApproved(false);
  }, [account.pending]);

  useEffect(() => {
    if (!hasUnsavedChanges || navigationApproved) return;
    const warnBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warnBeforeUnload);
    return () => window.removeEventListener('beforeunload', warnBeforeUnload);
  }, [hasUnsavedChanges, navigationApproved]);

  function confirmWorkspaceDeparture(): boolean {
    return confirmReviewDiscard(
      hasUnsavedChanges,
      hasUnsavedProposal && !memoEditDirty && !workspace.hasUnpersistedCapture
        ? UNSAVED_REVIEW_NAVIGATION_MESSAGE
        : UNSAVED_WORKSPACE_NAVIGATION_MESSAGE,
    );
  }

  function confirmSourceChange(): boolean {
    return confirmReviewDiscard(
      hasUnsavedProposal,
      SOURCE_CHANGE_DISCARDS_REVIEW_MESSAGE,
    );
  }

  const guardedAccount: WorkspaceAccountProps = {
    ...account,
    interactionDisabled: pwaUpdating,
    onLinkGoogle: () => {
      if (!confirmWorkspaceDeparture()) return;
      setNavigationApproved(true);
      account.onLinkGoogle();
    },
    onLogout: () => {
      if (confirmWorkspaceDeparture()) account.onLogout();
    },
  };

  const capturePrompt = workspace.hasUnpersistedCapture
    ? LOCAL_DRAFT_STORAGE_FAILED_PROMPT
    : workspace.connection === 'offline'
    ? OFFLINE_CAPTURE_PROMPT
    : workspace.recoveryLoading
    ? '서버에 저장된 검토 상태를 복원하고 있습니다.'
    : workspace.recoveryError
      ? '원문은 지금 저장할 수 있습니다. 제안 분석은 검토 상태를 복구한 뒤 시작합니다.'
      : workspace.review
        ? '현재 제안을 먼저 승인·보류·거절해 주세요.'
        : workspace.postponedReview
          ? '보류한 제안을 먼저 검토해 주세요.'
          : '메모 원문은 AI 결과와 별도로 먼저 저장됩니다.';

  return (
    <main>
      <PwaUpdateManager
        hasUnsavedChanges={hasUnsavedChanges}
        operationPending={serverOperationPending}
        onUpdatingChange={setPwaUpdating}
      />
      <header className="hero">
        <div>
          <span className="eyebrow">PERSONAL MEMO</span>
          <h1>생각을 먼저 적으세요.</h1>
          <p>분석은 제안만 만듭니다. 수정하고 승인한 내용만 실제 항목이 됩니다.</p>
        </div>
        <div className="hero-actions">
          <ConnectionStatus status={workspace.connection} onRetry={workspace.checkConnection} />
          <AccountPanel {...guardedAccount} />
        </div>
      </header>

      <FeedbackBanner
        feedback={workspace.feedback}
        retryLabel={workspace.retryAction?.label}
        onRetry={workspace.retryAction
          ? () => {
              const scope = workspace.retryAction?.scope ?? '';
              const mayDiscardReview =
                scope.startsWith('update:') ||
                scope.startsWith('trash:') ||
                scope.startsWith('undo:');
              if (!mayDiscardReview || confirmSourceChange()) workspace.retry();
            }
          : undefined}
        onDismiss={workspace.dismissFeedback}
      />

      {workspace.recoveryLoading && (
        <p className="recovery-state" role="status" aria-live="polite">
          마지막 적용과 검토 중·보류한 제안을 불러오는 중입니다…
        </p>
      )}

      {workspace.recoveryError && (
        <aside className="recovery-state recovery-state--error" role="alert">
          <p>저장된 검토 상태를 불러오지 못했습니다. 원문 저장은 가능하지만 제안 분석은 잠시 보류됩니다.</p>
          <button type="button" className="secondary-button" onClick={workspace.refreshRecovery}>
            검토 상태 다시 불러오기
          </button>
        </aside>
      )}

      <MemoLibrary
        activeMemos={workspace.activeMemos}
        trashedMemos={workspace.trashedMemos}
        loading={workspace.memosLoading}
        error={workspace.memosError}
        busy={interactionLocked}
        pendingScope={workspace.pendingMemoScope}
        analysisBlocked={
          workspace.recoveryLoading ||
          workspace.recoveryError !== null ||
          workspace.review !== null ||
          workspace.postponedReview !== null
        }
        onRetry={workspace.refreshMemos}
        onUpdate={(memo, content) =>
          confirmSourceChange() ? workspace.updateMemo(memo, content) : Promise.resolve(false)
        }
        onTrash={(memo) => {
          if (confirmSourceChange()) workspace.trashMemo(memo);
        }}
        onRestore={workspace.restoreMemo}
        onAnalyze={workspace.analyzeMemo}
        onDirtyChange={setMemoEditDirty}
      />

      <MemoTagGraph
        projection={workspace.graph}
        loading={workspace.workspaceLoading}
        error={workspace.workspaceError}
        onRetry={workspace.refreshWorkspace}
      />

      <TaskList
        tasks={workspace.tasks}
        loading={workspace.workspaceLoading}
        error={workspace.workspaceError}
        busy={interactionLocked}
        pendingTaskId={workspace.pendingTaskId}
        onRetry={workspace.refreshWorkspace}
        onStatusChange={workspace.updateTaskStatus}
      />

      {workspace.applicationId && (
        <aside className="undo-card">
          <div>
            <strong>마지막 적용을 되돌릴 수 있습니다.</strong>
            <p>적용으로 생성된 태그 연결과 할 일만 제거하고 원문 메모는 보존합니다.</p>
          </div>
          <button
            type="button"
            className="secondary-button"
            disabled={interactionLocked}
            onClick={() => {
              if (confirmSourceChange()) workspace.undoApplication();
            }}
          >
            {interactionLocked ? '처리 중…' : '마지막 적용 되돌리기'}
          </button>
        </aside>
      )}

      {workspace.postponedReview && (
        <PostponedReview
          title={workspace.postponedReview.title}
          onResume={workspace.resumePostponedReview}
        />
      )}

      {workspace.review && (
        <ProposalReview
          review={workspace.review}
          busy={interactionLocked}
          onChange={workspace.changeReview}
          onApply={workspace.applyCurrentReview}
          onPostpone={() => {
            if (
              confirmReviewDiscard(
                hasUnsavedProposal,
                UNSAVED_REVIEW_POSTPONE_MESSAGE,
              )
            ) workspace.postponeCurrentReview();
          }}
          onReject={workspace.rejectCurrentReview}
          onTransientDirtyChange={setTransientReviewDirty}
        />
      )}

      <MemoCapture
        content={workspace.content}
        disabled={workspace.captureLocked || interactionLocked}
        submissionDisabled={!canSubmitMemo(workspace.connection)}
        submitting={workspace.captureSubmitting}
        rawOnly={workspace.recoveryError !== null}
        prompt={capturePrompt}
        onContentChange={workspace.changeContent}
        onSubmit={workspace.captureMemo}
      />
    </main>
  );
}

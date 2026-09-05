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
import { EventList } from '../features/events/EventList';
import type { CalendarSharingProtection } from '../features/events/calendarSharingModel';
import { ConnectionStatus } from '../features/health/ConnectionStatus';
import { OwnerRemoteAddress } from '../features/home/OwnerRemoteAddress';
import { PwaUpdateManager } from '../features/pwa/PwaUpdateManager';
import { PostponedReview } from '../features/review/PostponedReview';
import { ProposalReview } from '../features/review/ProposalReview';
import { AiProcessingDiagnostic } from '../features/review/AiProcessingDiagnostic';
import { ReviewOutcomeSummary } from '../features/review/ReviewOutcomeSummary';
import { MemoSearch } from '../features/search/MemoSearch';
import { MemoBrowse } from '../features/memos/MemoBrowse';
import type { MemoDetailActionsConfig } from '../features/memos/MemoDetailActions';
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
import { WorkspaceNavigation, type WorkspaceView } from './WorkspaceNavigation';
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

const WORKSPACE_VIEW_TITLE: Record<WorkspaceView, string> = {
  GRAPH: '연결 지도',
  MEMOS: '새 메모',
  AGENDA: '일정',
  SETTINGS: '설정',
};

function WorkspaceApp({ account }: { account: WorkspaceAccountProps }) {
  const [memoEditDirty, setMemoEditDirty] = useState(false);
  const workspace = useMemoWorkspace(account.session.userId, memoEditDirty);
  const [activeView, setActiveView] = useState<WorkspaceView>('GRAPH');
  const [transientReviewDirty, setTransientReviewDirty] = useState(false);
  const [calendarSharingProtection, setCalendarSharingProtection] =
    useState<CalendarSharingProtection>({ pending: false, protectedState: false });
  const [pwaUpdating, setPwaUpdating] = useState(false);
  const [navigationApproved, setNavigationApproved] = useState(false);
  const reviewExitFeedbackRef = useRef<HTMLDivElement>(null);
  const reviewWasOpen = useRef(false);
  const hasUnsavedProposal = workspace.hasUnsavedReview || transientReviewDirty;
  const hasUnsavedChanges = hasUnsavedWorkspaceChanges({
    reviewEdited: workspace.hasUnsavedReview,
    transientReviewInput: transientReviewDirty,
    memoEdit: memoEditDirty,
    unpersistedCapture: workspace.hasUnpersistedCapture,
    calendarSharingProtected: calendarSharingProtection.protectedState,
  });
  const serverOperationPending = hasPendingServerOperation({
    workspaceBusy: workspace.busy,
    pendingTaskId: workspace.pendingTaskId,
    authOperation: account.pending,
    calendarSharingPending: calendarSharingProtection.pending,
  });
  const interactionLocked = serverOperationPending || pwaUpdating;
  const analysisBlocked =
    workspace.recoveryLoading ||
    workspace.recoveryError !== null ||
    workspace.review !== null ||
    workspace.postponedReview !== null;

  useEffect(() => {
    if (workspace.review) {
      reviewWasOpen.current = true;
      return;
    }
    if (!reviewWasOpen.current || interactionLocked) return;

    reviewWasOpen.current = false;
    const frame = window.requestAnimationFrame(() => {
      reviewExitFeedbackRef.current?.focus({ preventScroll: true });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [interactionLocked, workspace.review]);

  useEffect(() => {
    if (account.pending === null) setNavigationApproved(false);
  }, [account.pending]);

  useEffect(() => {
    if (activeView !== 'GRAPH') return;
    const frame = window.requestAnimationFrame(() => {
      window.dispatchEvent(new Event('resize'));
    });
    return () => window.cancelAnimationFrame(frame);
  }, [activeView]);

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

  function confirmMemoEditDiscard(): boolean {
    return confirmReviewDiscard(memoEditDirty, UNSAVED_WORKSPACE_NAVIGATION_MESSAGE);
  }

  const guardedAccount: WorkspaceAccountProps = {
    ...account,
    interactionDisabled: interactionLocked,
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
          : '';
  const retryFeedback = workspace.retryAction
    ? () => {
        const scope = workspace.retryAction?.scope ?? '';
        const mayDiscardReview =
          scope.startsWith('update:') ||
          scope.startsWith('trash:') ||
          scope.startsWith('undo:');
        if (!mayDiscardReview || confirmSourceChange()) workspace.retry();
      }
    : undefined;

  function openCapture() {
    setActiveView('MEMOS');
    window.requestAnimationFrame(() => {
      window.scrollTo({ top: 0, left: 0, behavior: 'auto' });
      document.getElementById('memo-content')?.focus();
    });
  }

  function selectView(view: WorkspaceView) {
    setActiveView(view);
    window.requestAnimationFrame(() => {
      window.scrollTo({ top: 0, left: 0, behavior: 'auto' });
    });
  }

  const failedUpdate = workspace.memoUpdateFailure;
  const memoActions: MemoDetailActionsConfig = {
    busy: interactionLocked,
    editDirty: memoEditDirty,
    pendingScope: workspace.pendingMemoScope,
    analysisBlocked,
    updateFailure: failedUpdate ? {
      ...failedUpdate,
      retry: failedUpdate.retry ? () => confirmSourceChange()
        ? failedUpdate.retry!() : Promise.resolve(false) : undefined,
    } : null,
    onUpdate: (memo: Parameters<typeof workspace.updateMemo>[0], content: string) =>
      confirmSourceChange() ? workspace.updateMemo(memo, content) : Promise.resolve(false),
    onTrash: (memo: Parameters<typeof workspace.trashMemo>[0]) => {
      if (!confirmSourceChange()) return false;
      workspace.closeGraphNode();
      workspace.trashMemo(memo);
      return true;
    },
    onRestore: workspace.restoreMemo,
    onAnalyze: (memo: Parameters<typeof workspace.analyzeMemo>[0]) => {
      workspace.closeGraphNode();
      workspace.analyzeMemo(memo);
      return true;
    },
    onDirtyChange: setMemoEditDirty,
  };

  return (
    <main className="workspace-shell">
      <PwaUpdateManager
        hasUnsavedChanges={hasUnsavedChanges}
        operationPending={serverOperationPending}
        onUpdatingChange={setPwaUpdating}
      />
      <header className="app-header">
        <div className="app-identity">
          <span className="app-identity__mark" aria-hidden="true" />
          <div>
            <span className="app-identity__name">PERSONAL MEMO</span>
            <h1 aria-live="polite" aria-atomic="true">{WORKSPACE_VIEW_TITLE[activeView]}</h1>
          </div>
        </div>
        <ConnectionStatus status={workspace.connection} onRetry={workspace.checkConnection} />
      </header>

      <div
        ref={reviewExitFeedbackRef}
        className="review-exit-feedback"
        tabIndex={-1}
        aria-label="작업 결과"
      >
        <FeedbackBanner
          feedback={workspace.feedback}
          retryLabel={workspace.review ? undefined : workspace.retryAction?.label}
          onRetry={workspace.review ? undefined : retryFeedback}
          onDismiss={workspace.dismissFeedback}
        />
      </div>

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

      {workspace.postponedReview && (
        <div id="review-pending">
          <PostponedReview
            title={workspace.postponedReview.title}
            onResume={workspace.resumePostponedReview}
          />
        </div>
      )}

      <section
        className="workspace-view workspace-view--graph"
        aria-label="연결 지도 화면"
        hidden={activeView !== 'GRAPH'}
      >
        <MemoTagGraph
          projection={workspace.graph}
          loading={workspace.workspaceLoading}
          error={workspace.workspaceError}
          selectedNode={workspace.selectedGraphNode}
          selectionProjectionVersion={workspace.selectedGraphProjectionVersion}
          activeMemoNode={workspace.activeGraphMemoNode}
          memoDetail={workspace.selectedGraphMemo}
          detailLoading={workspace.graphDetailLoading}
          detailError={workspace.graphDetailError}
          neighborhood={workspace.graphNeighborhood}
          neighborhoodLoading={workspace.graphNeighborhoodLoading}
          neighborhoodLoadingMore={workspace.graphNeighborhoodLoadingMore}
          neighborhoodError={workspace.graphNeighborhoodError}
          neighborhoodRestartRequired={workspace.graphNeighborhoodRestartRequired}
          pinPending={workspace.pinPending}
          pinError={workspace.graphPinError}
          interactionDisabled={interactionLocked}
          onRetry={workspace.refreshWorkspace}
          onSelectNode={workspace.selectGraphNode}
          onCloseDetail={() => {
            if (confirmMemoEditDiscard()) workspace.closeGraphNode();
          }}
          onRetryDetail={workspace.retryGraphNodeDetail}
          onRetryNeighborhood={workspace.retryGraphNeighborhood}
          onLoadMoreNeighborhood={workspace.loadMoreGraphNeighborhood}
          onOpenNeighborhoodMemo={(node) => {
            if (confirmMemoEditDiscard()) workspace.openGraphNeighborhoodMemo(node);
          }}
          onBackToNeighborhood={() => {
            if (confirmMemoEditDiscard()) workspace.backToGraphNeighborhood();
          }}
          onSetPinned={(memoId, pinned) => {
            if (!memoEditDirty && !interactionLocked) workspace.setMemoPinned(memoId, pinned);
          }}
          onRetryPin={workspace.retryGraphPin}
          memoActions={{ ...memoActions, onReload: workspace.reloadGraphMemoDetail }}
        />
        <details className="graph-search-disclosure">
          <summary>메모 찾기</summary>
          <MemoSearch
            memoActions={memoActions}
            canCloseDetail={confirmMemoEditDiscard}
          />
          <MemoBrowse
            activeMemos={workspace.activeMemos}
            trashedMemos={workspace.trashedMemos}
            loading={workspace.memosLoading}
            error={workspace.memosError}
            onRetry={workspace.refreshMemos}
            memoActions={memoActions}
            canCloseDetail={confirmMemoEditDiscard}
          />
        </details>
        <button
          type="button"
          className="capture-fab"
          disabled={interactionLocked}
          onClick={openCapture}
        >
          <span aria-hidden="true">＋</span>
          새 메모
        </button>
      </section>

      <section
        className="workspace-view workspace-view--memos"
        aria-label="메모 화면"
        hidden={activeView !== 'MEMOS'}
      >
        <section className="workspace-compose" aria-labelledby="workspace-compose-title">
          <h2 id="workspace-compose-title" className="visually-hidden">새 메모</h2>
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
        </section>
      </section>

      <section
        className="workspace-view workspace-view--agenda"
        aria-label="일정 화면"
        hidden={activeView !== 'AGENDA'}
      >
        <TaskList
          tasks={workspace.tasks}
          loading={workspace.workspaceLoading}
          error={workspace.workspaceError}
          busy={interactionLocked}
          pendingTaskId={workspace.pendingTaskId}
          onRetry={workspace.refreshWorkspace}
          onStatusChange={workspace.updateTaskStatus}
        />

        <EventList
          events={workspace.events}
          loading={workspace.eventsLoading}
          error={workspace.eventsError}
          interactionDisabled={interactionLocked}
          online={workspace.connection === 'online'}
          onCalendarSharingProtectionChange={setCalendarSharingProtection}
          onRetry={workspace.refreshEvents}
        />
      </section>

      <section
        className="workspace-view workspace-view--settings"
        aria-label="설정 화면"
        hidden={activeView !== 'SETTINGS'}
      >
        <div className="settings-account-card">
          <OwnerRemoteAddress />
          <AccountPanel {...guardedAccount} />
        </div>

        <ReviewOutcomeSummary
          summary={workspace.reviewOutcomeSummary}
          loading={workspace.reviewOutcomeLoading}
          error={workspace.reviewOutcomeError}
          onRetry={workspace.refreshReviewOutcomes}
        />

        <AiProcessingDiagnostic
          summary={workspace.analysisPathEvidenceSummary}
          loading={workspace.analysisPathEvidenceLoading}
          error={workspace.analysisPathEvidenceError}
          onLoad={workspace.refreshAnalysisPathEvidence}
          onRefresh={workspace.refreshAnalysisPathEvidence}
        />

        {workspace.applicationId && (
          <aside className="undo-card">
            <div>
              <strong>마지막 정리 되돌리기</strong>
              <p>메모는 남기고, 마지막으로 추가한 연결과 항목만 되돌립니다.</p>
            </div>
            <button
              type="button"
              className="secondary-button"
              disabled={interactionLocked}
              onClick={() => {
                if (confirmSourceChange()) workspace.undoApplication();
              }}
            >
              {interactionLocked ? '처리 중…' : '되돌리기'}
            </button>
          </aside>
        )}
      </section>

      {workspace.review && (
        <div id="review-pending">
          <ProposalReview
            key={workspace.review.proposalId}
            review={workspace.review}
            sourceTimeZone={workspace.reviewSourceTimeZone}
            clientRecordedAt={workspace.reviewClientRecordedAt}
            sourceTimeZoneError={workspace.reviewSourceTimeZoneError}
            relationReviewCandidates={workspace.relationReviewCandidates}
            relationReviewLoading={workspace.relationReviewLoading}
            relationReviewError={workspace.relationReviewError}
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
            onRetryRelationReview={workspace.retryRelationReviewCandidates}
            onRetrySourceTimeZone={workspace.retryReviewSourceTimeZone}
            onTransientDirtyChange={setTransientReviewDirty}
            feedback={workspace.feedback?.kind === 'error' ? workspace.feedback : null}
            retryScope={workspace.retryAction?.scope}
            retryLabel={workspace.retryAction?.label}
            onRetry={retryFeedback}
            onDismissFeedback={workspace.dismissFeedback}
          />
        </div>
      )}

      <WorkspaceNavigation activeView={activeView} onSelect={selectView} />
    </main>
  );
}

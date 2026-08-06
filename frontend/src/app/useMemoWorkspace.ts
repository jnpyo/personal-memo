import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, errorMessage } from '../shared/api/errors';
import { api } from '../shared/api/client';
import {
  createCaptureAttempt,
  RetryIdentityStore,
  type CaptureAttempt,
} from '../shared/api/retryIdentity';
import type { GraphProjection, MemoView, Task, TaskStatus } from '../shared/api/types';
import type { Feedback } from '../shared/ui/FeedbackBanner';
import {
  canSubmitMemo,
  type ConnectionState,
} from '../features/capture/captureAvailability';
import { rawMemoDraftStore } from '../features/capture/rawMemoDraftStore';
import { buildUpdateMemoRequest } from '../features/memos/memoModel';
import { buildApplyRequest, createReviewDraft, type ReviewDraft } from '../features/review/reviewModel';
import {
  deriveCapturePolicy,
  deriveRecoveryState,
  type CapturePolicy,
} from '../features/review/recoveryModel';

const EMPTY_GRAPH: GraphProjection = {
  nodes: [],
  edges: [],
  truncated: false,
  projectionVersion: 'empty',
};

type RetryAction = {
  scope: string;
  label: string;
  run: () => void;
};

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Seoul';
}

export function useMemoWorkspace(ownerId: string) {
  const [connection, setConnection] = useState<ConnectionState>('checking');
  const [content, setContent] = useState(() => rawMemoDraftStore.read(ownerId));
  const [draftPersistenceFailed, setDraftPersistenceFailed] = useState(false);
  const [review, setReview] = useState<ReviewDraft | null>(null);
  const [hasUnsavedReview, setHasUnsavedReview] = useState(false);
  const [postponedReview, setPostponedReview] = useState<ReviewDraft | null>(null);
  const [applicationId, setApplicationId] = useState<string | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [graph, setGraph] = useState<GraphProjection>(EMPTY_GRAPH);
  const [activeMemos, setActiveMemos] = useState<MemoView[]>([]);
  const [trashedMemos, setTrashedMemos] = useState<MemoView[]>([]);
  const [workspaceLoading, setWorkspaceLoading] = useState(true);
  const [workspaceError, setWorkspaceError] = useState<string | null>(null);
  const [recoveryLoading, setRecoveryLoading] = useState(true);
  const [recoveryError, setRecoveryError] = useState<string | null>(null);
  const [memosLoading, setMemosLoading] = useState(true);
  const [memosError, setMemosError] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [pendingTaskId, setPendingTaskId] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [retryAction, setRetryAction] = useState<RetryAction | null>(null);

  const captureAttempt = useRef<CaptureAttempt | null>(null);
  const retryIdentities = useRef(new RetryIdentityStore());
  const timeZone = useRef(browserTimeZone());
  const capturePolicy = deriveCapturePolicy(recoveryLoading, recoveryError);

  const checkConnection = useCallback(async () => {
    setConnection('checking');
    try {
      await api.health();
      setConnection('online');
    } catch {
      setConnection('offline');
    }
  }, []);

  const refreshWorkspace = useCallback(async () => {
    setWorkspaceLoading(true);
    setWorkspaceError(null);
    try {
      const [nextTasks, nextGraph] = await Promise.all([api.tasks(), api.graph(100)]);
      setTasks(nextTasks);
      setGraph(nextGraph);
    } catch (error) {
      setWorkspaceError(errorMessage(error));
    } finally {
      setWorkspaceLoading(false);
    }
  }, []);

  const refreshMemos = useCallback(async () => {
    setMemosLoading(true);
    setMemosError(null);
    try {
      const [active, trashed] = await Promise.all([
        api.memos('ACTIVE', 50),
        api.memos('TRASHED', 50),
      ]);
      setActiveMemos(active);
      setTrashedMemos(trashed);
    } catch (error) {
      setMemosError(errorMessage(error));
    } finally {
      setMemosLoading(false);
    }
  }, []);

  const refreshRecovery = useCallback(async () => {
    setRecoveryLoading(true);
    setRecoveryError(null);
    setApplicationId(null);
    setReview(null);
    setHasUnsavedReview(false);
    setPostponedReview(null);
    try {
      const [latestApplication, reviewRequiredProposals, postponedProposals] = await Promise.all([
        api.latestApplication(),
        api.proposals('REVIEW_REQUIRED', 1),
        api.proposals('POSTPONED', 1),
      ]);
      const recovered = deriveRecoveryState(latestApplication, [
        ...reviewRequiredProposals,
        ...postponedProposals,
      ]);
      setApplicationId(recovered.applicationId);
      setReview(recovered.review);
      setHasUnsavedReview(false);
      setPostponedReview(recovered.postponedReview);
    } catch (error) {
      setRecoveryError(errorMessage(error));
    } finally {
      setRecoveryLoading(false);
    }
  }, []);

  useEffect(() => {
    void checkConnection();
    void refreshWorkspace();
    void refreshMemos();
    void refreshRecovery();
  }, [checkConnection, refreshMemos, refreshRecovery, refreshWorkspace]);

  useEffect(() => {
    const handleOffline = () => setConnection('offline');
    const handleOnline = () => void checkConnection();
    window.addEventListener('offline', handleOffline);
    window.addEventListener('online', handleOnline);
    return () => {
      window.removeEventListener('offline', handleOffline);
      window.removeEventListener('online', handleOnline);
    };
  }, [checkConnection]);

  function clearRetry(scope?: string) {
    setRetryAction((current) => (!scope || current?.scope === scope ? null : current));
  }

  function fail(error: unknown, scope: string, label: string, retry: () => void) {
    if (error instanceof TypeError) setConnection('offline');
    setFeedback({ kind: 'error', message: errorMessage(error) });
    setRetryAction({ scope, label, run: retry });
  }

  async function runCapture(attempt: CaptureAttempt, policy: CapturePolicy) {
    const scope = 'capture';
    setBusyAction(scope);
    clearRetry(scope);
    setFeedback({
      kind: 'info',
      message:
        policy === 'RAW_ONLY'
          ? '원문을 먼저 안전하게 저장하고 있습니다.'
          : '원문을 저장하고 규칙 기반 분석 후보를 만들고 있습니다.',
    });

    try {
      const memo = await api.createMemo({
        id: attempt.memoId,
        content: attempt.content,
        clientCreatedAt: attempt.clientCreatedAt,
        timeZone: timeZone.current,
        idempotencyKey: attempt.createKey,
      });
      rawMemoDraftStore.clear(ownerId);
      setDraftPersistenceFailed(false);
      await refreshMemos();
      if (policy === 'RAW_ONLY') {
        setContent('');
        captureAttempt.current = null;
        clearRetry(scope);
        setFeedback({
          kind: 'success',
          message: '원문을 저장했습니다. 검토 상태를 복구한 뒤 이 메모에서 제안 분석을 시작할 수 있습니다.',
        });
        return;
      }
      const run = await api.analyze(memo.id, memo.currentRevision, attempt.analysisKey);
      const proposal = await api.proposal(run.proposalId, {
        memoId: run.memoId,
        memoRevision: run.memoRevision,
      });

      setReview(createReviewDraft(run.proposalId, proposal));
      setHasUnsavedReview(false);
      setPostponedReview(null);
      setFeedback({ kind: 'success', message: '원문은 보존되었습니다. 제안을 수정하거나 승인해 주세요.' });
      captureAttempt.current = null;
      clearRetry(scope);
      await refreshMemos();
    } catch (error) {
      fail(error, scope, '저장 다시 시도', () => void runCapture(attempt, policy));
    } finally {
      setBusyAction(null);
    }
  }

  function captureMemo(nextContent: string) {
    if (!canSubmitMemo(connection)) {
      setFeedback({
        kind: 'info',
        message: draftPersistenceFailed
          ? '브라우저 저장소에 임시 초안을 보존하지 못했습니다. 이 화면을 닫지 말고 서버에 다시 연결한 뒤 제출해 주세요.'
          : '입력은 이 계정 전용 임시 초안으로 보존했습니다. 서버에 다시 연결한 뒤 제출해 주세요.',
      });
      return;
    }
    if (capturePolicy === 'LOCKED') {
      setFeedback({ kind: 'info', message: '서버의 검토 상태를 먼저 복원해 주세요.' });
      return;
    }
    const current = captureAttempt.current;
    const attempt = current?.content === nextContent ? current : createCaptureAttempt(nextContent);
    captureAttempt.current = attempt;
    void runCapture(attempt, capturePolicy);
  }

  function changeContent(nextContent: string) {
    setContent(nextContent);
    const persisted = rawMemoDraftStore.save(ownerId, nextContent);
    setDraftPersistenceFailed(nextContent.length > 0 && !persisted);
    if (captureAttempt.current && captureAttempt.current.content !== nextContent) {
      captureAttempt.current = null;
      clearRetry('capture');
    }
  }

  function changeReview(nextReview: ReviewDraft) {
    setReview(nextReview);
    setHasUnsavedReview(true);
    clearRetry(`apply:${nextReview.proposalId}`);
  }

  async function applyReview(snapshot: ReviewDraft) {
    const scope = `apply:${snapshot.proposalId}`;
    const body = buildApplyRequest(snapshot, timeZone.current);
    const fingerprint = JSON.stringify(body);
    const idempotencyKey = retryIdentities.current.keyFor(scope, fingerprint);

    setBusyAction(scope);
    clearRetry(scope);
    setFeedback({ kind: 'info', message: '승인한 항목을 적용하고 있습니다.' });

    try {
      await api.apply(snapshot.proposalId, body, idempotencyKey);
      setReview(null);
      setHasUnsavedReview(false);
      setContent('');
      clearRetry(scope);
      setFeedback({ kind: 'success', message: '승인한 태그와 할 일을 생성했습니다.' });
      await Promise.all([refreshWorkspace(), refreshMemos(), refreshRecovery()]);
      retryIdentities.current.clear(scope);
    } catch (error) {
      fail(error, scope, '승인 다시 시도', () => void applyReview(snapshot));
    } finally {
      setBusyAction(null);
    }
  }

  function applyCurrentReview() {
    if (review) void applyReview(review);
  }

  async function postponeCurrentReview() {
    if (!review) return;
    const snapshot = review;
    const scope = `postpone:${snapshot.proposalId}`;
    const idempotencyKey = retryIdentities.current.keyFor(scope, snapshot.proposalId);
    setBusyAction(scope);
    clearRetry(scope);

    try {
      await api.postponeProposal(snapshot.proposalId, idempotencyKey);
      setReview(null);
      setHasUnsavedReview(false);
      setFeedback({
        kind: 'info',
        message: '제안을 보류했습니다. 승인 전이므로 생성된 항목은 없습니다.',
      });
      await Promise.all([refreshMemos(), refreshRecovery()]);
      retryIdentities.current.clear(scope);
    } catch (error) {
      fail(error, scope, '보류 다시 시도', () => void postponeCurrentReview());
    } finally {
      setBusyAction(null);
    }
  }

  async function rejectCurrentReview() {
    if (!review) return;
    const snapshot = review;
    const scope = `reject:${snapshot.proposalId}`;
    const idempotencyKey = retryIdentities.current.keyFor(scope, snapshot.proposalId);
    setBusyAction(scope);
    clearRetry(scope);

    try {
      await api.rejectProposal(snapshot.proposalId, idempotencyKey);
      setReview(null);
      setHasUnsavedReview(false);
      setFeedback({
        kind: 'info',
        message: '제안을 거절했습니다. 원본 메모는 그대로 보존됩니다.',
      });
      await Promise.all([refreshMemos(), refreshRecovery()]);
      retryIdentities.current.clear(scope);
    } catch (error) {
      fail(error, scope, '거절 다시 시도', () => void rejectCurrentReview());
    } finally {
      setBusyAction(null);
    }
  }

  function resumePostponedReview() {
    if (!postponedReview) return;
    setReview(postponedReview);
    setHasUnsavedReview(false);
    setPostponedReview(null);
    setFeedback({ kind: 'info', message: '보류한 제안을 다시 열었습니다.' });
  }

  async function runUpdateMemo(memo: MemoView, body: ReturnType<typeof buildUpdateMemoRequest>) {
    const scope = `update:${memo.id}`;
    const idempotencyKey = retryIdentities.current.keyFor(scope, JSON.stringify(body));
    setBusyAction(scope);
    clearRetry(scope);
    setFeedback({ kind: 'info', message: '원문을 새 revision으로 저장하고 있습니다.' });

    try {
      await api.updateMemo(memo.id, body, idempotencyKey);
      clearRetry(scope);
      setFeedback({
        kind: 'success',
        message: `revision ${memo.currentRevision + 1}를 저장했습니다. 이전 분석 결과는 자동으로 오래된 제안이 됩니다.`,
      });
      await Promise.all([refreshMemos(), refreshRecovery()]);
      retryIdentities.current.clear(scope);
      return true;
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        retryIdentities.current.clear(scope);
        setFeedback({ kind: 'error', message: errorMessage(error) });
        setRetryAction({
          scope,
          label: '최신 메모 불러오기',
          run: () => void refreshMemos(),
        });
      } else {
        fail(error, scope, '원문 저장 다시 시도', () => void runUpdateMemo(memo, body));
      }
      return false;
    } finally {
      setBusyAction(null);
    }
  }

  function updateMemo(memo: MemoView, nextContent: string): Promise<boolean> {
    const body = buildUpdateMemoRequest(
      memo,
      nextContent,
      new Date().toISOString(),
      timeZone.current,
    );
    return runUpdateMemo(memo, body);
  }

  async function trashMemo(memo: MemoView) {
    const scope = `trash:${memo.id}`;
    const fingerprint = `${memo.id}:${memo.currentRevision}:${memo.status}`;
    const idempotencyKey = retryIdentities.current.keyFor(scope, fingerprint);
    setBusyAction(scope);
    clearRetry(scope);

    try {
      await api.trashMemo(memo.id, idempotencyKey);
      clearRetry(scope);
      setFeedback({
        kind: 'success',
        message: '메모를 휴지통으로 옮겼습니다. 원문과 revision 기록은 삭제하지 않았습니다.',
      });
      await Promise.all([refreshMemos(), refreshWorkspace(), refreshRecovery()]);
      retryIdentities.current.clear(scope);
    } catch (error) {
      fail(error, scope, '휴지통 이동 다시 시도', () => void trashMemo(memo));
    } finally {
      setBusyAction(null);
    }
  }

  async function restoreMemo(memo: MemoView) {
    const scope = `restore:${memo.id}`;
    const fingerprint = `${memo.id}:${memo.currentRevision}:${memo.status}`;
    const idempotencyKey = retryIdentities.current.keyFor(scope, fingerprint);
    setBusyAction(scope);
    clearRetry(scope);

    try {
      await api.restoreMemo(memo.id, idempotencyKey);
      clearRetry(scope);
      setFeedback({ kind: 'success', message: '원본 메모를 활성 목록으로 복원했습니다.' });
      await Promise.all([refreshMemos(), refreshWorkspace()]);
      retryIdentities.current.clear(scope);
    } catch (error) {
      fail(error, scope, '복원 다시 시도', () => void restoreMemo(memo));
    } finally {
      setBusyAction(null);
    }
  }

  async function analyzeMemo(memo: MemoView) {
    if (recoveryLoading || recoveryError) {
      setFeedback({ kind: 'info', message: '서버의 검토 상태를 먼저 복원해 주세요.' });
      return;
    }
    if (review || postponedReview) {
      setFeedback({ kind: 'info', message: '열려 있는 제안을 먼저 승인·보류·거절해 주세요.' });
      return;
    }

    const scope = `analyze:${memo.id}`;
    const fingerprint = `${memo.id}:${memo.currentRevision}`;
    const idempotencyKey = retryIdentities.current.keyFor(scope, fingerprint);
    setBusyAction(scope);
    clearRetry(scope);
    setFeedback({ kind: 'info', message: `revision ${memo.currentRevision}의 분석 제안을 만들고 있습니다.` });

    try {
      const run = await api.analyze(memo.id, memo.currentRevision, idempotencyKey);
      const proposal = await api.proposal(run.proposalId, {
        memoId: run.memoId,
        memoRevision: run.memoRevision,
      });
      setReview(createReviewDraft(run.proposalId, proposal));
      setHasUnsavedReview(false);
      setPostponedReview(null);
      clearRetry(scope);
      setFeedback({
        kind: 'success',
        message: '최신 원문은 그대로 두고 별도의 분석 제안을 열었습니다.',
      });
      await refreshMemos();
      retryIdentities.current.clear(scope);
    } catch (error) {
      fail(error, scope, '제안 분석 다시 시도', () => void analyzeMemo(memo));
    } finally {
      setBusyAction(null);
    }
  }

  async function undoApplication() {
    if (!applicationId) return;
    const currentApplicationId = applicationId;
    const scope = `undo:${currentApplicationId}`;
    const idempotencyKey = retryIdentities.current.keyFor(scope, currentApplicationId);
    setBusyAction(scope);
    clearRetry(scope);

    try {
      await api.undo(currentApplicationId, idempotencyKey);
      setFeedback({
        kind: 'success',
        message: '마지막 적용을 되돌렸습니다. 원본 메모는 삭제하지 않았습니다.',
      });
      await Promise.all([refreshWorkspace(), refreshMemos(), refreshRecovery()]);
      retryIdentities.current.clear(scope);
    } catch (error) {
      fail(error, scope, '되돌리기 다시 시도', () => void undoApplication());
    } finally {
      setBusyAction(null);
    }
  }

  async function updateTaskStatus(task: Task, status: TaskStatus) {
    if (task.status === status) return;
    const scope = `task:${task.id}`;
    const fingerprint = `${task.id}:${status}`;
    const idempotencyKey = retryIdentities.current.keyFor(scope, fingerprint);
    setPendingTaskId(task.id);
    clearRetry(scope);

    try {
      await api.updateTask(task.id, status, idempotencyKey);
      setFeedback({ kind: 'success', message: `“${task.title}” 상태를 변경했습니다.` });
      await refreshWorkspace();
      retryIdentities.current.clear(scope);
    } catch (error) {
      fail(error, scope, '상태 변경 다시 시도', () => void updateTaskStatus(task, status));
    } finally {
      setPendingTaskId(null);
    }
  }

  function dismissFeedback() {
    setFeedback(null);
    setRetryAction(null);
  }

  return {
    connection,
    content,
    hasUnpersistedCapture: content.length > 0 && draftPersistenceFailed,
    review,
    hasUnsavedReview,
    postponedReview,
    applicationId,
    tasks,
    graph,
    activeMemos,
    trashedMemos,
    workspaceLoading,
    workspaceError,
    recoveryLoading,
    recoveryError,
    memosLoading,
    memosError,
    pendingMemoScope:
      busyAction?.startsWith('update:') ||
      busyAction?.startsWith('trash:') ||
      busyAction?.startsWith('restore:') ||
      busyAction?.startsWith('analyze:')
        ? busyAction
        : null,
    busy: busyAction !== null,
    captureSubmitting: busyAction === 'capture',
    captureLocked:
      busyAction !== null ||
      capturePolicy === 'LOCKED' ||
      review !== null ||
      postponedReview !== null,
    pendingTaskId,
    feedback,
    retryAction,
    checkConnection,
    refreshWorkspace,
    refreshMemos,
    refreshRecovery,
    changeContent,
    captureMemo,
    changeReview,
    applyCurrentReview,
    postponeCurrentReview: () => void postponeCurrentReview(),
    rejectCurrentReview: () => void rejectCurrentReview(),
    resumePostponedReview,
    updateMemo,
    trashMemo: (memo: MemoView) => void trashMemo(memo),
    restoreMemo: (memo: MemoView) => void restoreMemo(memo),
    analyzeMemo: (memo: MemoView) => void analyzeMemo(memo),
    undoApplication: () => void undoApplication(),
    updateTaskStatus: (task: Task, status: TaskStatus) => void updateTaskStatus(task, status),
    retry: () => retryAction?.run(),
    dismissFeedback,
  };
}

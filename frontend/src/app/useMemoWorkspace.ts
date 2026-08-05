import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, errorMessage } from '../shared/api/errors';
import { api } from '../shared/api/client';
import {
  createCaptureAttempt,
  RetryIdentityStore,
  type CaptureAttempt,
} from '../shared/api/retryIdentity';
import type { GraphProjection, Task, TaskStatus } from '../shared/api/types';
import type { Feedback } from '../shared/ui/FeedbackBanner';
import { buildApplyRequest, createReviewDraft, type ReviewDraft } from '../features/review/reviewModel';

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

export function useMemoWorkspace() {
  const [connection, setConnection] = useState<'checking' | 'online' | 'offline'>('checking');
  const [content, setContent] = useState('');
  const [review, setReview] = useState<ReviewDraft | null>(null);
  const [postponedReview, setPostponedReview] = useState<ReviewDraft | null>(null);
  const [applicationId, setApplicationId] = useState<string | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [graph, setGraph] = useState<GraphProjection>(EMPTY_GRAPH);
  const [workspaceLoading, setWorkspaceLoading] = useState(true);
  const [workspaceError, setWorkspaceError] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [pendingTaskId, setPendingTaskId] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [retryAction, setRetryAction] = useState<RetryAction | null>(null);

  const captureAttempt = useRef<CaptureAttempt | null>(null);
  const retryIdentities = useRef(new RetryIdentityStore());
  const timeZone = useRef(browserTimeZone());

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

  useEffect(() => {
    void checkConnection();
    void refreshWorkspace();
  }, [checkConnection, refreshWorkspace]);

  function clearRetry(scope?: string) {
    setRetryAction((current) => (!scope || current?.scope === scope ? null : current));
  }

  function fail(error: unknown, scope: string, label: string, retry: () => void) {
    setFeedback({ kind: 'error', message: errorMessage(error) });
    setRetryAction({ scope, label, run: retry });
  }

  async function runCapture(attempt: CaptureAttempt) {
    const scope = 'capture';
    setBusyAction(scope);
    clearRetry(scope);
    setFeedback({ kind: 'info', message: '원문을 저장하고 Fake 분석 후보를 만들고 있습니다.' });

    try {
      const memo = await api.createMemo({
        id: attempt.memoId,
        content: attempt.content,
        clientCreatedAt: attempt.clientCreatedAt,
        timeZone: timeZone.current,
        idempotencyKey: attempt.createKey,
      });
      const run = await api.analyze(memo.id, memo.currentRevision, attempt.analysisKey);
      const proposal = await api.proposal(run.proposalId);

      setReview(createReviewDraft(run.proposalId, proposal));
      setPostponedReview(null);
      setFeedback({ kind: 'success', message: '원문은 보존되었습니다. 제안을 수정하거나 승인해 주세요.' });
      captureAttempt.current = null;
      clearRetry(scope);
    } catch (error) {
      fail(error, scope, '저장 및 분석 다시 시도', () => void runCapture(attempt));
    } finally {
      setBusyAction(null);
    }
  }

  function captureMemo(nextContent: string) {
    const current = captureAttempt.current;
    const attempt = current?.content === nextContent ? current : createCaptureAttempt(nextContent);
    captureAttempt.current = attempt;
    void runCapture(attempt);
  }

  function changeContent(nextContent: string) {
    setContent(nextContent);
    if (captureAttempt.current && captureAttempt.current.content !== nextContent.trim()) {
      captureAttempt.current = null;
      clearRetry('capture');
    }
  }

  function changeReview(nextReview: ReviewDraft) {
    setReview(nextReview);
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
      const result = await api.apply(snapshot.proposalId, body, idempotencyKey);
      setApplicationId(result.applicationId);
      setReview(null);
      setContent('');
      retryIdentities.current.clear(scope);
      clearRetry(scope);
      setFeedback({ kind: 'success', message: '승인한 태그와 할 일을 생성했습니다.' });
      await refreshWorkspace();
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
      let localOnly = false;
      try {
        await api.postponeProposal(snapshot.proposalId, idempotencyKey);
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 404) throw error;
        localOnly = true;
      }

      setPostponedReview(snapshot);
      setReview(null);
      retryIdentities.current.clear(scope);
      setFeedback({
        kind: 'info',
        message: localOnly
          ? '제안을 이 화면에서 보류했습니다. 서버 보류 기능은 아직 사용할 수 없습니다.'
          : '제안을 보류했습니다. 승인 전이므로 생성된 항목은 없습니다.',
      });
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
      let localOnly = false;
      try {
        await api.rejectProposal(snapshot.proposalId, idempotencyKey);
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 404) throw error;
        localOnly = true;
      }

      setReview(null);
      retryIdentities.current.clear(scope);
      setFeedback({
        kind: 'info',
        message: localOnly
          ? '제안을 이 화면에서 닫았습니다. 원본 메모는 그대로 보존됩니다.'
          : '제안을 거절했습니다. 원본 메모는 그대로 보존됩니다.',
      });
    } catch (error) {
      fail(error, scope, '거절 다시 시도', () => void rejectCurrentReview());
    } finally {
      setBusyAction(null);
    }
  }

  function resumePostponedReview() {
    if (!postponedReview) return;
    setReview(postponedReview);
    setPostponedReview(null);
    setFeedback({ kind: 'info', message: '보류한 제안을 다시 열었습니다.' });
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
      setApplicationId(null);
      retryIdentities.current.clear(scope);
      setFeedback({
        kind: 'success',
        message: '마지막 적용을 되돌렸습니다. 원본 메모는 삭제하지 않았습니다.',
      });
      await refreshWorkspace();
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
      retryIdentities.current.clear(scope);
      setFeedback({ kind: 'success', message: `“${task.title}” 상태를 변경했습니다.` });
      await refreshWorkspace();
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
    review,
    postponedReview,
    applicationId,
    tasks,
    graph,
    workspaceLoading,
    workspaceError,
    busy: busyAction !== null,
    captureSubmitting: busyAction === 'capture',
    captureLocked: busyAction !== null || review !== null || postponedReview !== null,
    pendingTaskId,
    feedback,
    retryAction,
    checkConnection,
    refreshWorkspace,
    changeContent,
    captureMemo,
    changeReview,
    applyCurrentReview,
    postponeCurrentReview: () => void postponeCurrentReview(),
    rejectCurrentReview: () => void rejectCurrentReview(),
    resumePostponedReview,
    undoApplication: () => void undoApplication(),
    updateTaskStatus: (task: Task, status: TaskStatus) => void updateTaskStatus(task, status),
    retry: () => retryAction?.run(),
    dismissFeedback,
  };
}

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, errorMessage } from '../shared/api/errors';
import { api } from '../shared/api/client';
import {
  createCaptureAttempt,
  RetryIdentityStore,
  type CaptureAttempt,
} from '../shared/api/retryIdentity';
import type {
  AnalysisPathEvidenceSummary,
  AnalysisReviewOutcomeSummary,
  CalendarEvent,
  GraphNode,
  GraphProjection,
  MemoView,
  RelationReviewCandidate,
  Task,
  TaskStatus,
} from '../shared/api/types';
import type { Feedback } from '../shared/ui/FeedbackBanner';
import {
  canSubmitMemo,
  type ConnectionState,
} from '../features/capture/captureAvailability';
import { rawMemoDraftStore } from '../features/capture/rawMemoDraftStore';
import { graphNodeEntityId } from '../features/graph/graphModel';
import {
  GRAPH_NEIGHBORHOOD_PAGE_LIMIT,
  GraphNeighborhoodMergeError,
  graphNeighborhoodRetryRequest,
  mergeGraphNeighborhoodPage,
  reconcileGraphNeighborhoodAfterMemoPin,
  type GraphNeighborhoodCollection,
  type GraphNeighborhoodRetryRequest,
} from '../features/graph/graphNeighborhoodModel';
import { buildUpdateMemoRequest } from '../features/memos/memoModel';
import {
  buildApplyRequest,
  createReviewDraft,
  isRelationSelectionReady,
  type ReviewDraft,
} from '../features/review/reviewModel';
import {
  findBareTimeClarification,
  requiresReviewSourceTimeZone,
} from '../features/review/bareTimeClarificationModel';
import {
  deriveCapturePolicy,
  deriveRecoveryState,
  type CapturePolicy,
} from '../features/review/recoveryModel';
import {
  isCurrentScopedRequest,
  isLatestWorkspaceRequest,
  refreshAfterMemoSourceEdit,
} from './workspaceOperationState';

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

type RelationReviewLoadState = {
  proposalId: string;
  status: 'LOADING' | 'READY' | 'ERROR';
  candidates: RelationReviewCandidate[] | null;
  error: string | null;
};

const TERMINAL_APPLY_CONFLICT_CODES = new Set([
  'STALE_MEMO_REVISION',
  'PROPOSAL_CHANGED',
  'PROPOSAL_NOT_APPLICABLE',
]);

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Seoul';
}

export function useMemoWorkspace(ownerId: string) {
  const [connection, setConnection] = useState<ConnectionState>('checking');
  const [content, setContent] = useState(() => rawMemoDraftStore.read(ownerId));
  const [draftPersistenceFailed, setDraftPersistenceFailed] = useState(false);
  const [review, setReview] = useState<ReviewDraft | null>(null);
  const [reviewSourceTimeZone, setReviewSourceTimeZone] = useState<string | null>(null);
  const [reviewClientRecordedAt, setReviewClientRecordedAt] = useState<string | null>(null);
  const [reviewSourceTimeZoneError, setReviewSourceTimeZoneError] =
    useState<string | null>(null);
  const [reviewSourceTimeZoneRetry, setReviewSourceTimeZoneRetry] = useState(0);
  const [hasUnsavedReview, setHasUnsavedReview] = useState(false);
  const [postponedReview, setPostponedReview] = useState<ReviewDraft | null>(null);
  const [applicationId, setApplicationId] = useState<string | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [events, setEvents] = useState<CalendarEvent[]>([]);
  const [graph, setGraph] = useState<GraphProjection>(EMPTY_GRAPH);
  const [selectedGraphNode, setSelectedGraphNode] = useState<GraphNode | null>(null);
  const [selectedGraphProjectionVersion, setSelectedGraphProjectionVersion] =
    useState<string | null>(null);
  const [selectedGraphMemo, setSelectedGraphMemo] = useState<MemoView | null>(null);
  const [activeGraphMemoNode, setActiveGraphMemoNode] = useState<GraphNode | null>(null);
  const [graphDetailLoading, setGraphDetailLoading] = useState(false);
  const [graphDetailError, setGraphDetailError] = useState<string | null>(null);
  const [graphNeighborhood, setGraphNeighborhood] =
    useState<GraphNeighborhoodCollection | null>(null);
  const [graphNeighborhoodLoading, setGraphNeighborhoodLoading] = useState(false);
  const [graphNeighborhoodLoadingMore, setGraphNeighborhoodLoadingMore] = useState(false);
  const [graphNeighborhoodError, setGraphNeighborhoodError] = useState<string | null>(null);
  const [graphNeighborhoodRestartRequired, setGraphNeighborhoodRestartRequired] = useState(false);
  const [graphPinError, setGraphPinError] = useState<string | null>(null);
  const [activeMemos, setActiveMemos] = useState<MemoView[]>([]);
  const [trashedMemos, setTrashedMemos] = useState<MemoView[]>([]);
  const [workspaceLoading, setWorkspaceLoading] = useState(true);
  const [workspaceError, setWorkspaceError] = useState<string | null>(null);
  const [recoveryLoading, setRecoveryLoading] = useState(true);
  const [recoveryError, setRecoveryError] = useState<string | null>(null);
  const [memosLoading, setMemosLoading] = useState(true);
  const [memosError, setMemosError] = useState<string | null>(null);
  const [eventsLoading, setEventsLoading] = useState(true);
  const [eventsError, setEventsError] = useState<string | null>(null);
  const [reviewOutcomeSummary, setReviewOutcomeSummary] =
    useState<AnalysisReviewOutcomeSummary | null>(null);
  const [reviewOutcomeLoading, setReviewOutcomeLoading] = useState(true);
  const [reviewOutcomeError, setReviewOutcomeError] = useState<string | null>(null);
  const [analysisPathEvidenceSummary, setAnalysisPathEvidenceSummary] =
    useState<AnalysisPathEvidenceSummary | null>(null);
  const [analysisPathEvidenceLoading, setAnalysisPathEvidenceLoading] = useState(false);
  const [analysisPathEvidenceError, setAnalysisPathEvidenceError] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [pendingTaskId, setPendingTaskId] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const [retryAction, setRetryAction] = useState<RetryAction | null>(null);
  const [relationReviewLoadState, setRelationReviewLoadState] =
    useState<RelationReviewLoadState | null>(null);

  const captureAttempt = useRef<CaptureAttempt | null>(null);
  const retryIdentities = useRef(new RetryIdentityStore());
  const workspaceRequest = useRef(0);
  const eventListRequest = useRef(0);
  const memoListRequest = useRef(0);
  const reviewOutcomeRequest = useRef(0);
  const analysisPathEvidenceRequest = useRef(0);
  const relationReviewRequest = useRef(0);
  const reviewMemoSourceRequest = useRef(0);
  const relationReviewAbort = useRef<AbortController | null>(null);
  const relationReviewLoadStateRef = useRef<RelationReviewLoadState | null>(null);
  const graphDetailRequest = useRef(0);
  const graphDetailAbort = useRef<AbortController | null>(null);
  const graphNeighborhoodRequest = useRef(0);
  const graphNeighborhoodAbort = useRef<AbortController | null>(null);
  const graphNeighborhoodSnapshot = useRef<GraphNeighborhoodCollection | null>(null);
  const graphSelectionIdentity = useRef<string | null>(null);
  const graphSelectionNodeSnapshot = useRef<GraphNode | null>(null);
  const activeGraphMemoIdentity = useRef<string | null>(null);
  const graphNeighborhoodRetry = useRef<GraphNeighborhoodRetryRequest | null>(null);
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

  const refreshEvents = useCallback(async () => {
    const request = ++eventListRequest.current;
    setEventsLoading(true);
    setEventsError(null);
    try {
      const nextEvents = await api.events(100);
      if (isLatestWorkspaceRequest(request, eventListRequest.current)) {
        setEvents(nextEvents);
      }
    } catch (error) {
      if (isLatestWorkspaceRequest(request, eventListRequest.current)) {
        setEventsError(errorMessage(error));
      }
    } finally {
      if (isLatestWorkspaceRequest(request, eventListRequest.current)) {
        setEventsLoading(false);
      }
    }
  }, []);

  const refreshWorkspace = useCallback(async () => {
    const request = ++workspaceRequest.current;
    setWorkspaceLoading(true);
    setWorkspaceError(null);
    void refreshEvents();
    try {
      const [nextTasks, nextGraph] = await Promise.all([api.tasks(), api.graph(100)]);
      if (isLatestWorkspaceRequest(request, workspaceRequest.current)) {
        setTasks(nextTasks);
        setGraph(nextGraph);
      }
    } catch (error) {
      if (isLatestWorkspaceRequest(request, workspaceRequest.current)) {
        setWorkspaceError(errorMessage(error));
      }
    } finally {
      if (isLatestWorkspaceRequest(request, workspaceRequest.current)) {
        setWorkspaceLoading(false);
      }
    }
  }, [refreshEvents]);

  const refreshMemos = useCallback(async () => {
    const request = ++memoListRequest.current;
    setMemosLoading(true);
    setMemosError(null);
    try {
      const [active, trashed] = await Promise.all([
        api.memos('ACTIVE', 50),
        api.memos('TRASHED', 50),
      ]);
      if (isLatestWorkspaceRequest(request, memoListRequest.current)) {
        setActiveMemos(active);
        setTrashedMemos(trashed);
      }
    } catch (error) {
      if (isLatestWorkspaceRequest(request, memoListRequest.current)) {
        setMemosError(errorMessage(error));
      }
    } finally {
      if (isLatestWorkspaceRequest(request, memoListRequest.current)) {
        setMemosLoading(false);
      }
    }
  }, []);

  const loadGraphMemoDetail = useCallback(async (node: GraphNode) => {
    const request = ++graphDetailRequest.current;
    graphDetailAbort.current?.abort();
    const controller = new AbortController();
    graphDetailAbort.current = controller;
    setSelectedGraphMemo(null);
    setGraphDetailLoading(true);
    setGraphDetailError(null);

    try {
      const memo = await api.memo(graphNodeEntityId(node), controller.signal);
      if (graphDetailRequest.current === request && !controller.signal.aborted) {
        setSelectedGraphMemo(memo);
      }
    } catch (error) {
      if (graphDetailRequest.current === request && !controller.signal.aborted) {
        setGraphDetailError(errorMessage(error));
      }
    } finally {
      if (graphDetailRequest.current === request && !controller.signal.aborted) {
        setGraphDetailLoading(false);
      }
    }
  }, []);

  const loadGraphNeighborhood = useCallback(async (
    node: GraphNode,
    cursor: string | null,
    append: boolean,
  ) => {
    const request = ++graphNeighborhoodRequest.current;
    graphNeighborhoodAbort.current?.abort();
    const controller = new AbortController();
    graphNeighborhoodAbort.current = controller;
    graphNeighborhoodRetry.current = graphNeighborhoodRetryRequest(
      node,
      cursor,
      append,
      false,
    );
    setGraphNeighborhoodError(null);
    setGraphNeighborhoodRestartRequired(false);
    if (append) {
      setGraphNeighborhoodLoadingMore(true);
    } else {
      graphNeighborhoodSnapshot.current = null;
      setGraphNeighborhood(null);
      setGraphNeighborhoodLoading(true);
      setGraphNeighborhoodLoadingMore(false);
    }

    try {
      const page = await api.graphNeighborhood(
        node.kind,
        graphNodeEntityId(node),
        cursor,
        controller.signal,
        GRAPH_NEIGHBORHOOD_PAGE_LIMIT,
      );
      if (!isCurrentScopedRequest({
        request,
        latestStarted: graphNeighborhoodRequest.current,
        aborted: controller.signal.aborted,
        expectedScope: node.id,
        currentScope: graphSelectionIdentity.current,
      })) return;

      const merged = mergeGraphNeighborhoodPage(
        append ? graphNeighborhoodSnapshot.current : null,
        page,
        cursor,
      );
      graphNeighborhoodSnapshot.current = merged;
      graphNeighborhoodRetry.current = null;
      setGraphNeighborhood(merged);
    } catch (error) {
      if (isCurrentScopedRequest({
        request,
        latestStarted: graphNeighborhoodRequest.current,
        aborted: controller.signal.aborted,
        expectedScope: node.id,
        currentScope: graphSelectionIdentity.current,
      })) {
        const restartFromFirstPage = error instanceof GraphNeighborhoodMergeError || (
          append &&
          error instanceof ApiError &&
          error.status === 422 &&
          error.code === 'INVALID_GRAPH_CURSOR'
        );
        if (restartFromFirstPage) {
          graphNeighborhoodRetry.current = graphNeighborhoodRetryRequest(
            node,
            cursor,
            append,
            true,
          );
          setGraphNeighborhoodRestartRequired(true);
          setGraphNeighborhoodError(
            '연결 순서가 변경되었거나 페이지 정보가 만료되었습니다. 현재 목록은 이전 페이지 기준일 수 있으므로 전체 연결을 처음부터 다시 불러와 주세요.',
          );
        } else {
          setGraphNeighborhoodError(errorMessage(error));
        }
      }
    } finally {
      if (isCurrentScopedRequest({
        request,
        latestStarted: graphNeighborhoodRequest.current,
        aborted: controller.signal.aborted,
        expectedScope: node.id,
        currentScope: graphSelectionIdentity.current,
      })) {
        setGraphNeighborhoodLoading(false);
        setGraphNeighborhoodLoadingMore(false);
      }
    }
  }, []);

  const selectGraphNode = useCallback((node: GraphNode) => {
    graphSelectionIdentity.current = node.id;
    graphSelectionNodeSnapshot.current = node;
    setSelectedGraphNode(node);
    setSelectedGraphProjectionVersion(graph.projectionVersion);
    setGraphPinError(null);
    activeGraphMemoIdentity.current = node.kind === 'MEMO' ? graphNodeEntityId(node) : null;
    setActiveGraphMemoNode(node.kind === 'MEMO' ? node : null);
    void loadGraphNeighborhood(node, null, false);
    if (node.kind === 'MEMO') {
      void loadGraphMemoDetail(node);
      return;
    }

    graphDetailRequest.current += 1;
    graphDetailAbort.current?.abort();
    graphDetailAbort.current = null;
    setSelectedGraphMemo(null);
    setGraphDetailLoading(false);
    setGraphDetailError(null);
  }, [graph.projectionVersion, loadGraphMemoDetail, loadGraphNeighborhood]);

  const openGraphNeighborhoodMemo = useCallback((node: GraphNode) => {
    const neighborhood = graphNeighborhoodSnapshot.current;
    if (
      node.kind !== 'MEMO' ||
      !neighborhood ||
      graphSelectionIdentity.current !== neighborhood.center.id ||
      !neighborhood.neighbors.some(
        (neighbor) => neighbor.kind === 'MEMO' && neighbor.id === node.id,
      )
    ) return;

    setGraphPinError(null);
    activeGraphMemoIdentity.current = graphNodeEntityId(node);
    setActiveGraphMemoNode(node);
    void loadGraphMemoDetail(node);
  }, [loadGraphMemoDetail]);

  const backToGraphNeighborhood = useCallback(() => {
    graphDetailRequest.current += 1;
    graphDetailAbort.current?.abort();
    graphDetailAbort.current = null;
    activeGraphMemoIdentity.current = null;
    setActiveGraphMemoNode(null);
    setSelectedGraphMemo(null);
    setGraphDetailLoading(false);
    setGraphDetailError(null);
    setGraphPinError(null);
  }, []);

  const closeGraphNode = useCallback(() => {
    graphSelectionIdentity.current = null;
    graphSelectionNodeSnapshot.current = null;
    activeGraphMemoIdentity.current = null;
    graphDetailRequest.current += 1;
    graphDetailAbort.current?.abort();
    graphDetailAbort.current = null;
    graphNeighborhoodRequest.current += 1;
    graphNeighborhoodAbort.current?.abort();
    graphNeighborhoodAbort.current = null;
    graphNeighborhoodSnapshot.current = null;
    graphNeighborhoodRetry.current = null;
    setSelectedGraphNode(null);
    setSelectedGraphProjectionVersion(null);
    setActiveGraphMemoNode(null);
    setSelectedGraphMemo(null);
    setGraphDetailLoading(false);
    setGraphDetailError(null);
    setGraphNeighborhood(null);
    setGraphNeighborhoodLoading(false);
    setGraphNeighborhoodLoadingMore(false);
    setGraphNeighborhoodError(null);
    setGraphNeighborhoodRestartRequired(false);
    setGraphPinError(null);
  }, []);

  const retryGraphNodeDetail = useCallback(() => {
    if (activeGraphMemoNode) {
      void loadGraphMemoDetail(activeGraphMemoNode);
    }
  }, [activeGraphMemoNode, loadGraphMemoDetail]);

  const retryGraphNeighborhood = useCallback(() => {
    const retry = graphNeighborhoodRetry.current;
    if (retry && graphSelectionIdentity.current === retry.node.id) {
      void loadGraphNeighborhood(retry.node, retry.cursor, retry.append);
    }
  }, [loadGraphNeighborhood]);

  const loadMoreGraphNeighborhood = useCallback(() => {
    const current = graphNeighborhoodSnapshot.current;
    const node = selectedGraphNode;
    if (
      !current?.nextCursor ||
      !node ||
      graphNeighborhoodLoadingMore ||
      graphSelectionIdentity.current !== node.id
    ) return;
    void loadGraphNeighborhood(node, current.nextCursor, true);
  }, [graphNeighborhoodLoadingMore, loadGraphNeighborhood, selectedGraphNode]);

  const refreshReviewOutcomes = useCallback(async () => {
    const request = ++reviewOutcomeRequest.current;
    setReviewOutcomeLoading(true);
    setReviewOutcomeError(null);
    try {
      const summary = await api.reviewOutcomeSummary(14);
      if (reviewOutcomeRequest.current === request) setReviewOutcomeSummary(summary);
    } catch (error) {
      if (reviewOutcomeRequest.current === request) setReviewOutcomeError(errorMessage(error));
    } finally {
      if (reviewOutcomeRequest.current === request) setReviewOutcomeLoading(false);
    }
  }, []);

  const refreshAnalysisPathEvidence = useCallback(async () => {
    const request = ++analysisPathEvidenceRequest.current;
    setAnalysisPathEvidenceLoading(true);
    setAnalysisPathEvidenceError(null);
    try {
      const summary = await api.analysisPathEvidenceSummary(14);
      if (analysisPathEvidenceRequest.current === request) {
        setAnalysisPathEvidenceSummary(summary);
      }
    } catch (error) {
      if (analysisPathEvidenceRequest.current === request) {
        setAnalysisPathEvidenceError(errorMessage(error));
      }
    } finally {
      if (analysisPathEvidenceRequest.current === request) {
        setAnalysisPathEvidenceLoading(false);
      }
    }
  }, []);

  const loadRelationReviewCandidates = useCallback(async (
    snapshot: Pick<ReviewDraft, 'proposalId' | 'proposal'>,
  ) => {
    const request = ++relationReviewRequest.current;
    relationReviewAbort.current?.abort();
    relationReviewAbort.current = null;

    if (snapshot.proposal.relationCandidates.length === 0) {
      const ready: RelationReviewLoadState = {
        proposalId: snapshot.proposalId,
        status: 'READY',
        candidates: [],
        error: null,
      };
      relationReviewLoadStateRef.current = ready;
      setRelationReviewLoadState(ready);
      return;
    }

    const controller = new AbortController();
    relationReviewAbort.current = controller;
    const loading: RelationReviewLoadState = {
      proposalId: snapshot.proposalId,
      status: 'LOADING',
      candidates: null,
      error: null,
    };
    relationReviewLoadStateRef.current = loading;
    setRelationReviewLoadState(loading);

    try {
      const candidates = await api.relationReviewCandidates(
        snapshot.proposalId,
        snapshot.proposal,
        controller.signal,
      );
      if (controller.signal.aborted || relationReviewRequest.current !== request) return;
      const ready: RelationReviewLoadState = {
        proposalId: snapshot.proposalId,
        status: 'READY',
        candidates,
        error: null,
      };
      relationReviewLoadStateRef.current = ready;
      setRelationReviewLoadState(ready);
    } catch (error) {
      if (controller.signal.aborted || relationReviewRequest.current !== request) return;
      const failed: RelationReviewLoadState = {
        proposalId: snapshot.proposalId,
        status: 'ERROR',
        candidates: null,
        error: errorMessage(error),
      };
      relationReviewLoadStateRef.current = failed;
      setRelationReviewLoadState(failed);
    } finally {
      if (relationReviewAbort.current === controller) relationReviewAbort.current = null;
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
    void refreshReviewOutcomes();
  }, [
    checkConnection,
    refreshMemos,
    refreshRecovery,
    refreshReviewOutcomes,
    refreshWorkspace,
  ]);

  const reviewProposalId = review?.proposalId ?? null;
  const reviewProposal = review?.proposal ?? null;
  const listedReviewMemo = reviewProposal
    ? activeMemos.find(
        (memo) => memo.id === reviewProposal.memoId &&
          memo.currentRevision === reviewProposal.memoRevision,
      ) ?? null
    : null;

  useEffect(() => {
    const request = ++reviewMemoSourceRequest.current;
    setReviewSourceTimeZone(null);
    setReviewClientRecordedAt(null);
    setReviewSourceTimeZoneError(null);
    if (!reviewProposal) return;
    if (listedReviewMemo?.sourceTimeZone && listedReviewMemo.clientRecordedAt) {
      setReviewSourceTimeZone(listedReviewMemo.sourceTimeZone);
      setReviewClientRecordedAt(listedReviewMemo.clientRecordedAt);
      return;
    }

    void api.memo(reviewProposal.memoId).then((memo) => {
      if (reviewMemoSourceRequest.current !== request) return;
      if (memo.currentRevision !== reviewProposal.memoRevision) {
        setReviewSourceTimeZoneError('메모 revision이 변경되어 작성 시간대를 확인할 수 없습니다.');
        return;
      }
      if (memo.sourceTimeZone && memo.clientRecordedAt) {
        setReviewSourceTimeZone(memo.sourceTimeZone);
        setReviewClientRecordedAt(memo.clientRecordedAt);
      } else if (!memo.clientRecordedAt) {
        setReviewSourceTimeZoneError('메모 revision의 작성 시각이 응답에 없습니다.');
      } else {
        setReviewSourceTimeZoneError('메모 revision의 시간대가 응답에 없습니다.');
      }
    }).catch((error: unknown) => {
      if (reviewMemoSourceRequest.current === request) {
        setReviewSourceTimeZone(null);
        setReviewClientRecordedAt(null);
        setReviewSourceTimeZoneError(errorMessage(error));
      }
    });
  }, [listedReviewMemo, ownerId, reviewProposal, reviewSourceTimeZoneRetry]);

  useEffect(() => {
    if (!reviewProposalId || !reviewProposal) {
      relationReviewRequest.current += 1;
      relationReviewAbort.current?.abort();
      relationReviewAbort.current = null;
      relationReviewLoadStateRef.current = null;
      setRelationReviewLoadState(null);
      return;
    }

    void loadRelationReviewCandidates({ proposalId: reviewProposalId, proposal: reviewProposal });
    return () => {
      relationReviewRequest.current += 1;
      relationReviewAbort.current?.abort();
      relationReviewAbort.current = null;
    };
  }, [loadRelationReviewCandidates, ownerId, reviewProposal, reviewProposalId]);

  useEffect(() => {
    if (workspaceLoading || !selectedGraphNode) return;
    const projectedNode = graph.nodes.find((node) => node.id === selectedGraphNode.id);
    if (!projectedNode) {
      closeGraphNode();
      return;
    }
    graphSelectionNodeSnapshot.current = projectedNode;
  }, [closeGraphNode, graph.nodes, selectedGraphNode, workspaceLoading]);

  useEffect(() => () => {
    graphDetailRequest.current += 1;
    graphDetailAbort.current?.abort();
    graphNeighborhoodRequest.current += 1;
    graphNeighborhoodAbort.current?.abort();
    relationReviewRequest.current += 1;
    relationReviewAbort.current?.abort();
  }, []);

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

  function clearProposalRetries(proposalId: string, keepScope?: string) {
    const proposalScopes = [
      `apply:${proposalId}`,
      `postpone:${proposalId}`,
      `reject:${proposalId}`,
    ];
    setRetryAction((current) => {
      if (!current) return null;
      return proposalScopes.includes(current.scope) && current.scope !== keepScope ? null : current;
    });
    proposalScopes
      .filter((scope) => scope !== keepScope)
      .forEach((scope) => retryIdentities.current.clear(scope));
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
      void refreshReviewOutcomes();
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
    clearProposalRetries(nextReview.proposalId);
  }

  async function applyReview(snapshot: ReviewDraft) {
    const scope = `apply:${snapshot.proposalId}`;
    clearProposalRetries(snapshot.proposalId, scope);
    const relationState = relationReviewLoadStateRef.current;
    const relationCandidates = snapshot.proposal.relationCandidates.length === 0
      ? []
      : relationState?.proposalId === snapshot.proposalId && relationState.status === 'READY'
        ? relationState.candidates
        : null;
    if (!isRelationSelectionReady(snapshot, relationCandidates)) {
      setFeedback({
        kind: 'error',
        message: '연결 후보 정보를 확인하거나 사용할 수 없는 연결을 제외한 뒤 다시 승인해 주세요.',
      });
      return;
    }
    const unresolvedBareTime = findBareTimeClarification(snapshot);
    if (unresolvedBareTime) {
      setFeedback({
        kind: 'error',
        message: unresolvedBareTime.fixedHour24 === null
          ? `‘${unresolvedBareTime.candidate.surfaceText}’의 날짜와 오전·오후를 확인하거나 시간 없이 두기를 선택해 주세요.`
          : `‘${unresolvedBareTime.candidate.surfaceText}’의 날짜를 확인하거나 시간 없이 두기를 선택해 주세요.`,
      });
      return;
    }
    const sourceTimeZoneRequired = requiresReviewSourceTimeZone(snapshot);
    const sourceTimeZone = reviewSourceTimeZone;
    if (sourceTimeZoneRequired && !sourceTimeZone) {
      setFeedback({
        kind: 'error',
        message: '메모 revision의 작성 시간대를 불러온 뒤 시각을 승인해 주세요.',
      });
      return;
    }
    const body = buildApplyRequest(snapshot, sourceTimeZone ?? timeZone.current);
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
      clearProposalRetries(snapshot.proposalId);
      setFeedback({ kind: 'success', message: '승인한 태그와 항목을 생성했습니다.' });
      void refreshReviewOutcomes();
      await Promise.all([refreshWorkspace(), refreshMemos(), refreshRecovery()]);
    } catch (error) {
      if (
        error instanceof ApiError &&
        error.code !== undefined &&
        TERMINAL_APPLY_CONFLICT_CODES.has(error.code)
      ) {
        clearProposalRetries(snapshot.proposalId);
        setFeedback({ kind: 'error', message: errorMessage(error) });
        void refreshReviewOutcomes();
        await Promise.all([refreshMemos(), refreshRecovery()]);
      } else {
        fail(error, scope, '승인 다시 시도', () => void applyReview(snapshot));
        if (error instanceof ApiError && error.code === 'RELATION_TARGET_UNAVAILABLE') {
          void loadRelationReviewCandidates(snapshot);
        }
      }
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
    clearProposalRetries(snapshot.proposalId, scope);
    const idempotencyKey = retryIdentities.current.keyFor(scope, snapshot.proposalId);
    setBusyAction(scope);
    clearRetry(scope);

    try {
      await api.postponeProposal(snapshot.proposalId, idempotencyKey);
      setReview(null);
      setHasUnsavedReview(false);
      clearProposalRetries(snapshot.proposalId);
      setFeedback({
        kind: 'info',
        message: '제안을 보류했습니다. 승인 전이므로 생성된 항목은 없습니다.',
      });
      void refreshReviewOutcomes();
      await Promise.all([refreshMemos(), refreshRecovery()]);
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
    clearProposalRetries(snapshot.proposalId, scope);
    const idempotencyKey = retryIdentities.current.keyFor(scope, snapshot.proposalId);
    setBusyAction(scope);
    clearRetry(scope);

    try {
      await api.rejectProposal(snapshot.proposalId, idempotencyKey);
      setReview(null);
      setHasUnsavedReview(false);
      clearProposalRetries(snapshot.proposalId);
      setFeedback({
        kind: 'info',
        message: '제안을 거절했습니다. 원본 메모는 그대로 보존됩니다.',
      });
      void refreshReviewOutcomes();
      await Promise.all([refreshMemos(), refreshRecovery()]);
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
      void refreshReviewOutcomes();
      await refreshAfterMemoSourceEdit({
        refreshMemos,
        refreshEvents,
        refreshRecovery,
      });
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
      void refreshReviewOutcomes();
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
      void refreshReviewOutcomes();
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
      void refreshReviewOutcomes();
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

  async function runSetMemoPinned(memoId: string, pinned: boolean) {
    const scope = `pin:${memoId}`;
    const body = { pinned };
    const idempotencyKey = retryIdentities.current.keyFor(scope, JSON.stringify(body));
    const selectionIdAtStart = graphSelectionIdentity.current;
    const activeMemoIdAtStart = activeGraphMemoIdentity.current;
    setBusyAction(scope);
    clearRetry(scope);
    setGraphPinError(null);
    setFeedback({
      kind: 'info',
      message: pinned ? '메모를 홈 그래프에 고정하고 있습니다.' : '메모 고정을 해제하고 있습니다.',
    });

    try {
      const result = await api.setMemoPinned(memoId, pinned, idempotencyKey);
      const selectionStillActive = selectionIdAtStart !== null &&
        graphSelectionIdentity.current === selectionIdAtStart;
      const detailStillActive = selectionStillActive &&
        activeMemoIdAtStart === result.id &&
        activeGraphMemoIdentity.current === result.id;
      let neighborhoodReload: Promise<void> | null = null;
      if (selectionStillActive) {
        if (detailStillActive) {
          setSelectedGraphMemo((current) =>
            current?.id === result.id ? { ...current, pinned: result.pinned } : current,
          );
          setActiveGraphMemoNode((current) =>
            current?.kind === 'MEMO' && graphNodeEntityId(current) === result.id
              ? { ...current, pinned: result.pinned }
              : current,
          );
        }
        setSelectedGraphNode((current) =>
          current?.kind === 'MEMO' && graphNodeEntityId(current) === result.id
            ? { ...current, pinned: result.pinned }
            : current,
        );

        const rootNode = graphSelectionNodeSnapshot.current;
        const reconciled = reconcileGraphNeighborhoodAfterMemoPin(
          graphNeighborhoodSnapshot.current,
          result.id,
          result.pinned,
        );
        const reloadCenter = reconciled.reloadCenter ?? (
          rootNode?.kind === 'TAG' && rootNode.id === selectionIdAtStart
            ? rootNode
            : null
        );
        if (reloadCenter) {
          neighborhoodReload = loadGraphNeighborhood(reloadCenter, null, false);
        } else {
          graphNeighborhoodSnapshot.current = reconciled.collection;
          setGraphNeighborhood(reconciled.collection);
        }
      }
      clearRetry(scope);
      setFeedback({
        kind: 'success',
        message: result.pinned
          ? '메모를 홈 그래프에 고정했습니다.'
          : '메모의 홈 그래프 고정을 해제했습니다.',
      });
      await Promise.all([
        refreshWorkspace(),
        refreshMemos(),
        neighborhoodReload ?? Promise.resolve(),
      ]);
      retryIdentities.current.clear(scope);
    } catch (error) {
      if (
        selectionIdAtStart !== null &&
        graphSelectionIdentity.current === selectionIdAtStart &&
        activeMemoIdAtStart !== null &&
        activeGraphMemoIdentity.current === activeMemoIdAtStart
      ) {
        setGraphPinError(errorMessage(error));
      }
      if (
        error instanceof ApiError &&
        (error.status === 404 || error.code === 'MEMO_NOT_ACTIVE')
      ) {
        clearRetry(scope);
        retryIdentities.current.clear(scope);
        setFeedback({ kind: 'error', message: errorMessage(error) });
        await Promise.all([refreshWorkspace(), refreshMemos()]);
      } else {
        fail(
          error,
          scope,
          '고정 변경 다시 시도',
          () => void runSetMemoPinned(memoId, pinned),
        );
      }
    } finally {
      setBusyAction(null);
    }
  }

  function dismissFeedback() {
    setFeedback(null);
    setRetryAction(null);
  }

  const activeRelationReviewState =
    review && relationReviewLoadState?.proposalId === review.proposalId
      ? relationReviewLoadState
      : null;
  const hasRelationCandidates = (review?.proposal.relationCandidates.length ?? 0) > 0;
  const activeRelationReviewCandidates = !hasRelationCandidates
    ? []
    : activeRelationReviewState?.status === 'READY'
      ? activeRelationReviewState.candidates
      : null;

  return {
    connection,
    content,
    hasUnpersistedCapture: content.length > 0 && draftPersistenceFailed,
    review,
    relationReviewCandidates: activeRelationReviewCandidates,
    relationReviewLoading:
      hasRelationCandidates &&
      (activeRelationReviewState === null || activeRelationReviewState.status === 'LOADING'),
    relationReviewError:
      activeRelationReviewState?.status === 'ERROR' ? activeRelationReviewState.error : null,
    hasUnsavedReview,
    postponedReview,
    reviewSourceTimeZone,
    reviewClientRecordedAt,
    reviewSourceTimeZoneError,
    applicationId,
    tasks,
    events,
    graph,
    selectedGraphNode,
    selectedGraphProjectionVersion,
    selectedGraphMemo,
    activeGraphMemoNode,
    graphDetailLoading,
    graphDetailError,
    graphNeighborhood,
    graphNeighborhoodLoading,
    graphNeighborhoodLoadingMore,
    graphNeighborhoodError,
    graphNeighborhoodRestartRequired,
    graphPinError,
    activeMemos,
    trashedMemos,
    workspaceLoading,
    workspaceError,
    recoveryLoading,
    recoveryError,
    memosLoading,
    memosError,
    eventsLoading,
    eventsError,
    reviewOutcomeSummary,
    reviewOutcomeLoading,
    reviewOutcomeError,
    analysisPathEvidenceSummary,
    analysisPathEvidenceLoading,
    analysisPathEvidenceError,
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
    pinPending: busyAction?.startsWith('pin:') ?? false,
    feedback,
    retryAction,
    checkConnection,
    refreshWorkspace,
    refreshEvents,
    refreshMemos,
    refreshRecovery,
    refreshReviewOutcomes,
    refreshAnalysisPathEvidence,
    selectGraphNode,
    openGraphNeighborhoodMemo,
    backToGraphNeighborhood,
    closeGraphNode,
    retryGraphNodeDetail,
    retryGraphNeighborhood,
    loadMoreGraphNeighborhood,
    changeContent,
    captureMemo,
    changeReview,
    retryRelationReviewCandidates: () => {
      if (review) void loadRelationReviewCandidates(review);
    },
    retryReviewSourceTimeZone: () => setReviewSourceTimeZoneRetry((current) => current + 1),
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
    setMemoPinned: (memoId: string, pinned: boolean) => void runSetMemoPinned(memoId, pinned),
    retryGraphPin: () => {
      if (retryAction?.scope.startsWith('pin:')) retryAction.run();
    },
    retry: () => retryAction?.run(),
    dismissFeedback,
  };
}

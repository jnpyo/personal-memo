import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from 'react';
import { api } from '../../shared/api/client';
import { errorMessage } from '../../shared/api/errors';
import type {
  MemoSearchItem,
  MemoSearchMatchedField,
  MemoStatus,
  MemoView,
  TaskStatus,
} from '../../shared/api/types';
import { CurrentMemoDetail } from '../memos/CurrentMemoDetail';
import {
  MemoDetailActions,
  type MemoDetailActionsConfig,
} from '../memos/MemoDetailActions';
import {
  buildMemoSearchRequest,
  emptyMemoSearchDraft,
  MemoSearchValidationError,
  type MemoSearchDraft,
} from './searchModel';
import { useMemoSearch } from './useMemoSearch';

const LIFECYCLE_LABEL: Record<MemoStatus, string> = {
  ACTIVE: '활성',
  TRASHED: '휴지통',
};
const TASK_LABEL: Record<TaskStatus | 'NONE', string> = {
  TODO: '미완료',
  DONE: '완료',
  CANCELLED: '취소',
  NONE: '작업 없음',
};
const MATCH_LABEL: Record<MemoSearchMatchedField, string> = {
  TITLE: '제목 일치',
  BODY: '원문 일치',
  TAG: '태그 일치',
  ALIAS: '별칭 일치',
};

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Seoul';
}

function formatRevisedAt(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

export function memoSearchResultTitle(item: MemoSearchItem): string {
  const title = item.title?.trim();
  if (title) return title;
  const firstPreviewLine = item.preview.split(/\r?\n/, 1)[0]?.trim();
  return firstPreviewLine || '제목 없는 메모';
}

function canReceiveFocus(element: HTMLElement | null): element is HTMLElement {
  return Boolean(element?.isConnected && !element.hasAttribute('disabled'));
}

export function memoSearchOverdueDisabled(
  taskState: MemoSearchDraft['taskState'],
): boolean {
  return taskState !== '' && taskState !== 'TODO';
}

export function focusMemoSearchResult(
  memoId: string,
  opener: HTMLElement | null,
  fallback: HTMLElement | null,
): () => void {
  let cancelled = false;
  let frameId: number | null = null;
  let attempts = 0;
  const restore = () => {
    if (cancelled) return;
    const target = canReceiveFocus(opener)
      ? opener
      : document.getElementById(`memo-search-result-${memoId}`);
    if (canReceiveFocus(target)) {
      target.focus({ preventScroll: true });
      target.scrollIntoView({ block: 'nearest' });
      return;
    }
    attempts += 1;
    if (attempts < 2) {
      frameId = window.requestAnimationFrame(restore);
      return;
    }
    if (canReceiveFocus(fallback)) fallback.focus({ preventScroll: true });
  };
  frameId = window.requestAnimationFrame(restore);
  return () => {
    cancelled = true;
    if (frameId !== null) window.cancelAnimationFrame(frameId);
  };
}

type DetailDialogProps = {
  item: MemoSearchItem;
  memo: MemoView | null;
  loading: boolean;
  error: string | null;
  onClose: () => void;
  onRetry: () => void;
  memoActions?: MemoDetailActionsConfig;
};

export function MemoSearchDetailDialog({
  item,
  memo,
  loading,
  error,
  onClose,
  onRetry,
  memoActions,
}: DetailDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const scrollRef = useRef<HTMLElement>(null);
  const title = memoSearchResultTitle(item);
  const selectedMemo = memo?.id === item.memoId ? memo : null;
  const selectedMemoError = memo && !selectedMemo
    ? '선택한 메모와 상세 응답이 일치하지 않습니다. 다시 불러와 주세요.'
    : error;

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!dialog.open) dialog.showModal();
    const frame = window.requestAnimationFrame(() => {
      if (scrollRef.current) scrollRef.current.scrollTop = 0;
      headingRef.current?.focus({ preventScroll: true });
    });
    return () => {
      window.cancelAnimationFrame(frame);
      if (dialog.open) dialog.close();
    };
  }, [item.memoId]);

  const lifecycleChanged = selectedMemo !== null && selectedMemo.status !== item.lifecycleStatus;
  return (
    <dialog
      ref={dialogRef}
      className="graph-detail-dialog search-detail-dialog"
      aria-labelledby="memo-search-detail-title"
      aria-busy={loading}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <section ref={scrollRef} className="graph-detail-drawer search-detail-drawer">
        <header className="graph-detail-drawer__header">
          <div>
            <span className="eyebrow">SEARCH MEMO DETAIL</span>
            <h2 id="memo-search-detail-title" ref={headingRef} tabIndex={-1}>{title} 상세</h2>
          </div>
          <button
            type="button"
            className="graph-detail-drawer__close"
            aria-label="검색 메모 상세 닫기"
            onClick={onClose}
          >
            <span aria-hidden="true">×</span>
          </button>
        </header>

        <p className="graph-detail-drawer__scope">
          검색 결과를 그래프에 추가하지 않고, 서버에서 현재 원문을 다시 읽었습니다.
          검색 시점의 제목·태그·상태는 아래 검색 메타데이터로만 표시합니다.
        </p>
        <dl className="graph-detail-metadata">
          <div><dt>메모 상태</dt><dd>{LIFECYCLE_LABEL[item.lifecycleStatus]}</dd></div>
          <div><dt>작업 상태</dt><dd>{TASK_LABEL[item.taskState]}{item.overdue ? ' · 기한 초과' : ''}</dd></div>
          <div><dt>그래프 고정</dt><dd>{item.pinned ? '고정됨' : '고정 안 됨'}</dd></div>
          <div>
            <dt>승인 제목</dt>
            <dd>
              {item.title
                ? `${item.title} · revision ${item.canonicalRevision}`
                : '승인된 제목 없음'}
            </dd>
          </div>
        </dl>
        {lifecycleChanged && (
          <p className="memo-detail-revision-warning" role="status">
            검색 후 메모 상태가 변경되었습니다. 현재 상태는 {selectedMemo ? LIFECYCLE_LABEL[selectedMemo.status] : '알 수 없음'}입니다.
          </p>
        )}
        <CurrentMemoDetail
          memo={selectedMemo}
          loading={loading}
          error={selectedMemoError}
          onRetry={onRetry}
          headingId="memo-search-raw-content-title"
          expectedRevision={item.currentRevision}
        />
        {selectedMemo && memoActions && <MemoDetailActions key={selectedMemo.id} memo={selectedMemo} {...memoActions} />}
        <section className="graph-detail-block" aria-labelledby="memo-search-tags-title">
          <div className="graph-detail-block__heading">
            <h3 id="memo-search-tags-title">검색 시점 canonical 태그</h3>
            <span>최대 8개 표시</span>
          </div>
          {item.canonicalTags.length > 0 ? (
            <ul className="graph-detail-neighbors">
              {item.canonicalTags.map((tag) => <li key={tag.id}>#{tag.name}</li>)}
            </ul>
          ) : (
            <p className="graph-detail-empty">표시할 canonical 태그가 없습니다.</p>
          )}
        </section>
        {item.lifecycleStatus === 'TRASHED' && (
          <p className="search-detail-restriction">
            휴지통 메모에서는 그래프 연결이나 고정 변경을 제공하지 않습니다.
          </p>
        )}
      </section>
    </dialog>
  );
}

type MemoSearchProps = {
  memoActions?: MemoDetailActionsConfig;
  canCloseDetail?: () => boolean;
};

export function MemoSearch({ memoActions, canCloseDetail }: MemoSearchProps = {}) {
  const search = useMemoSearch();
  const [draft, setDraft] = useState<MemoSearchDraft>(() => emptyMemoSearchDraft());
  const [validationError, setValidationError] = useState<string | null>(null);
  const [selectedItem, setSelectedItem] = useState<MemoSearchItem | null>(null);
  const [memoDetail, setMemoDetail] = useState<MemoView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const detailOpenerRef = useRef<HTMLButtonElement | null>(null);
  const detailAbortRef = useRef<AbortController | null>(null);
  const detailGenerationRef = useRef(0);
  const detailSelectionRef = useRef<MemoSearchItem | null>(null);
  const focusRestoreCancelRef = useRef<(() => void) | null>(null);
  const loadMoreRef = useRef<HTMLButtonElement | null>(null);
  const searchRetryRef = useRef<HTMLButtonElement | null>(null);
  const pendingLoadMoreFocusRef = useRef<{
    previousCount: number;
    opener: HTMLButtonElement;
  } | null>(null);
  const timeZone = useMemo(browserTimeZone, []);
  const overdueDisabled = memoSearchOverdueDisabled(draft.taskState);
  const items = search.collection?.items ?? [];

  const loadDetail = useCallback(async (item: MemoSearchItem, preserveDetail = false) => {
    if (detailSelectionRef.current?.memoId !== item.memoId) return;
    const generation = ++detailGenerationRef.current;
    detailAbortRef.current?.abort();
    const controller = new AbortController();
    detailAbortRef.current = controller;
    // Conflict recovery must update the source beneath the editor, not unmount its draft.
    setMemoDetail((current) => preserveDetail && current?.id === item.memoId ? current : null);
    setDetailLoading(true);
    setDetailError(null);
    try {
      const memo = await api.memo(item.memoId, controller.signal);
      if (detailGenerationRef.current === generation && !controller.signal.aborted) {
        if (memo.id !== item.memoId) {
          throw new Error('선택한 메모와 상세 응답이 일치하지 않습니다. 다시 불러와 주세요.');
        }
        setMemoDetail(memo);
      }
    } catch (caught) {
      if (detailGenerationRef.current === generation && !controller.signal.aborted) {
        setDetailError(errorMessage(caught));
        throw caught;
      }
    } finally {
      if (detailGenerationRef.current === generation && !controller.signal.aborted) {
        setDetailLoading(false);
      }
    }
  }, []);

  const closeDetail = useCallback(() => {
    if (canCloseDetail && !canCloseDetail()) return false;
    const closedItem = detailSelectionRef.current;
    detailSelectionRef.current = null;
    detailGenerationRef.current += 1;
    detailAbortRef.current?.abort();
    detailAbortRef.current = null;
    setSelectedItem(null);
    setMemoDetail(null);
    setDetailLoading(false);
    setDetailError(null);
    if (!closedItem) return true;
    const opener = detailOpenerRef.current;
    detailOpenerRef.current = null;
    focusRestoreCancelRef.current?.();
    focusRestoreCancelRef.current = focusMemoSearchResult(
      closedItem.memoId,
      opener,
      inputRef.current,
    );
    return true;
  }, [canCloseDetail]);

  useEffect(() => () => {
    detailSelectionRef.current = null;
    detailGenerationRef.current += 1;
    detailAbortRef.current?.abort();
    focusRestoreCancelRef.current?.();
  }, []);

  useEffect(() => {
    const pending = pendingLoadMoreFocusRef.current;
    if (!pending || search.loadingMore) return;
    pendingLoadMoreFocusRef.current = null;
    const active = document.activeElement;
    if (
      active &&
      active !== document.body &&
      active !== pending.opener &&
      (active as HTMLElement).isConnected
    ) return;
    if (search.error) {
      searchRetryRef.current?.focus({ preventScroll: true });
      return;
    }
    if (search.collection?.nextCursor) {
      loadMoreRef.current?.focus({ preventScroll: true });
      return;
    }
    const firstNew = search.collection?.items[pending.previousCount];
    if (firstNew) {
      document.getElementById(`memo-search-result-${firstNew.memoId}`)?.focus({ preventScroll: true });
    }
  }, [search.collection, search.error, search.loadingMore]);

  function updateDraft(patch: Partial<MemoSearchDraft>) {
    setDraft((current) => ({ ...current, ...patch }));
    setValidationError(null);
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      const request = buildMemoSearchRequest(draft);
      setValidationError(null);
      search.submit(request);
    } catch (caught) {
      setValidationError(
        caught instanceof MemoSearchValidationError
          ? caught.message
          : '검색 조건을 확인해 주세요.',
      );
    }
  }

  function reset() {
    if (selectedItem && !closeDetail()) return;
    setDraft(emptyMemoSearchDraft());
    setValidationError(null);
    search.clear();
    window.requestAnimationFrame(() => inputRef.current?.focus({ preventScroll: true }));
  }

  async function saveForSelectedDetail(memoId: string, save: () => Promise<boolean>): Promise<boolean> {
    const selection = detailSelectionRef.current;
    const generation = detailGenerationRef.current;
    if (!selection || selection.memoId !== memoId) return false;
    const saved = await save();
    // A successful save belongs to the dialog instance that submitted it.
    // Closing/reopening even the same memo invalidates that instance.
    if (detailGenerationRef.current !== generation || detailSelectionRef.current !== selection) return false;
    if (saved) void loadDetail(selection, true).catch(() => undefined);
    return saved;
  }

  const updateFailure = memoActions?.updateFailure;
  const retryUpdate = updateFailure?.retry;

  const liveStatus = search.loading
    ? '메모를 검색하는 중입니다.'
    : search.restartRequired && search.collection
      ? `${items.length}개는 이전 검색 시점의 결과입니다. 처음부터 다시 검색해야 합니다.`
      : search.error && search.collection
        ? `${items.length}개 표시 중이며 다음 결과를 불러오지 못했습니다.`
    : search.collection
      ? `${items.length}개 표시 중${search.collection.nextCursor ? ', 결과가 더 있습니다.' : '.'}`
      : search.submittedRequest && !search.error
        ? '검색 결과가 없습니다.'
        : '검색어를 입력하면 결과를 표시합니다.';

  return (
    <section className="search-section" aria-labelledby="memo-search-title">
      <div className="section-heading">
        <div>
          <span className="eyebrow">PRIVATE SEARCH</span>
          <h2 id="memo-search-title">메모 검색</h2>
        </div>
        <span className="limit-badge">페이지 20 · 최대 100</span>
      </div>
      <p className="search-privacy-note">
        검색어는 주소나 브라우저 저장소에 남기지 않고 서버에 POST body로만 보냅니다.
        정확한 원문·승인 제목 부분 일치와 canonical 태그·별칭 완전 일치만 지원합니다.
      </p>

      <form className="memo-search-form" onSubmit={submit} noValidate>
        <label htmlFor="memo-search-query">메모 검색어</label>
        <div className="memo-search-query-row">
          <input
            ref={inputRef}
            id="memo-search-query"
            type="search"
            maxLength={200}
            autoComplete="off"
            autoCorrect="off"
            autoCapitalize="none"
            spellCheck={false}
            enterKeyHint="search"
            value={draft.query}
            onChange={(event) => updateDraft({ query: event.target.value })}
          />
          <button
            type="submit"
            className="approve-button"
            disabled={draft.query.trim().length === 0}
          >
            {search.loading ? '새 검색' : '검색'}
          </button>
          <button type="button" className="secondary-button" onClick={reset}>
            초기화
          </button>
        </div>

        <fieldset className="memo-search-lifecycle">
          <legend>메모 상태</legend>
          {(['ACTIVE', 'TRASHED'] as const).map((status) => (
            <label key={status}>
              <input
                type="radio"
                name="memo-search-lifecycle"
                value={status}
                checked={draft.lifecycleStatus === status}
                onChange={() => updateDraft({ lifecycleStatus: status })}
              />
              <span>{LIFECYCLE_LABEL[status]}</span>
            </label>
          ))}
        </fieldset>

        <details className="memo-search-filters">
          <summary>작업·수정일 필터</summary>
          <div className="memo-search-filter-grid">
            <label htmlFor="memo-search-task-state">
              작업 상태
              <select
                id="memo-search-task-state"
                value={draft.taskState}
                onChange={(event) => {
                  const taskState = event.target.value as MemoSearchDraft['taskState'];
                  updateDraft({
                    taskState,
                    ...((taskState !== '' && taskState !== 'TODO')
                      ? { overdueOnly: false }
                      : {}),
                  });
                }}
              >
                <option value="">전체</option>
                <option value="TODO">미완료</option>
                <option value="DONE">완료</option>
                <option value="CANCELLED">취소</option>
                <option value="NONE">작업 없음</option>
              </select>
            </label>
            <label className="memo-search-checkbox">
              <input
                type="checkbox"
                checked={draft.overdueOnly}
                disabled={overdueDisabled}
                onChange={(event) => updateDraft({ overdueOnly: event.target.checked })}
              />
              <span>기한 지난 미완료만</span>
            </label>
            <label htmlFor="memo-search-revised-from">
              원문 수정일 시작
              <input
                id="memo-search-revised-from"
                type="date"
                value={draft.revisedFromDate}
                onChange={(event) => updateDraft({ revisedFromDate: event.target.value })}
              />
            </label>
            <label htmlFor="memo-search-revised-through">
              원문 수정일 끝 (포함)
              <input
                id="memo-search-revised-through"
                type="date"
                value={draft.revisedThroughDate}
                onChange={(event) => updateDraft({ revisedThroughDate: event.target.value })}
              />
            </label>
          </div>
          <p className="memo-search-time-zone">날짜 경계 시간대: {timeZone}</p>
        </details>
        {validationError && <p className="memo-search-validation" role="alert">{validationError}</p>}
      </form>

      <p className="memo-search-live" role="status" aria-live="polite" aria-atomic="true">
        {liveStatus}
      </p>

      {!search.loading && search.error && !search.collection && (
        <aside className="memo-search-error" role="alert">
          <p>{search.error}</p>
          <button
            ref={searchRetryRef}
            type="button"
            className="secondary-button"
            onClick={search.retry}
          >
            검색 다시 시도
          </button>
        </aside>
      )}
      {!search.loading && search.submittedRequest && !search.error && items.length === 0 && (
        <p className="panel-state">일치하는 메모가 없습니다. 검색어나 필터를 바꿔 보세요.</p>
      )}

      {items.length > 0 && (
        <ul className="memo-search-results" aria-label="메모 검색 결과">
          {items.map((item) => {
            const title = memoSearchResultTitle(item);
            return (
              <li key={item.memoId}>
                <button
                  id={`memo-search-result-${item.memoId}`}
                  type="button"
                  className="memo-search-result"
                  onClick={(event) => {
                    detailOpenerRef.current = event.currentTarget;
                    focusRestoreCancelRef.current?.();
                    focusRestoreCancelRef.current = null;
                    detailSelectionRef.current = item;
                    setSelectedItem(item);
                    void loadDetail(item).catch(() => undefined);
                  }}
                >
                  <span className="memo-search-result__heading">
                    <strong>{title}</strong>
                    <span>{LIFECYCLE_LABEL[item.lifecycleStatus]}</span>
                  </span>
                  {item.title && item.canonicalRevision !== item.currentRevision && (
                    <small>승인 제목 revision {item.canonicalRevision} · 현재 원문 revision {item.currentRevision}</small>
                  )}
                  <span className="memo-search-result__preview">{item.preview}</span>
                  <span className="memo-search-result__meta">
                    <span>{TASK_LABEL[item.taskState]}</span>
                    {item.overdue && <span>기한 초과</span>}
                    {item.pinned && <span>그래프 고정됨</span>}
                    <time dateTime={item.revisedAt}>{formatRevisedAt(item.revisedAt)}</time>
                  </span>
                  {item.canonicalTags.length > 0 && (
                    <span className="memo-search-result__tags">
                      {item.canonicalTags.map((tag) => <span key={tag.id}>#{tag.name}</span>)}
                    </span>
                  )}
                  <span className="memo-search-result__matches">
                    {item.matchedFields.map((field) => <span key={field}>{MATCH_LABEL[field]}</span>)}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}

      {search.error && search.collection && (
        <aside className="memo-search-error" role="alert">
          <p>{search.error}</p>
          <button
            ref={searchRetryRef}
            type="button"
            className="secondary-button"
            onClick={search.retry}
          >
            {search.restartRequired ? '처음부터 다시 검색' : '다음 결과 다시 불러오기'}
          </button>
        </aside>
      )}
      {!search.error && search.collection?.nextCursor && (
        <button
          ref={loadMoreRef}
          type="button"
          className="secondary-button memo-search-more"
          disabled={search.loadingMore}
          onClick={(event) => {
            pendingLoadMoreFocusRef.current = {
              previousCount: items.length,
              opener: event.currentTarget,
            };
            search.loadMore();
          }}
        >
          {search.loadingMore ? '다음 결과 불러오는 중…' : '결과 더 불러오기'}
        </button>
      )}
      {search.collection?.browserTruncated && (
        <p className="memo-search-cap" role="status">
          브라우저 표시 상한 100개에 도달했습니다. 검색어나 필터를 좁혀 주세요.
        </p>
      )}

      {selectedItem && (
        <MemoSearchDetailDialog
          item={selectedItem}
          memo={memoDetail}
          loading={detailLoading}
          error={detailError}
          onClose={closeDetail}
          onRetry={() => void loadDetail(selectedItem, true).catch(() => undefined)}
          memoActions={memoActions
            ? {
                ...memoActions,
                busy: memoActions.busy || detailLoading,
                onReload: () => loadDetail(selectedItem, true),
                updateFailure: updateFailure && retryUpdate
                  ? { ...updateFailure, retry: () => saveForSelectedDetail(updateFailure.memoId, retryUpdate) }
                  : updateFailure,
                onUpdate: (memo, content) => saveForSelectedDetail(
                  memo.id,
                  () => memoActions.onUpdate(memo, content),
                ),
                onTrash: (memo) => {
                  const started = memoActions.onTrash(memo);
                  if (started !== false) closeDetail();
                  return started;
                },
                onRestore: (memo) => {
                  memoActions.onRestore(memo);
                  closeDetail();
                },
                onAnalyze: (memo) => {
                  const started = memoActions.onAnalyze(memo);
                  if (started !== false) closeDetail();
                  return started;
                },
              }
            : undefined}
        />
      )}
    </section>
  );
}

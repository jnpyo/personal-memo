import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../shared/api/client';
import { errorMessage } from '../../shared/api/errors';
import type { MemoStatus, MemoView } from '../../shared/api/types';
import { CurrentMemoDetail } from './CurrentMemoDetail';
import { MemoDetailActions, type MemoDetailActionsConfig } from './MemoDetailActions';

const RECENT_MEMO_LIMIT = 50;

type Props = {
  activeMemos: MemoView[];
  trashedMemos: MemoView[];
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  memoActions: MemoDetailActionsConfig;
  canCloseDetail?: () => boolean;
};

function boundedText(value: string, limit: number): string {
  const characters = Array.from(value);
  return characters.length > limit
    ? `${characters.slice(0, limit - 1).join('')}…`
    : value;
}

export function memoBrowseTitle(memo: Pick<MemoView, 'content'>): string {
  const firstLine = memo.content.split(/\r?\n/).find((line) => line.trim())?.trim();
  return boundedText(firstLine || '내용 없는 메모', 80);
}

export function memoBrowsePreview(memo: Pick<MemoView, 'content'>): string {
  return boundedText(memo.content.trim(), 240);
}

export function focusMemoBrowseResult(
  memoId: string,
  opener: HTMLElement | null,
  fallback: HTMLElement | null,
): () => void {
  let cancelled = false;
  const frame = window.requestAnimationFrame(() => {
    if (cancelled) return;
    const available = (element: HTMLElement | null): element is HTMLElement =>
      Boolean(element?.isConnected && !element.hasAttribute('disabled'));
    const result = available(opener)
      ? opener
      : document.getElementById(`memo-browse-result-${memoId}`);
    const target = available(result) ? result : available(fallback) ? fallback : null;
    target?.focus({ preventScroll: true });
  });
  return () => {
    cancelled = true;
    window.cancelAnimationFrame(frame);
  };
}

export async function saveCurrentMemoBrowseDetail(
  save: () => Promise<boolean>,
  isCurrent: () => boolean,
  reload: () => void,
): Promise<boolean> {
  if (!isCurrent()) return false;
  const saved = await save();
  if (!isCurrent()) return false;
  if (saved) reload();
  return saved;
}

type DetailProps = {
  selectedMemo: MemoView;
  memo: MemoView | null;
  loading: boolean;
  error: string | null;
  onClose: () => void;
  onRetry: () => void;
  memoActions: MemoDetailActionsConfig;
};

export function MemoBrowseDetailDialog({
  selectedMemo,
  memo,
  loading,
  error,
  onClose,
  onRetry,
  memoActions,
}: DetailProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const scrollRef = useRef<HTMLElement>(null);
  const currentMemo = memo?.id === selectedMemo.id ? memo : null;
  const detailError = memo && !currentMemo
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
  }, [selectedMemo.id]);

  return (
    <dialog
      ref={dialogRef}
      className="graph-detail-dialog memo-browse-detail-dialog"
      aria-labelledby="memo-browse-detail-title"
      aria-busy={loading}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <section ref={scrollRef} className="graph-detail-drawer">
        <header className="graph-detail-drawer__header">
          <h2 id="memo-browse-detail-title" ref={headingRef} tabIndex={-1}>
            {memoBrowseTitle(currentMemo ?? selectedMemo)}
          </h2>
          <button
            type="button"
            className="graph-detail-drawer__close"
            aria-label="최근 메모 상세 닫기"
            onClick={onClose}
          >
            <span aria-hidden="true">×</span>
          </button>
        </header>
        {currentMemo?.status === 'TRASHED' && <p className="graph-detail-state">휴지통 메모</p>}
        <CurrentMemoDetail
          memo={currentMemo}
          loading={loading}
          error={detailError}
          onRetry={onRetry}
          headingId="memo-browse-raw-content-title"
        />
        {currentMemo && (
          <MemoDetailActions
            key={currentMemo.id}
            memo={currentMemo}
            {...memoActions}
            busy={memoActions.busy || loading}
          />
        )}
      </section>
    </dialog>
  );
}

export function MemoBrowse({
  activeMemos,
  trashedMemos,
  loading,
  error,
  onRetry,
  memoActions,
  canCloseDetail,
}: Props) {
  const [status, setStatus] = useState<MemoStatus>('ACTIVE');
  const [selectedMemo, setSelectedMemo] = useState<MemoView | null>(null);
  const [memoDetail, setMemoDetail] = useState<MemoView | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const selectionRef = useRef<MemoView | null>(null);
  const detailGenerationRef = useRef(0);
  const detailAbortRef = useRef<AbortController | null>(null);
  const openerRef = useRef<HTMLButtonElement | null>(null);
  const filterRef = useRef<HTMLButtonElement | null>(null);
  const focusCancelRef = useRef<(() => void) | null>(null);
  const memos = (status === 'ACTIVE' ? activeMemos : trashedMemos).slice(0, RECENT_MEMO_LIMIT);

  const loadDetail = useCallback(async (selection: MemoView, preserveDetail = false) => {
    if (selectionRef.current !== selection) return;
    const generation = ++detailGenerationRef.current;
    detailAbortRef.current?.abort();
    const controller = new AbortController();
    detailAbortRef.current = controller;
    setMemoDetail((current) => preserveDetail && current?.id === selection.id ? current : null);
    setDetailLoading(true);
    setDetailError(null);
    try {
      const memo = await api.memo(selection.id, controller.signal);
      if (
        selectionRef.current === selection &&
        detailGenerationRef.current === generation &&
        !controller.signal.aborted
      ) {
        if (memo.id !== selection.id) {
          throw new Error('선택한 메모와 상세 응답이 일치하지 않습니다. 다시 불러와 주세요.');
        }
        setMemoDetail(memo);
      }
    } catch (caught) {
      if (
        selectionRef.current === selection &&
        detailGenerationRef.current === generation &&
        !controller.signal.aborted
      ) {
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
    const closedSelection = selectionRef.current;
    selectionRef.current = null;
    detailGenerationRef.current += 1;
    detailAbortRef.current?.abort();
    detailAbortRef.current = null;
    setSelectedMemo(null);
    setMemoDetail(null);
    setDetailLoading(false);
    setDetailError(null);
    focusCancelRef.current?.();
    if (closedSelection) {
      focusCancelRef.current = focusMemoBrowseResult(
        closedSelection.id,
        openerRef.current,
        filterRef.current,
      );
    }
    openerRef.current = null;
    return true;
  }, [canCloseDetail]);

  useEffect(() => () => {
    selectionRef.current = null;
    detailGenerationRef.current += 1;
    detailAbortRef.current?.abort();
    focusCancelRef.current?.();
  }, []);

  function saveForSelection(memoId: string, save: () => Promise<boolean>): Promise<boolean> {
    const selection = selectionRef.current;
    const generation = detailGenerationRef.current;
    return saveCurrentMemoBrowseDetail(
      save,
      () => Boolean(
        selection && selection.id === memoId && selectionRef.current === selection &&
        detailGenerationRef.current === generation,
      ),
      () => {
        if (selection) void loadDetail(selection, true).catch(() => undefined);
      },
    );
  }

  const failure = memoActions.updateFailure?.memoId === selectedMemo?.id
    ? memoActions.updateFailure
    : undefined;
  const retrySave = failure?.retry;

  return (
    <section className="search-section memo-browse-section" aria-labelledby="memo-browse-title">
      <div className="section-heading">
        <h2 id="memo-browse-title">최근 메모</h2>
        <span className="limit-badge">상태별 최근 50개</span>
      </div>
      <div className="memo-filter" role="group" aria-label="최근 메모 상태">
        {(['ACTIVE', 'TRASHED'] as const).map((value) => (
          <button
            key={value}
            ref={status === value ? filterRef : undefined}
            type="button"
            aria-pressed={status === value}
            className={status === value ? 'approve-button' : 'secondary-button'}
            onClick={() => {
              if (selectedMemo && !closeDetail()) return;
              setStatus(value);
            }}
          >
            {value === 'ACTIVE' ? '최근 메모' : '휴지통'}
          </button>
        ))}
      </div>
      <p className="memo-search-live">최근 수정순으로 최대 50개를 표시합니다. 오래된 메모는 아래에서 검색해 주세요.</p>
      {loading && <p role="status" className="panel-state">최근 메모를 불러오는 중…</p>}
      {error && (
        <aside className="memo-search-error" role="alert">
          <p>{error}</p>
          <button type="button" className="secondary-button" onClick={onRetry}>최근 메모 다시 불러오기</button>
        </aside>
      )}
      {!loading && !error && memos.length === 0 && (
        <p className="panel-state">{status === 'ACTIVE' ? '최근 메모가 없습니다.' : '휴지통이 비어 있습니다.'}</p>
      )}
      {memos.length > 0 && (
        <ul className="memo-search-results" aria-label={status === 'ACTIVE' ? '최근 메모 목록' : '최근 휴지통 목록'}>
          {memos.map((memo) => (
            <li key={memo.id}>
              <button
                id={`memo-browse-result-${memo.id}`}
                type="button"
                className="memo-search-result memo-browse-result"
                onClick={(event) => {
                  if (selectedMemo && !closeDetail()) return;
                  focusCancelRef.current?.();
                  focusCancelRef.current = null;
                  const selection = { ...memo };
                  selectionRef.current = selection;
                  openerRef.current = event.currentTarget;
                  setSelectedMemo(selection);
                  void loadDetail(selection).catch(() => undefined);
                }}
              >
                <span className="memo-search-result__heading"><strong>{memoBrowseTitle(memo)}</strong></span>
                <span className="memo-search-result__preview">{memoBrowsePreview(memo)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {selectedMemo && (
        <MemoBrowseDetailDialog
          selectedMemo={selectedMemo}
          memo={memoDetail}
          loading={detailLoading}
          error={detailError}
          onClose={closeDetail}
          onRetry={() => void loadDetail(selectedMemo, true).catch(() => undefined)}
          memoActions={{
            ...memoActions,
            busy: memoActions.busy || detailLoading,
            onReload: () => loadDetail(selectedMemo, true),
            updateFailure: failure && retrySave
              ? { ...failure, retry: () => saveForSelection(failure.memoId, retrySave) }
              : failure,
            onUpdate: (memo, content) => saveForSelection(
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
          }}
        />
      )}
    </section>
  );
}

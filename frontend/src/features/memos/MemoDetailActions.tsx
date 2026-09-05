import { useEffect, useRef, useState } from 'react';
import type { MemoView } from '../../shared/api/types';
import { errorMessage } from '../../shared/api/errors';
import { isMemoContentValid } from './memoModel';

export type MemoUpdateFailure = {
  memoId: string;
  revision: number;
  content: string;
  message: string;
  conflict: boolean;
  retry?: () => Promise<boolean>;
};

export type MemoDetailActionsConfig = {
  busy: boolean;
  pendingScope: string | null;
  analysisBlocked: boolean;
  onUpdate: (memo: MemoView, content: string) => Promise<boolean>;
  onTrash: (memo: MemoView) => boolean | void;
  onRestore: (memo: MemoView) => void;
  onAnalyze: (memo: MemoView) => boolean | void;
  onDirtyChange: (dirty: boolean) => void;
  editDirty?: boolean;
  updateFailure?: MemoUpdateFailure | null;
  onReload?: () => Promise<void>;
};

type Props = MemoDetailActionsConfig & {
  memo: MemoView;
};

type EditDraft = {
  memo: MemoView;
  content: string;
};

export function MemoDetailActions({
  memo,
  busy,
  pendingScope,
  analysisBlocked,
  onUpdate,
  onTrash,
  onRestore,
  onAnalyze,
  onDirtyChange,
  updateFailure,
  onReload,
}: Props) {
  const [editDraft, setEditDraft] = useState<EditDraft | null>(null);
  const [operationPending, setOperationPending] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const alive = useRef(false);
  const operationGeneration = useRef(0);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const editButtonRef = useRef<HTMLButtonElement>(null);
  const editing = editDraft?.memo.id === memo.id;
  const editIsStale = editing && editDraft.memo.currentRevision !== memo.currentRevision;
  const locked = busy || operationPending;
  const failure = editing && updateFailure?.memoId === memo.id &&
    updateFailure.revision === editDraft.memo.currentRevision &&
    updateFailure.content === editDraft.content ? updateFailure : null;

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      operationGeneration.current += 1;
      onDirtyChange(false);
    };
  }, [onDirtyChange]);

  useEffect(() => {
    if (editing) textareaRef.current?.focus();
  }, [editing]);

  useEffect(() => {
    if (!editDraft || editDraft.memo.id === memo.id) return;
    operationGeneration.current += 1;
    setEditDraft(null);
    setOperationPending(false);
    setLocalError(null);
    onDirtyChange(false);
  }, [editDraft, memo.id, onDirtyChange]);

  async function performSave(save: () => Promise<boolean>) {
    const generation = ++operationGeneration.current;
    setOperationPending(true);
    setLocalError(null);
    try {
      const saved = await save();
      if (alive.current && operationGeneration.current === generation && saved) {
        setEditDraft(null);
        onDirtyChange(false);
        window.requestAnimationFrame(() => editButtonRef.current?.focus());
      }
    } catch (error) {
      if (alive.current && operationGeneration.current === generation) setLocalError(errorMessage(error));
    } finally {
      if (alive.current && operationGeneration.current === generation) setOperationPending(false);
    }
  }

  async function reloadLatest() {
    if (!onReload || locked) return;
    const generation = ++operationGeneration.current;
    setOperationPending(true);
    setLocalError(null);
    try {
      await onReload();
    } catch (error) {
      if (alive.current && operationGeneration.current === generation) setLocalError(errorMessage(error));
    } finally {
      if (alive.current && operationGeneration.current === generation) setOperationPending(false);
    }
  }

  function saveEdit() {
    if (!editDraft || locked || !isMemoContentValid(editDraft.content) || editIsStale || memo.status !== 'ACTIVE') return;
    void performSave(() => onUpdate(editDraft.memo, editDraft.content));
  }

  if (editing && editDraft) {
    return (
      <section className="memo-detail-actions" aria-labelledby={`memo-detail-edit-${memo.id}`}>
        <div className="memo-editor">
          <label id={`memo-detail-edit-${memo.id}`} htmlFor={`memo-detail-edit-content-${memo.id}`}>
            메모 수정
          </label>
          <textarea
            ref={textareaRef}
            id={`memo-detail-edit-content-${memo.id}`}
            maxLength={20_000}
            rows={6}
            value={editDraft.content}
            disabled={locked}
            onChange={(event) => {
              const content = event.target.value;
              setEditDraft({ ...editDraft, content });
              setLocalError(null);
              onDirtyChange(editDraft.memo.content !== content);
            }}
          />
          {(failure || localError) && (
            <aside className="memo-editor__warning" role="alert">
              <p>{localError ?? failure?.message}</p>
              {failure?.conflict && onReload ? (
                <button type="button" className="secondary-button" disabled={locked} onClick={() => void reloadLatest()}>
                  최신 메모 불러오기
                </button>
              ) : failure?.retry && (
                <button type="button" className="secondary-button" disabled={locked} onClick={() => void performSave(failure.retry!)}>
                  원문 저장 다시 시도
                </button>
              )}
            </aside>
          )}
          {(editIsStale || memo.status !== 'ACTIVE') && (
            <aside className="memo-editor__warning" role="status">
              <p>{memo.status !== 'ACTIVE'
                ? '휴지통으로 이동된 메모입니다. 입력한 내용은 복사해 보관할 수 있습니다.'
                : '원문이 바뀌었습니다. 위의 최신 원문과 수정 내용을 비교해 주세요.'}</p>
              {memo.status === 'ACTIVE' && (
                <button type="button" className="secondary-button" disabled={locked} onClick={() => {
                  setEditDraft({ memo, content: editDraft.content });
                  onDirtyChange(memo.content !== editDraft.content);
                  setLocalError(null);
                  textareaRef.current?.focus();
                }}>
                  내 수정 내용 유지
                </button>
              )}
            </aside>
          )}
          <div className="memo-editor__footer">
            <span>{editDraft.content.length.toLocaleString()} / 20,000</span>
            <div>
              <button
                type="button"
                className="secondary-button"
                disabled={locked}
                onClick={() => {
                  setEditDraft(null);
                  onDirtyChange(false);
                  setLocalError(null);
                  window.requestAnimationFrame(() => editButtonRef.current?.focus());
                }}
              >
                취소
              </button>
              <button
                type="button"
                className="approve-button"
                disabled={locked || editIsStale || memo.status !== 'ACTIVE' || !isMemoContentValid(editDraft.content)}
                onClick={saveEdit}
              >
                {pendingScope === `update:${memo.id}` ? '저장 중…' : '저장'}
              </button>
            </div>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="memo-detail-actions" aria-label="메모 관리">
      {memo.status === 'ACTIVE' ? (
        <div className="memo-card__actions">
          <button
            type="button"
            className="approve-button"
            disabled={busy || analysisBlocked}
            title={analysisBlocked ? '열려 있는 제안을 먼저 처리해 주세요.' : undefined}
            onClick={() => onAnalyze(memo)}
          >
            {pendingScope === `analyze:${memo.id}` ? '정리 중…' : '정리하기'}
          </button>
          <button
            ref={editButtonRef}
            type="button"
            className="secondary-button"
            disabled={busy}
            onClick={() => {
              setEditDraft({ memo, content: memo.content });
              setLocalError(null);
              onDirtyChange(false);
            }}
          >
            수정
          </button>
          <button
            type="button"
            className="danger-button"
            disabled={busy}
            onClick={() => onTrash(memo)}
          >
            {pendingScope === `trash:${memo.id}` ? '이동 중…' : '휴지통'}
          </button>
        </div>
      ) : (
        <button
          type="button"
          className="secondary-button"
          disabled={busy}
          onClick={() => onRestore(memo)}
        >
          {pendingScope === `restore:${memo.id}` ? '복원 중…' : '복원'}
        </button>
      )}
    </section>
  );
}

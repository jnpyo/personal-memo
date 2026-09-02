import { useEffect, useState } from 'react';
import type { MemoView } from '../../shared/api/types';
import { isMemoContentValid } from './memoModel';

export type MemoDetailActionsConfig = {
  busy: boolean;
  pendingScope: string | null;
  analysisBlocked: boolean;
  onUpdate: (memo: MemoView, content: string) => Promise<boolean>;
  onTrash: (memo: MemoView) => void;
  onRestore: (memo: MemoView) => void;
  onAnalyze: (memo: MemoView) => void;
  onDirtyChange: (dirty: boolean) => void;
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
}: Props) {
  const [editDraft, setEditDraft] = useState<EditDraft | null>(null);
  const editing = editDraft?.memo.id === memo.id;
  const editIsStale = editing && editDraft.memo.currentRevision !== memo.currentRevision;

  useEffect(() => () => onDirtyChange(false), [onDirtyChange]);

  useEffect(() => {
    if (!editDraft || editDraft.memo.id === memo.id) return;
    setEditDraft(null);
    onDirtyChange(false);
  }, [editDraft, memo.id, onDirtyChange]);

  async function saveEdit() {
    if (!editDraft || !isMemoContentValid(editDraft.content) || editIsStale) return;
    const saved = await onUpdate(editDraft.memo, editDraft.content);
    if (saved) {
      setEditDraft(null);
      onDirtyChange(false);
    }
  }

  if (editing && editDraft) {
    return (
      <section className="memo-detail-actions" aria-labelledby={`memo-detail-edit-${memo.id}`}>
        <div className="memo-editor">
          <label id={`memo-detail-edit-${memo.id}`} htmlFor={`memo-detail-edit-content-${memo.id}`}>
            메모 수정
          </label>
          <textarea
            id={`memo-detail-edit-content-${memo.id}`}
            maxLength={20_000}
            rows={6}
            value={editDraft.content}
            disabled={busy}
            onChange={(event) => {
              const content = event.target.value;
              setEditDraft({ ...editDraft, content });
              onDirtyChange(editDraft.memo.content !== content);
            }}
          />
          {editIsStale && (
            <p className="memo-editor__warning" role="status">
              편집 중 원문이 바뀌었습니다. 취소한 뒤 최신 원문에서 다시 수정해 주세요.
            </p>
          )}
          <div className="memo-editor__footer">
            <span>{editDraft.content.length.toLocaleString()} / 20,000</span>
            <div>
              <button
                type="button"
                className="secondary-button"
                disabled={busy}
                onClick={() => {
                  setEditDraft(null);
                  onDirtyChange(false);
                }}
              >
                취소
              </button>
              <button
                type="button"
                className="approve-button"
                disabled={busy || editIsStale || !isMemoContentValid(editDraft.content)}
                onClick={() => void saveEdit()}
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
            type="button"
            className="secondary-button"
            disabled={busy}
            onClick={() => {
              setEditDraft({ memo, content: memo.content });
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

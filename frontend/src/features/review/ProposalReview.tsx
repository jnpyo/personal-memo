import { useState } from 'react';
import type { ItemKind, TagCandidate } from '../../shared/api/types';
import type { ReviewDraft } from './reviewModel';

type Props = {
  review: ReviewDraft;
  busy: boolean;
  onChange: (review: ReviewDraft) => void;
  onApply: () => void;
  onPostpone: () => void;
  onReject: () => void;
};

const TYPE_LABEL: Record<ItemKind, string> = {
  TASK: '할 일',
  EVENT: '일정',
  INFORMATION: '정보',
  IDEA: '아이디어',
  RECORD: '기록',
};

export function ProposalReview({
  review,
  busy,
  onChange,
  onApply,
  onPostpone,
  onReject,
}: Props) {
  const [newTag, setNewTag] = useState('');

  const removeTag = (index: number) => {
    onChange({ ...review, tags: review.tags.filter((_, candidateIndex) => candidateIndex !== index) });
  };

  const addTag = () => {
    const canonicalName = newTag.trim();
    if (!canonicalName || review.tags.some((tag) => tag.canonicalName === canonicalName)) return;

    const candidate: TagCandidate = {
      existingTagId: null,
      canonicalName,
      matchedAlias: null,
      isNewProposal: true,
    };
    onChange({ ...review, tags: [...review.tags, candidate] });
    setNewTag('');
  };

  const isValid =
    review.title.trim().length > 0 &&
    review.items.length > 0 &&
    review.items.every((item) => item.title.trim().length > 0);

  return (
    <section className="review-card" aria-labelledby="review-title">
      <span className="eyebrow">REVIEW REQUIRED</span>
      <h2 id="review-title">AI 제안을 확인해 주세요</h2>
      <p className="review-note">
        아래 내용은 아직 제안입니다. 승인하기 전에는 태그, 할 일, 관계가 생성되지 않습니다.
      </p>

      <div className="review-fields">
        <label>
          제목
          <input
            value={review.title}
            disabled={busy}
            maxLength={200}
            onChange={(event) => onChange({ ...review, title: event.target.value })}
          />
        </label>

        <label>
          유형
          <select
            value={review.selectedType}
            disabled={busy}
            onChange={(event) =>
              onChange({ ...review, selectedType: event.target.value as ItemKind })
            }
          >
            {review.proposal.typeCandidates.map((candidate) => (
              <option key={candidate.value} value={candidate.value}>
                {TYPE_LABEL[candidate.value]}
              </option>
            ))}
          </select>
        </label>
      </div>

      <fieldset disabled={busy}>
        <legend>생성할 항목</legend>
        {review.items.map((item, index) => (
          <label className="item-editor" key={item.candidateId ?? `${item.kind}-${index}`}>
            <span>{TYPE_LABEL[item.kind]}</span>
            <input
              value={item.title}
              maxLength={200}
              onChange={(event) =>
                onChange({
                  ...review,
                  items: review.items.map((candidate, candidateIndex) =>
                    candidateIndex === index ? { ...candidate, title: event.target.value } : candidate,
                  ),
                })
              }
            />
          </label>
        ))}
      </fieldset>

      <fieldset disabled={busy}>
        <legend>태그 제안</legend>
        <div className="chips">
          {review.tags.map((tag, index) => (
            <button
              key={`${tag.existingTagId ?? 'new'}-${tag.canonicalName}`}
              type="button"
              onClick={() => removeTag(index)}
              aria-label={`${tag.canonicalName} 태그 제외`}
            >
              #{tag.canonicalName} <span aria-hidden="true">×</span>
            </button>
          ))}
        </div>
        <div className="add-tag">
          <input
            value={newTag}
            maxLength={100}
            placeholder="태그 직접 추가"
            aria-label="새 태그"
            onChange={(event) => setNewTag(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault();
                addTag();
              }
            }}
          />
          <button type="button" className="secondary-button" onClick={addTag}>
            추가
          </button>
        </div>
      </fieldset>

      {review.proposal.dateCandidates[0] && (
        <p className="date-candidate">
          날짜 원문 <strong>{review.proposal.dateCandidates[0].surfaceText}</strong>
          <span aria-hidden="true"> · </span>
          {review.proposal.dateCandidates[0].value ?? '날짜 미확정'}
          <span aria-hidden="true"> · </span>
          {review.proposal.dateCandidates[0].precision}
        </p>
      )}

      {review.proposal.ambiguityReasons && review.proposal.ambiguityReasons.length > 0 && (
        <p className="ambiguity-note">
          확인 필요: {review.proposal.ambiguityReasons.join(', ')}
        </p>
      )}

      <div className="review-actions">
        <button type="button" className="approve-button" disabled={busy || !isValid} onClick={onApply}>
          {busy ? '처리 중…' : '선택한 항목 승인'}
        </button>
        <button type="button" className="secondary-button" disabled={busy} onClick={onPostpone}>
          나중에 검토
        </button>
        <button type="button" className="danger-button" disabled={busy} onClick={onReject}>
          제안 거절
        </button>
      </div>
    </section>
  );
}

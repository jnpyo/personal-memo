import { useEffect, useRef, useState } from 'react';
import type { DateCandidate, ItemKind, TagCandidate } from '../../shared/api/types';
import {
  addManualItem,
  changeItemDue,
  changeItemDueValue,
  changeItemKind,
  changeItemTitle,
  changeReviewTitle,
  changeSelectedType,
  createCustomDateOnly,
  isValidDue,
  isValidReviewDraft,
  ITEM_KINDS,
  removeReviewItem,
  usableDateCandidates,
  type ReviewDraft,
} from './reviewModel';

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

function sameDate(left: DateCandidate, right: DateCandidate): boolean {
  return (
    left.surfaceText === right.surfaceText &&
    left.value === right.value &&
    left.precision === right.precision &&
    left.timeSpecified === right.timeSpecified
  );
}

function dateCandidateLabel(candidate: DateCandidate): string {
  const interpreted = candidate.value ?? '날짜 미확정';
  return `${candidate.surfaceText} → ${interpreted} (${candidate.precision})`;
}

export function ProposalReview({
  review,
  busy,
  onChange,
  onApply,
  onPostpone,
  onReject,
}: Props) {
  const [newTag, setNewTag] = useState('');
  const headingRef = useRef<HTMLHeadingElement>(null);
  const representativeTypeRef = useRef<HTMLSelectElement>(null);
  const itemTitleRefs = useRef<Array<HTMLInputElement | null>>([]);
  const addItemButtonRef = useRef<HTMLButtonElement>(null);
  const pendingItemFocus = useRef<number | 'ADD_OR_TYPE' | null>(null);
  const dateCandidates = usableDateCandidates(review.proposal);

  useEffect(() => {
    const heading = headingRef.current;
    if (!heading) return;
    heading.focus({ preventScroll: true });
    heading.scrollIntoView({ block: 'start', behavior: 'auto' });
  }, [review.proposalId]);

  useEffect(() => {
    const pending = pendingItemFocus.current;
    if (pending === null) return;

    const addButton = addItemButtonRef.current;
    const target =
      pending === 'ADD_OR_TYPE'
        ? review.selectedType !== null && addButton && !addButton.disabled
          ? addButton
          : representativeTypeRef.current
        : itemTitleRefs.current[pending];
    pendingItemFocus.current = null;
    target?.focus({ preventScroll: true });
    target?.scrollIntoView({ block: 'center', behavior: 'auto' });
  }, [review.items, review.selectedType]);

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

  const isValid = isValidReviewDraft(review);

  return (
    <section className="review-card" aria-labelledby="review-title">
      <span className="eyebrow">REVIEW REQUIRED</span>
      <h2 id="review-title" ref={headingRef} tabIndex={-1}>
        AI 제안을 확인해 주세요
      </h2>
      <p className="review-note">
        아래 내용은 아직 제안입니다. 승인하기 전에는 태그, 할 일, 관계가 생성되지 않습니다.
      </p>

      <div className="review-fields">
        <label>
          대표 제목
          <input
            value={review.title}
            disabled={busy}
            maxLength={200}
            onChange={(event) => onChange(changeReviewTitle(review, event.target.value))}
          />
        </label>

        <label>
          대표 유형
          <select
            ref={representativeTypeRef}
            value={review.selectedType ?? ''}
            disabled={busy}
            aria-describedby={review.selectedType === null ? 'representative-type-help' : undefined}
            onChange={(event) => {
              if (event.target.value) {
                onChange(changeSelectedType(review, event.target.value as ItemKind));
              }
            }}
          >
            <option value="" disabled>
              유형을 선택하세요
            </option>
            {ITEM_KINDS.map((kind) => (
              <option key={kind} value={kind}>
                {TYPE_LABEL[kind]}
              </option>
            ))}
          </select>
          {review.selectedType === null && (
            <span id="representative-type-help" className="field-help">
              분석 결과가 유형을 확정하지 못했습니다. 승인할 유형을 직접 선택해 주세요.
            </span>
          )}
        </label>
      </div>

      <fieldset
        className="review-items"
        disabled={busy}
        aria-describedby="review-items-help"
      >
        <legend>생성할 항목</legend>
        {review.items.length === 0 && (
          <p className="review-items__empty">
            아직 생성할 항목이 없습니다. 대표 유형을 선택한 뒤 필요한 항목을 직접 추가해 주세요.
          </p>
        )}
        {review.items.map((item, index) => {
          const matchedCandidate = item.due
            ? dateCandidates.findIndex((candidate) => sameDate(candidate, item.due!))
            : -1;
          const dueChoice = item.due
            ? matchedCandidate >= 0
              ? String(matchedCandidate)
              : 'custom'
            : 'none';
          const dueIsValid = item.due === null || isValidDue(item.due);
          const dateHelpId = `item-${index}-date-help`;

          return (
            <fieldset className="item-editor" key={item.candidateId ?? `${item.kind}-${index}`}>
              <legend>항목 {index + 1}</legend>
              <div className="item-editor__toolbar">
                <button
                  type="button"
                  className="item-remove-button"
                  aria-label={`항목 ${index + 1} 제거`}
                  onClick={() => {
                    const nextReview = removeReviewItem(review, index);
                    if (nextReview === review) return;
                    pendingItemFocus.current =
                      nextReview.items.length === 0
                        ? 'ADD_OR_TYPE'
                        : Math.min(index, nextReview.items.length - 1);
                    onChange(nextReview);
                  }}
                >
                  항목 제거
                </button>
              </div>
              <div className="item-editor__fields">
                <label>
                  유형
                  <select
                    aria-label={`항목 ${index + 1} 유형`}
                    value={item.kind}
                    onChange={(event) =>
                      onChange(changeItemKind(review, index, event.target.value as ItemKind))
                    }
                  >
                    {ITEM_KINDS.map((kind) => (
                      <option key={kind} value={kind}>
                        {TYPE_LABEL[kind]}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  제목
                  <input
                    ref={(element) => {
                      itemTitleRefs.current[index] = element;
                    }}
                    aria-label={`항목 ${index + 1} 제목`}
                    value={item.title}
                    maxLength={200}
                    onChange={(event) =>
                      onChange(changeItemTitle(review, index, event.target.value))
                    }
                  />
                </label>
              </div>

              {item.kind === 'TASK' && (
                <div className="task-date-editor">
                  <label>
                    마감 날짜
                    <select
                      aria-describedby={dateHelpId}
                      value={dueChoice}
                      onChange={(event) => {
                        const choice = event.target.value;
                        if (choice === 'none') {
                          onChange(changeItemDue(review, index, null));
                        } else if (choice === 'custom') {
                          onChange(changeItemDue(review, index, createCustomDateOnly()));
                        } else {
                          onChange(changeItemDue(review, index, dateCandidates[Number(choice)]));
                        }
                      }}
                    >
                      <option value="none">날짜 포함 안 함</option>
                      {dateCandidates.map((candidate, candidateIndex) => (
                        <option key={`${candidate.precision}-${candidateIndex}`} value={candidateIndex}>
                          {dateCandidateLabel(candidate)}
                        </option>
                      ))}
                      <option value="custom">날짜 직접 입력</option>
                    </select>
                  </label>

                  {item.due?.precision === 'DATE_ONLY' && (
                    <label>
                      확정 날짜
                      <input
                        type="date"
                        value={item.due.value ?? ''}
                        aria-invalid={!dueIsValid}
                        aria-describedby={dateHelpId}
                        onChange={(event) =>
                          onChange(changeItemDueValue(review, index, event.target.value))
                        }
                      />
                    </label>
                  )}

                  {(item.due?.precision === 'EXACT_TIME' ||
                    item.due?.precision === 'RELATIVE_EXACT') && (
                    <label>
                      확정 시각 (UTC offset 포함)
                      <input
                        type="text"
                        value={item.due.value ?? ''}
                        aria-invalid={!dueIsValid}
                        aria-describedby={dateHelpId}
                        placeholder="2026-11-25T18:00:00+09:00"
                        onChange={(event) =>
                          onChange(changeItemDueValue(review, index, event.target.value))
                        }
                      />
                    </label>
                  )}

                  <p
                    id={dateHelpId}
                    className={dueIsValid ? 'field-help' : 'field-error'}
                    role={dueIsValid ? undefined : 'alert'}
                  >
                    {dueIsValid
                      ? '각 할 일의 날짜는 별도로 선택되며, 날짜만 있는 값은 현지 달력 날짜로 보존됩니다.'
                      : '유효한 날짜를 입력하거나 날짜 포함 안 함을 선택해 주세요.'}
                  </p>
                </div>
              )}
            </fieldset>
          );
        })}
        <div className="review-items__actions">
          <button
            ref={addItemButtonRef}
            type="button"
            className="secondary-button"
            disabled={review.selectedType === null || review.items.length >= 3}
            aria-describedby="review-items-help"
            onClick={() => {
              const nextReview = addManualItem(review);
              if (nextReview === review) return;
              pendingItemFocus.current = nextReview.items.length - 1;
              onChange(nextReview);
            }}
          >
            항목 직접 추가
          </button>
          <span id="review-items-help" aria-live="polite">
            {review.selectedType === null
              ? '대표 유형을 먼저 선택해 주세요.'
              : `${review.items.length}/3개 항목`}
          </span>
        </div>
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

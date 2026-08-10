import { useEffect, useRef, useState } from 'react';
import type { DateCandidate, ItemKind, TagCandidate } from '../../shared/api/types';
import { FeedbackBanner, type Feedback } from '../../shared/ui/FeedbackBanner';
import {
  addManualItem,
  changeItemDue,
  changeItemDueValue,
  changeItemKind,
  changeItemTitle,
  changeReviewTitle,
  changeSelectedType,
  createCustomDateOnly,
  isItemKind,
  isValidDue,
  isValidReviewDraft,
  ITEM_KINDS,
  preferredItemKind,
  removeReviewItem,
  requiresExplicitDateMapping,
  sameDateCandidate,
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
  onTransientDirtyChange: (dirty: boolean) => void;
  feedback: Feedback | null;
  retryScope?: string;
  retryLabel?: string;
  onRetry?: () => void;
  onDismissFeedback: () => void;
};

type MainReviewStep = 'CONFIRM' | 'ALTERNATIVES' | 'EDIT';
type ReviewStep = MainReviewStep | 'REJECT_CONFIRM';

const TYPE_LABEL: Record<ItemKind, string> = {
  TASK: '할 일',
  EVENT: '일정',
  INFORMATION: '정보',
  IDEA: '아이디어',
  RECORD: '기록',
};

const TYPE_DESCRIPTION: Record<ItemKind, string> = {
  TASK: '해야 할 일이나 마감이 있는 내용',
  EVENT: '특정 날짜나 시간에 예정된 일정',
  INFORMATION: '나중에 다시 찾을 지식이나 정보',
  IDEA: '떠오른 생각이나 발전시킬 제안',
  RECORD: '경험하거나 확인한 사실의 기록',
};

const DATE_PRECISION_LABEL: Record<DateCandidate['precision'], string> = {
  EXACT_TIME: '정확한 시각',
  DATE_ONLY: '날짜만',
  RELATIVE_EXACT: '계산된 시각',
  APPROXIMATE: '대략적인 날짜',
  UNKNOWN: '날짜 미확정',
};

function dateCandidateLabel(candidate: DateCandidate): string {
  const interpreted = candidate.value ?? '날짜 미확정';
  return `${candidate.surfaceText} → ${interpreted} (${DATE_PRECISION_LABEL[candidate.precision]})`;
}

function suggestedType(review: ReviewDraft): ItemKind | null {
  return preferredItemKind(review.proposal);
}

function initialStep(review: ReviewDraft): MainReviewStep {
  return suggestedType(review) && isValidReviewDraft(review) && !requiresExplicitDateMapping(review)
    ? 'CONFIRM'
    : 'ALTERNATIVES';
}

function orderedTypeOptions(review: ReviewDraft): ItemKind[] {
  const ranked = [...review.proposal.typeCandidates]
    .sort((left, right) => right.score - left.score)
    .map((candidate) => candidate.value)
    .filter(isItemKind);
  return [...new Set([...ranked, ...ITEM_KINDS])];
}

function prepareTypeForEditing(review: ReviewDraft, type: ItemKind): ReviewDraft {
  const changed = changeSelectedType(review, type);
  return changed.items.length === 0 ? addManualItem(changed) : changed;
}

function dueSummary(due: DateCandidate | null): string | null {
  if (!due) return null;
  const source = due.surfaceText.trim();
  const interpreted = due.value?.trim() ?? '';
  if (source && interpreted && source !== interpreted) return `${source} → ${interpreted}`;
  return interpreted || source || null;
}

export function ProposalReview({
  review,
  busy,
  onChange,
  onApply,
  onPostpone,
  onReject,
  onTransientDirtyChange,
  feedback,
  retryScope,
  retryLabel,
  onRetry,
  onDismissFeedback,
}: Props) {
  const [newTag, setNewTag] = useState('');
  const [open, setOpen] = useState(true);
  const [step, setStep] = useState<ReviewStep>(() => initialStep(review));
  const [draftChanged, setDraftChanged] = useState(false);
  const previousStep = useRef<MainReviewStep>(initialStep(review));
  const dialogRef = useRef<HTMLDialogElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const resumeButtonRef = useRef<HTMLButtonElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const stepHeadingRef = useRef<HTMLHeadingElement>(null);
  const focusStepHeading = useRef(false);
  const scrollRef = useRef<HTMLElement>(null);
  const representativeTypeRef = useRef<HTMLSelectElement>(null);
  const itemTitleRefs = useRef<Array<HTMLInputElement | null>>([]);
  const addItemButtonRef = useRef<HTMLButtonElement>(null);
  const pendingItemFocus = useRef<number | 'ADD_OR_TYPE' | null>(null);
  const dateCandidates = usableDateCandidates(review.proposal);
  const datesNeedingReview = review.proposal.schemaVersion === '2'
    ? review.proposal.dateCandidates.filter(
        (date) =>
          date.candidateId === null ||
          !review.proposal.itemCandidates.some(
            (item) => item.dueDateCandidateId === date.candidateId,
          ),
      )
    : review.proposal.dateCandidates.filter(
        (date) => !dateCandidates.some((candidate) => sameDateCandidate(candidate, date)),
      );
  const primaryType = suggestedType(review);
  const isValid = isValidReviewDraft(review);
  const hasPendingTag = newTag.trim().length > 0;
  const canApply = isValid && !hasPendingTag;
  const [retryOperation, retryProposalId] = retryScope?.split(':') ?? [];
  const retryMatchesReview = retryProposalId === review.proposalId;
  const canShowRetry = !busy && retryMatchesReview && (
    (retryOperation === 'apply' && canApply && (step === 'CONFIRM' || step === 'EDIT')) ||
    (retryOperation === 'postpone' && !hasPendingTag && step !== 'REJECT_CONFIRM') ||
    (retryOperation === 'reject' && step === 'REJECT_CONFIRM')
  );

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;

    if (!open) {
      const frame = window.requestAnimationFrame(() => {
        resumeButtonRef.current?.focus({ preventScroll: true });
        resumeButtonRef.current?.scrollIntoView({ block: 'center', behavior: 'auto' });
      });
      return () => window.cancelAnimationFrame(frame);
    }

    openerRef.current = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    if (!dialog.open) dialog.showModal();

    return () => {
      if (dialog.open) dialog.close();
      const opener = openerRef.current;
      if (opener?.isConnected) opener.focus({ preventScroll: true });
    };
  }, [open]);

  useEffect(() => {
    if (!open) {
      focusStepHeading.current = false;
      return;
    }
    const target = focusStepHeading.current ? stepHeadingRef.current : headingRef.current;
    focusStepHeading.current = true;
    const frame = window.requestAnimationFrame(() => {
      if (scrollRef.current) scrollRef.current.scrollTop = 0;
      target?.focus({ preventScroll: true });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [open, step]);

  useEffect(
    () => () => onTransientDirtyChange(false),
    [onTransientDirtyChange],
  );

  useEffect(() => {
    const pending = pendingItemFocus.current;
    if (pending === null || step !== 'EDIT') return;

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
  }, [review.items, review.selectedType, step]);

  const changeDraft = (nextReview: ReviewDraft) => {
    setDraftChanged(true);
    onChange(nextReview);
  };

  const removeTag = (index: number) => {
    changeDraft({ ...review, tags: review.tags.filter((_, candidateIndex) => candidateIndex !== index) });
  };

  const addTag = () => {
    const canonicalName = newTag.trim();
    if (!canonicalName) return;
    if (review.tags.some((tag) => tag.canonicalName === canonicalName)) {
      setNewTag('');
      onTransientDirtyChange(false);
      return;
    }

    const candidate: TagCandidate = {
      existingTagId: null,
      canonicalName,
      matchedAlias: null,
      isNewProposal: true,
    };
    changeDraft({ ...review, tags: [...review.tags, candidate] });
    setNewTag('');
    onTransientDirtyChange(false);
  };

  const moveToEdit = (nextReview: ReviewDraft = review) => {
    if (nextReview !== review) changeDraft(nextReview);
    setStep('EDIT');
  };

  const askToReject = () => {
    if (step !== 'REJECT_CONFIRM') previousStep.current = step;
    setStep('REJECT_CONFIRM');
  };

  const lifecycleActions = (
    <div className="review-lifecycle-actions">
      <button type="button" className="secondary-button" disabled={busy} onClick={onPostpone}>
        나중에 검토
      </button>
      <button type="button" className="text-danger-button" disabled={busy} onClick={askToReject}>
        이 제안 사용하지 않기
      </button>
    </div>
  );

  const dateReviewNotice = datesNeedingReview.length > 0 && (
    <div className="review-note" role="status">
      <p>
        AI가 다음 날짜를 특정 할 일의 마감으로 안전하게 연결하지 못해 자동 적용하지 않습니다.
      </p>
      <ul>
        {datesNeedingReview.map((candidate, index) => (
          <li key={candidate.candidateId ?? `unassigned-date-${index}`}>
            {dateCandidateLabel(candidate)}
          </li>
        ))}
      </ul>
      <p>필요하면 각 할 일에서 정확한 날짜를 고르거나 마감 없음을 선택해 주세요.</p>
    </div>
  );

  return (
    <>
      {!open && (
        <aside className="postponed-card review-resume-card" aria-label="검토 대기 중인 AI 제안">
          <div>
            <span className="eyebrow">REVIEW WAITING</span>
            <p>AI 제안 검토가 열려 있습니다. 아직 어떤 항목도 적용되지 않았습니다.</p>
          </div>
          <button
            ref={resumeButtonRef}
            type="button"
            className="secondary-button"
            onClick={() => setOpen(true)}
          >
            검토 팝업 열기
          </button>
        </aside>
      )}

      <dialog
        ref={dialogRef}
        className="review-dialog"
        aria-labelledby="review-title"
        aria-busy={busy}
        onCancel={() => setOpen(false)}
        onClose={() => setOpen(false)}
      >
        <section ref={scrollRef} className="review-card review-dialog__surface">
          <header className="review-dialog__header">
            <div>
              <span className="eyebrow">AI PROPOSAL · NOT APPLIED</span>
              <h2 id="review-title" ref={headingRef} tabIndex={-1}>
                AI 제안을 확인해 주세요
              </h2>
            </div>
            <button
              type="button"
              className="review-dialog__close"
              aria-label="검토 팝업 닫기"
              onClick={() => setOpen(false)}
            >
              <span aria-hidden="true">×</span>
            </button>
          </header>

          <FeedbackBanner
            feedback={feedback}
            retryLabel={canShowRetry ? retryLabel : undefined}
            onRetry={canShowRetry ? onRetry : undefined}
            onDismiss={onDismissFeedback}
          />

          {dateReviewNotice}

          {step === 'CONFIRM' && (
            <div className="review-confirmation">
              <div className="review-intro">
                <h3 ref={stepHeadingRef} tabIndex={-1}>
                  AI는 이렇게 이해했어요.
                </h3>
                <p>이 내용이 맞다면 한 번의 승인으로 적용할 수 있습니다.</p>
              </div>

              <dl className="proposal-summary">
                <div>
                  <dt>분류</dt>
                  <dd>{review.selectedType ? TYPE_LABEL[review.selectedType] : '확인 필요'}</dd>
                </div>
                <div>
                  <dt>제목</dt>
                  <dd>{review.title}</dd>
                </div>
                <div>
                  <dt>생성 예정</dt>
                  <dd>{review.items.length}개 항목</dd>
                </div>
                <div>
                  <dt>태그</dt>
                  <dd>
                    {review.tags.length > 0
                      ? review.tags.map((tag) => `#${tag.canonicalName.trim()}`).join(' ')
                      : '없음'}
                  </dd>
                </div>
              </dl>

              {review.items.length > 0 && (
                <ul className="proposal-summary__items" aria-label="AI가 생성하려는 항목">
                  {review.items.map((item, index) => {
                    const due = dueSummary(item.due);
                    return (
                      <li key={item.candidateId ?? `${item.kind}-${index}`}>
                        <span>{TYPE_LABEL[item.kind]}</span>
                        <strong>{item.title}</strong>
                        {item.kind === 'TASK' && <small>마감 {due ?? '없음'}</small>}
                      </li>
                    );
                  })}
                </ul>
              )}

              <p className="review-approval-note">
                아직 제안일 뿐입니다. 아래의 ‘예’를 눌러야 선택한 항목과 태그가 만들어집니다.
              </p>

              <div className="review-choice-actions" role="group" aria-label="AI 제안 승인 여부">
                <button
                  type="button"
                  className="approve-button"
                  disabled={busy || !canApply}
                  onClick={onApply}
                >
                  {busy ? '적용 중…' : '예, 이대로 적용'}
                </button>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={busy}
                  onClick={() => setStep('ALTERNATIVES')}
                >
                  아니오, 다른 경우 보기
                </button>
              </div>
              {lifecycleActions}
            </div>
          )}

          {step === 'ALTERNATIVES' && (
            <div className="review-alternatives">
              <div className="review-intro">
                <h3 ref={stepHeadingRef} tabIndex={-1}>
                  {primaryType
                    ? '어떤 부분이 다른가요?'
                    : 'AI가 유형을 확정하지 못했어요.'}
                </h3>
                <p>
                  {primaryType
                    ? '현재 유형을 유지해 내용을 고치거나, 메모에 더 가까운 유형을 골라 주세요.'
                    : '아래에서 메모에 가장 가까운 유형을 직접 골라 주세요.'}
                </p>
              </div>

              {review.selectedType && (
                <button
                  type="button"
                  className="review-content-edit-choice"
                  disabled={busy}
                  onClick={() => moveToEdit(
                    review.items.length === 0
                      ? prepareTypeForEditing(review, review.selectedType!)
                      : review,
                  )}
                >
                  <span>유형은 맞아요</span>
                  <strong>{TYPE_LABEL[review.selectedType]}로 두고 날짜·내용 확인</strong>
                </button>
              )}

              <fieldset className="review-type-picker" disabled={busy}>
                <legend>{review.selectedType ? '다른 가능한 유형' : '가능한 유형'}</legend>
                <div className="review-type-options">
                  {orderedTypeOptions(review)
                    .filter((type) => type !== review.selectedType)
                    .map((type) => {
                      const candidateIndex = review.proposal.typeCandidates.findIndex(
                        (candidate) => candidate.value === type,
                      );
                      return (
                        <button
                          key={type}
                          type="button"
                          className="review-type-option"
                          aria-label={`${TYPE_LABEL[type]} 유형 선택`}
                          onClick={() => moveToEdit(prepareTypeForEditing(review, type))}
                        >
                          <span>
                            {TYPE_LABEL[type]}
                            {candidateIndex >= 0 && (
                              <small>{type === primaryType ? 'AI 추천' : 'AI 후보'}</small>
                            )}
                          </span>
                          <strong>{TYPE_DESCRIPTION[type]}</strong>
                        </button>
                      );
                    })}
                </div>
              </fieldset>

              <p className="review-approval-note">
                유형을 고른 뒤 제목, 날짜, 태그와 생성할 항목을 마지막으로 확인합니다.
              </p>

              <div className="review-navigation-actions">
                {primaryType &&
                  !draftChanged &&
                  isValidReviewDraft(review) &&
                  !requiresExplicitDateMapping(review) && (
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={busy}
                    onClick={() => setStep('CONFIRM')}
                  >
                    AI 추천으로 돌아가기
                  </button>
                )}
              </div>
              {lifecycleActions}
            </div>
          )}

          {step === 'EDIT' && (
            <div className="review-editor">
              <div className="review-intro review-intro--with-action">
                <div>
                  <h3 ref={stepHeadingRef} tabIndex={-1}>
                    선택한 내용을 확인해 주세요.
                  </h3>
                  <p>필요한 부분만 고친 뒤 마지막 버튼으로 승인합니다.</p>
                </div>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={busy}
                  onClick={() => setStep('ALTERNATIVES')}
                >
                  다른 유형 고르기
                </button>
              </div>

              <p className="review-note">
                아래 내용은 아직 제안입니다. 승인 전에는 선택한 항목과 태그가 생성되지 않습니다.
              </p>

              <div className="review-fields">
                <label>
                  대표 제목
                  <input
                    value={review.title}
                    disabled={busy}
                    maxLength={200}
                    onChange={(event) => changeDraft(changeReviewTitle(review, event.target.value))}
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
                        changeDraft(prepareTypeForEditing(review, event.target.value as ItemKind));
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
                    ? dateCandidates.findIndex((candidate) =>
                        sameDateCandidate(candidate, item.due!),
                      )
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
                            changeDraft(nextReview);
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
                              changeDraft(changeItemKind(review, index, event.target.value as ItemKind))
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
                              changeDraft(changeItemTitle(review, index, event.target.value))
                            }
                          />
                        </label>
                      </div>

                      {item.kind === 'TASK' && (
                        <div className="task-date-editor">
                          <label>
                            마감 날짜
                            <select
                              aria-label={`항목 ${index + 1} 마감 날짜`}
                              aria-describedby={dateHelpId}
                              value={dueChoice}
                              onChange={(event) => {
                                const choice = event.target.value;
                                if (choice === 'none') {
                                  changeDraft(changeItemDue(review, index, null));
                                } else if (choice === 'custom') {
                                  changeDraft(changeItemDue(review, index, createCustomDateOnly()));
                                } else {
                                  changeDraft(changeItemDue(review, index, dateCandidates[Number(choice)]));
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
                                  changeDraft(changeItemDueValue(review, index, event.target.value))
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
                                  changeDraft(changeItemDueValue(review, index, event.target.value))
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
                      changeDraft(nextReview);
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
                    aria-describedby={hasPendingTag ? 'pending-tag-help' : undefined}
                    onChange={(event) => {
                      const value = event.target.value;
                      setNewTag(value);
                      setDraftChanged(true);
                      onTransientDirtyChange(value.trim().length > 0);
                    }}
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
                {hasPendingTag && (
                  <p id="pending-tag-help" className="field-help" role="status">
                    입력한 태그를 반영하려면 ‘추가’를 누르거나 입력을 비워 주세요.
                  </p>
                )}
              </fieldset>

              {review.proposal.ambiguityReasons && review.proposal.ambiguityReasons.length > 0 && (
                <p className="ambiguity-note">
                  확인 필요: {review.proposal.ambiguityReasons.join(', ')}
                </p>
              )}

              <div className="review-actions">
                <button
                  type="button"
                  className="approve-button"
                  disabled={busy || !canApply}
                  onClick={onApply}
                >
                  {busy ? '처리 중…' : '수정한 내용 승인·적용'}
                </button>
                <button type="button" className="secondary-button" disabled={busy} onClick={onPostpone}>
                  나중에 검토
                </button>
                <button type="button" className="text-danger-button" disabled={busy} onClick={askToReject}>
                  이 제안 사용하지 않기
                </button>
              </div>
            </div>
          )}

          {step === 'REJECT_CONFIRM' && (
            <div className="review-reject-confirmation">
              <div className="review-intro">
                <h3 ref={stepHeadingRef} tabIndex={-1}>
                  이 AI 제안만 사용하지 않을까요?
                </h3>
                <p>제안을 버려도 원본 메모와 revision은 그대로 보존됩니다.</p>
              </div>
              <div className="review-reject-actions">
                <button
                  type="button"
                  className="danger-button"
                  disabled={busy}
                  onClick={onReject}
                >
                  {busy ? '처리 중…' : '예, 제안만 버리기'}
                </button>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={busy}
                  onClick={() => setStep(previousStep.current)}
                >
                  아니요, 검토로 돌아가기
                </button>
              </div>
            </div>
          )}
        </section>
      </dialog>
    </>
  );
}

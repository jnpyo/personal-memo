import { useEffect, useRef, useState } from 'react';
import type {
  DateCandidate,
  ItemKind,
  ProposalEventScheduleCandidate,
  RelationReviewCandidate,
  TagCandidate,
} from '../../shared/api/types';
import { FeedbackBanner, type Feedback } from '../../shared/ui/FeedbackBanner';
import {
  addManualItem,
  changeItemDue,
  changeItemDueValue,
  changeItemEventSchedule,
  changeItemEventScheduleField,
  changeItemEventScheduleMode,
  changeItemKind,
  changeItemTitle,
  changeReviewTitle,
  changeRelationSelection,
  changeSelectedType,
  createCustomDateOnly,
  createCustomTimedEventSchedule,
  eventScheduleFromDateCandidate,
  eventScheduleFromProposalCandidate,
  isItemKind,
  isValidDue,
  isValidEventSchedule,
  isValidReviewDraft,
  isRelationSelectionReady,
  isRelationSourceApplied,
  ITEM_KINDS,
  preferredItemKind,
  referencedProposalDateCandidateIds,
  removeReviewItem,
  requiresExplicitDateMapping,
  sameDateCandidate,
  sameEventScheduleCandidate,
  usableDateCandidates,
  type ReviewDraft,
} from './reviewModel';

type Props = {
  review: ReviewDraft;
  sourceTimeZone?: string | null;
  sourceTimeZoneError?: string | null;
  relationReviewCandidates: RelationReviewCandidate[] | null;
  relationReviewLoading: boolean;
  relationReviewError: string | null;
  busy: boolean;
  onChange: (review: ReviewDraft) => void;
  onApply: () => void;
  onPostpone: () => void;
  onReject: () => void;
  onRetryRelationReview: () => void;
  onRetrySourceTimeZone?: () => void;
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

const RELATION_TYPE_LABEL: Record<ReviewDraft['proposal']['relationCandidates'][number]['relationType'], string> = {
  RELATED_TO: '관련 있음',
  CONTINUES: '이어짐',
  DEPENDS_ON: '의존함',
  REFERENCES: '참조함',
};

const RELATION_TARGET_TYPE_LABEL: Record<RelationReviewCandidate['targetType'], string> = {
  MEMO: '메모',
  TAG: '태그',
};

function dateCandidateLabel(candidate: DateCandidate): string {
  const interpreted = candidate.value ?? '날짜 미확정';
  return `${candidate.surfaceText} → ${interpreted} (${DATE_PRECISION_LABEL[candidate.precision]})`;
}

function suggestedType(review: ReviewDraft): ItemKind | null {
  return preferredItemKind(review.proposal);
}

const CLOUD_EVIDENCE_FIELDS = [
  'cloudTransferMode',
  'cloudGatewayVersion',
  'cloudProviderId',
  'cloudModelVersion',
  'cloudConsentPolicyVersion',
  'cloudOutcome',
  'cloudToolCalls',
  'cloudMutationCalls',
  'cloudResolvedFields',
  'receivedRoutingPolicyVersion',
  'receivedRoutingReasons',
] as const;
const CLOUD_EVIDENCE_FIELD_SET = new Set<string>(CLOUD_EVIDENCE_FIELDS);

type CloudReviewDisposition = 'CONCISE' | 'CONSENT_REQUIRED' | 'DETAILED';

const RESERVED_CLOUD_DESCRIPTOR_VALUES = new Set(['none', 'legacy-unknown', 'unavailable']);
const LOCAL_MACHINE_MEMO_CONTENT = 'LOCAL_MACHINE_MEMO_CONTENT';

function hasOwn(metadata: Record<string, unknown>, field: string): boolean {
  return Object.prototype.hasOwnProperty.call(metadata, field);
}

function boundedCloudText(value: unknown): string | null {
  if (typeof value !== 'string' || value.trim().length === 0 || [...value].length > 64) return null;
  return value;
}

function usableDescriptorValue(value: string, allowNone = false): boolean {
  return !RESERVED_CLOUD_DESCRIPTOR_VALUES.has(value) || (allowNone && value === 'none');
}

function boundedCloudTextList(value: unknown): string[] | null {
  if (!Array.isArray(value)) return null;
  const entries = value.map(boundedCloudText);
  return entries.every((entry): entry is string => entry !== null) ? entries : null;
}

function cloudReviewDisposition(review: ReviewDraft): CloudReviewDisposition {
  const metadata = review.proposal.providerMetadata;
  const evidenceFields = Object.keys(metadata).filter(
    (field) => field.startsWith('cloud') || field.startsWith('receivedRouting'),
  );
  if (evidenceFields.length === 0) return 'CONCISE';
  if (evidenceFields.some((field) => !CLOUD_EVIDENCE_FIELD_SET.has(field))) return 'DETAILED';

  if (!CLOUD_EVIDENCE_FIELDS.every((field) => hasOwn(metadata, field))) return 'DETAILED';

  const transferMode = boundedCloudText(metadata.cloudTransferMode);
  const gatewayVersion = boundedCloudText(metadata.cloudGatewayVersion);
  const providerId = boundedCloudText(metadata.cloudProviderId);
  const modelVersion = boundedCloudText(metadata.cloudModelVersion);
  const consentPolicyVersion = boundedCloudText(metadata.cloudConsentPolicyVersion);
  const outcome = boundedCloudText(metadata.cloudOutcome);
  const receivedRoutingPolicyVersion = boundedCloudText(metadata.receivedRoutingPolicyVersion);
  const receivedRoutingReasons = boundedCloudTextList(metadata.receivedRoutingReasons);
  const cloudResolvedFields = boundedCloudTextList(metadata.cloudResolvedFields);
  if (
    !transferMode ||
    !gatewayVersion ||
    !providerId ||
    !modelVersion ||
    !consentPolicyVersion ||
    !outcome ||
    metadata.cloudToolCalls !== 0 ||
    metadata.cloudMutationCalls !== 0 ||
    !receivedRoutingPolicyVersion ||
    receivedRoutingPolicyVersion !== metadata.routingPolicyVersion ||
    receivedRoutingReasons === null ||
    cloudResolvedFields === null ||
    cloudResolvedFields.length !== 0
  ) {
    return 'DETAILED';
  }

  if (transferMode === 'NOT_REQUIRED') {
    return outcome === 'NOT_REQUIRED' &&
      gatewayVersion === 'none' &&
      providerId === 'none' &&
      modelVersion === 'none' &&
      consentPolicyVersion === 'none'
      ? 'CONCISE'
      : 'DETAILED';
  }

  if (
    transferMode !== 'NO_NETWORK' &&
    transferMode !== LOCAL_MACHINE_MEMO_CONTENT &&
    transferMode !== 'EXTERNAL_MEMO_CONTENT'
  ) {
    return 'DETAILED';
  }
  if (
    !usableDescriptorValue(gatewayVersion) ||
    !usableDescriptorValue(providerId) ||
    !usableDescriptorValue(modelVersion, true) ||
    !usableDescriptorValue(consentPolicyVersion)
  ) {
    return 'DETAILED';
  }

  if (outcome === 'SUCCESS') return 'CONCISE';
  if (outcome === 'CONSENT_REQUIRED' && transferMode === 'EXTERNAL_MEMO_CONTENT') {
    return 'CONSENT_REQUIRED';
  }
  return 'DETAILED';
}

function requiresDetailedCloudReview(review: ReviewDraft): boolean {
  return cloudReviewDisposition(review) !== 'CONCISE';
}

function isSuccessfulLocalModelProposal(review: ReviewDraft): boolean {
  return cloudReviewDisposition(review) === 'CONCISE' &&
    review.proposal.providerMetadata.cloudTransferMode === LOCAL_MACHINE_MEMO_CONTENT &&
    review.proposal.providerMetadata.cloudOutcome === 'SUCCESS';
}

function initialStep(review: ReviewDraft): MainReviewStep {
  if (
    review.proposal.relationCandidates.length > 0 ||
    (review.proposal.schemaVersion === '3' &&
      review.proposal.itemCandidates.some((item) => item.eventScheduleCandidates.length > 0))
  ) {
    return 'EDIT';
  }
  return suggestedType(review) &&
    isValidReviewDraft(review) &&
    !requiresExplicitDateMapping(review) &&
    !requiresDetailedCloudReview(review)
    ? 'CONFIRM'
    : 'ALTERNATIVES';
}

export function shouldFocusStepHeadingOnInitialOpen(review: ReviewDraft): boolean {
  return initialStep(review) === 'EDIT';
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

function eventScheduleSummary(item: ReviewDraft['items'][number]): string {
  const schedule = item.eventSchedule;
  if (!schedule) return '시간 미정 · 외부 공유 대상 아님';
  const end = schedule.end.trim();
  if (schedule.mode === 'ALL_DAY') {
    return end ? `종일 ${schedule.start} ~ ${end} 전` : `종일 ${schedule.start}`;
  }
  return end ? `${schedule.start} ~ ${end}` : schedule.start;
}

function eventScheduleCandidateEndLabel(
  candidate: ProposalEventScheduleCandidate,
  review: ReviewDraft,
): string | null {
  if (!candidate.end) return null;
  const end = review.proposal.dateCandidates.find(
    (date) => date.candidateId === candidate.end!.dateCandidateId,
  );
  if (!end) return null;
  const boundary = candidate.end.boundary === 'INCLUSIVE_THROUGH_VALUE'
    ? '해당 날짜 포함'
    : '해당 값 미포함';
  return `${dateCandidateLabel(end)} · ${boundary}`;
}

export function ProposalReview({
  review,
  sourceTimeZone = null,
  sourceTimeZoneError = null,
  relationReviewCandidates,
  relationReviewLoading,
  relationReviewError,
  busy,
  onChange,
  onApply,
  onPostpone,
  onReject,
  onRetryRelationReview,
  onRetrySourceTimeZone,
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
  const focusStepHeading = useRef(shouldFocusStepHeadingOnInitialOpen(review));
  const scrollRef = useRef<HTMLElement>(null);
  const representativeTypeRef = useRef<HTMLSelectElement>(null);
  const itemTitleRefs = useRef<Array<HTMLInputElement | null>>([]);
  const addItemButtonRef = useRef<HTMLButtonElement>(null);
  const pendingItemFocus = useRef<number | 'ADD_OR_TYPE' | null>(null);
  const dateCandidates = usableDateCandidates(review.proposal);
  const referencedDateCandidateIds = referencedProposalDateCandidateIds(review.proposal);
  const datesNeedingReview = review.proposal.schemaVersion !== '1'
    ? review.proposal.dateCandidates.filter(
        (date) =>
          date.candidateId === null ||
          !referencedDateCandidateIds.has(date.candidateId),
      )
    : review.proposal.dateCandidates.filter(
        (date) => !dateCandidates.some((candidate) => sameDateCandidate(candidate, date)),
      );
  const primaryType = suggestedType(review);
  const isValid = isValidReviewDraft(review);
  const hasPendingTag = newTag.trim().length > 0;
  const relationSelectionReady = isRelationSelectionReady(review, relationReviewCandidates);
  const hasScheduledEvent = review.items.some((item) => item.eventSchedule !== null);
  const scheduleZoneReady = !hasScheduledEvent || sourceTimeZone !== null;
  const canApply = isValid && !hasPendingTag && relationSelectionReady && scheduleZoneReady;
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

  const currentCloudDisposition = cloudReviewDisposition(review);
  const localModelAssisted = isSuccessfulLocalModelProposal(review);
  const cloudReviewNotice = currentCloudDisposition !== 'CONCISE' && (
    <div className="review-note" role="status">
      <p>
        {currentCloudDisposition === 'CONSENT_REQUIRED'
          ? '외부 보완 분석은 동의가 없어 실행하지 않았습니다.'
          : '보완 분석을 완료하지 못했습니다.'}
      </p>
      <p>원본 메모는 그대로 보존되며, 아래에는 검증된 로컬 제안만 표시됩니다.</p>
    </div>
  );

  const dateReviewNotice = datesNeedingReview.length > 0 && (
    <div className="review-note" role="status">
      <p>
        AI가 다음 날짜를 특정 할 일의 마감이나 일정 후보로 안전하게 연결하지 못해 자동 적용하지 않습니다.
      </p>
      <ul>
        {datesNeedingReview.map((candidate, index) => (
          <li key={candidate.candidateId ?? `unassigned-date-${index}`}>
            {dateCandidateLabel(candidate)}
          </li>
        ))}
      </ul>
      <p>필요하면 각 할 일이나 일정에서 정확한 날짜를 고르거나 날짜를 포함하지 마세요.</p>
    </div>
  );

  const sourceZoneReviewNotice = hasScheduledEvent && !sourceTimeZone && (
    <div className="review-note" role={sourceTimeZoneError ? 'alert' : 'status'}>
      <p>
        {sourceTimeZoneError
          ? '메모를 작성한 시간대를 불러오지 못했습니다. 일정 승인 전에 다시 시도해 주세요.'
          : '메모를 작성한 시간대를 확인하고 있습니다.'}
      </p>
      {sourceTimeZoneError && onRetrySourceTimeZone && (
        <button type="button" className="secondary-button" onClick={onRetrySourceTimeZone}>
          시간대 다시 불러오기
        </button>
      )}
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
              <div className="review-dialog__provenance">
                <span className="eyebrow">AI PROPOSAL · NOT APPLIED</span>
                {localModelAssisted && (
                  <span className="review-local-model-badge">로컬 LLM 보조 제안</span>
                )}
              </div>
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

          {cloudReviewNotice}

          {dateReviewNotice}

          {sourceZoneReviewNotice}

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
                        {item.kind === 'EVENT' && (
                          <small>
                            일정 {eventScheduleSummary(item)}
                            {item.eventSchedule && sourceTimeZone ? ` · ${sourceTimeZone}` : ''}
                          </small>
                        )}
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
                  review.proposal.relationCandidates.length === 0 &&
                  isValidReviewDraft(review) &&
                  !requiresExplicitDateMapping(review) &&
                  !requiresDetailedCloudReview(review) && (
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
                  const matchedEventCandidate = item.eventSchedule
                    ? dateCandidates.findIndex((candidate) =>
                        sameEventScheduleCandidate(item.eventSchedule!, candidate),
                      )
                    : -1;
                  const eventScheduleCandidates = item.eventScheduleCandidates ?? [];
                  const eventScheduleChoice = item.eventSchedule
                    ? item.eventScheduleProposalCandidateId
                      ? 'proposal'
                      : matchedEventCandidate >= 0
                      ? String(matchedEventCandidate)
                      : 'custom'
                    : 'none';
                  const eventScheduleIsValid =
                    item.eventSchedule === null || isValidEventSchedule(item.eventSchedule);
                  const eventScheduleIsReady =
                    eventScheduleIsValid &&
                    (item.eventSchedule === null || sourceTimeZone !== null);
                  const eventHelpId = `item-${index}-event-help`;

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

                      {item.kind === 'EVENT' && (
                        <div className="event-time-editor">
                          {eventScheduleCandidates.length > 0 && (
                            <section
                              className="review-note event-schedule-candidates"
                              aria-label={`항목 ${index + 1} AI 일정 후보`}
                            >
                              <strong>AI 일정 후보 · 아직 미적용</strong>
                              <p>
                                후보는 제안일 뿐입니다. ‘이 후보 사용’을 눌러 편집칸에 복사한 뒤
                                마지막 승인 버튼을 눌러야 저장됩니다.
                              </p>
                              <ul>
                                {eventScheduleCandidates.map((candidate) => {
                                  const start = review.proposal.dateCandidates.find(
                                    (date) => date.candidateId === candidate.startDateCandidateId,
                                  );
                                  const endLabel = eventScheduleCandidateEndLabel(candidate, review);
                                  const suggested =
                                    item.suggestedEventScheduleCandidateId === candidate.candidateId;
                                  const selected =
                                    item.eventScheduleProposalCandidateId === candidate.candidateId;
                                  return (
                                    <li key={candidate.candidateId}>
                                      <span>
                                        {suggested ? 'AI 추천 후보 · 신뢰하지 않은 제안' : 'AI 후보'}
                                      </span>
                                      <strong>
                                        {candidate.mode === 'TIMED' ? '시각 지정' : '종일'}
                                      </strong>
                                      {start && <small>시작 {dateCandidateLabel(start)}</small>}
                                      {endLabel && <small>종료 {endLabel}</small>}
                                      <button
                                        type="button"
                                        className="secondary-button"
                                        aria-pressed={selected}
                                        onClick={() => {
                                          const schedule = eventScheduleFromProposalCandidate(
                                            review.proposal,
                                            candidate,
                                          );
                                          if (schedule) {
                                            changeDraft(changeItemEventSchedule(
                                              review,
                                              index,
                                              schedule,
                                              candidate.candidateId,
                                            ));
                                          }
                                        }}
                                      >
                                        {selected ? '이 후보 사용 중' : '이 후보 사용'}
                                      </button>
                                    </li>
                                  );
                                })}
                              </ul>
                            </section>
                          )}
                          <label>
                            일정 시작
                            <select
                              aria-label={`항목 ${index + 1} 일정 시작`}
                              aria-describedby={eventHelpId}
                              value={eventScheduleChoice}
                              onChange={(event) => {
                                const choice = event.target.value;
                                if (choice === 'none') {
                                  changeDraft(changeItemEventSchedule(review, index, null));
                                } else if (choice === 'proposal') {
                                  return;
                                } else if (choice === 'custom') {
                                  changeDraft(changeItemEventSchedule(
                                    review,
                                    index,
                                    createCustomTimedEventSchedule(),
                                  ));
                                } else {
                                  changeDraft(changeItemEventSchedule(
                                    review,
                                    index,
                                    eventScheduleFromDateCandidate(
                                      dateCandidates[Number(choice)],
                                    ),
                                  ));
                                }
                              }}
                            >
                              <option value="none">시간 미정으로 저장</option>
                              {item.eventScheduleProposalCandidateId && (
                                <option value="proposal">AI 일정 후보에서 선택됨</option>
                              )}
                              {dateCandidates.map((candidate, candidateIndex) => (
                                <option
                                  key={`event-${candidate.precision}-${candidateIndex}`}
                                  value={candidateIndex}
                                >
                                  {dateCandidateLabel(candidate)}
                                </option>
                              ))}
                              <option value="custom">시작 직접 입력</option>
                            </select>
                          </label>

                          {item.eventSchedule && (
                            <label>
                              일정 방식
                              <select
                                aria-label={`항목 ${index + 1} 일정 방식`}
                                value={item.eventSchedule.mode}
                                onChange={(event) =>
                                  changeDraft(changeItemEventScheduleMode(
                                    review,
                                    index,
                                    event.target.value as 'TIMED' | 'ALL_DAY',
                                  ))
                                }
                              >
                                <option value="TIMED">시각 지정</option>
                                <option value="ALL_DAY">종일</option>
                              </select>
                            </label>
                          )}

                          {item.eventSchedule?.mode === 'TIMED' && (
                            <>
                              <label>
                                시작 시각 (UTC offset 포함)
                                <input
                                  type="text"
                                  value={item.eventSchedule.start}
                                  aria-invalid={!eventScheduleIsReady}
                                  aria-describedby={eventHelpId}
                                  placeholder="2026-08-24T18:00:00+09:00"
                                  onChange={(event) =>
                                    changeDraft(changeItemEventScheduleField(
                                      review,
                                      index,
                                      'start',
                                      event.target.value,
                                    ))
                                  }
                                />
                              </label>
                              <label>
                                종료 시각 (선택)
                                <input
                                  type="text"
                                  value={item.eventSchedule.end}
                                  aria-invalid={!eventScheduleIsReady}
                                  aria-describedby={eventHelpId}
                                  placeholder="2026-08-24T19:00:00+09:00"
                                  onChange={(event) =>
                                    changeDraft(changeItemEventScheduleField(
                                      review,
                                      index,
                                      'end',
                                      event.target.value,
                                    ))
                                  }
                                />
                              </label>
                            </>
                          )}

                          {item.eventSchedule?.mode === 'ALL_DAY' && (
                            <>
                              <label>
                                시작 날짜
                                <input
                                  type="date"
                                  value={item.eventSchedule.start}
                                  aria-invalid={!eventScheduleIsReady}
                                  aria-describedby={eventHelpId}
                                  onChange={(event) =>
                                    changeDraft(changeItemEventScheduleField(
                                      review,
                                      index,
                                      'start',
                                      event.target.value,
                                    ))
                                  }
                                />
                              </label>
                              <label>
                                종료 다음 날짜 (선택·미포함)
                                <input
                                  type="date"
                                  value={item.eventSchedule.end}
                                  aria-invalid={!eventScheduleIsReady}
                                  aria-describedby={eventHelpId}
                                  onChange={(event) =>
                                    changeDraft(changeItemEventScheduleField(
                                      review,
                                      index,
                                      'end',
                                      event.target.value,
                                    ))
                                  }
                                />
                              </label>
                            </>
                          )}

                          <p
                            id={eventHelpId}
                            className={eventScheduleIsReady ? 'field-help' : 'field-error'}
                            role={eventScheduleIsReady ? undefined : 'alert'}
                          >
                            {!eventScheduleIsValid
                              ? '시작을 정확히 입력하고, 종료가 있으면 시작보다 뒤로 지정해 주세요.'
                              : item.eventSchedule && !sourceTimeZone
                                ? '메모를 작성한 시간대를 불러온 뒤 승인할 수 있습니다.'
                                : item.eventSchedule
                                  ? `메모 revision의 시간대 ${sourceTimeZone}로 저장합니다. 종료를 비우면 임의 시간을 만들지 않습니다.`
                                  : '시간 미정 일정은 저장할 수 있지만 캘린더 공유 대상에는 포함되지 않습니다.'}
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

              {review.proposal.relationCandidates.length > 0 && (
                <fieldset
                  className="relation-review"
                  aria-describedby="relation-review-help"
                  disabled={busy}
                >
                  <legend>연결 후보</legend>
                  <p id="relation-review-help" className="field-help">
                    기본값은 모두 선택 안 함입니다. 필요한 연결만 직접 체크해 주세요. 연결 후보는
                    태그 제안과 별도이며, 체크해도 메모 태그로 추가되지 않습니다.
                  </p>

                  {relationReviewLoading && (
                    <p className="relation-review__status" role="status">
                      내 메모와 태그에서 연결 대상 이름을 확인하고 있습니다…
                    </p>
                  )}

                  {relationReviewError && (
                    <div className="relation-review__error" role="alert">
                      <p>{relationReviewError}</p>
                      <button
                        type="button"
                        className="secondary-button"
                        disabled={busy}
                        onClick={onRetryRelationReview}
                      >
                        연결 대상 다시 불러오기
                      </button>
                    </div>
                  )}

                  {relationReviewCandidates && (
                    <ul className="relation-review__list">
                      {review.proposal.relationCandidates.map((relation, proposalIndex) => {
                        const candidate = relationReviewCandidates[proposalIndex];
                        const sourceApplied = isRelationSourceApplied(review, proposalIndex);
                        const sourceItem = review.items.find(
                          (item) => item.proposalCandidateId === relation.sourceCandidateId,
                        );
                        const proposedSource = review.proposal.itemCandidates.find(
                          (item) => item.candidateId === relation.sourceCandidateId,
                        );
                        const selected = review.selectedRelationIndexes.includes(proposalIndex);
                        const available = candidate?.available === true;
                        const helpId = `relation-candidate-${proposalIndex}-help`;
                        return (
                          <li key={`${relation.targetType}-${relation.targetId}-${proposalIndex}`}>
                            <label
                              className={`relation-review-option${
                                !available || !sourceApplied ? ' relation-review-option--disabled' : ''
                              }`}
                            >
                              <input
                                type="checkbox"
                                checked={selected}
                                disabled={busy || !available || !sourceApplied}
                                aria-describedby={helpId}
                                onChange={(event) =>
                                  changeDraft(changeRelationSelection(
                                    review,
                                    proposalIndex,
                                    event.target.checked,
                                  ))
                                }
                              />
                              <span>
                                <strong>{sourceItem?.title ?? proposedSource?.title ?? '출발 항목 확인 필요'}</strong>
                                <span>
                                  {RELATION_TYPE_LABEL[relation.relationType]} ·{' '}
                                  {RELATION_TARGET_TYPE_LABEL[relation.targetType]}{' '}
                                  {candidate?.targetLabel ?? '현재 이름을 확인할 수 없음'}
                                </span>
                                <small id={helpId}>
                                  {!sourceApplied
                                    ? '출발 항목이 생성 목록에서 제외되어 선택할 수 없습니다.'
                                    : !available
                                      ? '이 연결 대상은 현재 사용할 수 없습니다.'
                                      : '체크한 경우에만 승인 요청에 포함됩니다.'}
                                </small>
                              </span>
                            </label>
                            {selected && (!available || !sourceApplied) && (
                              <button
                                type="button"
                                className="secondary-button relation-review__exclude"
                                disabled={busy}
                                onClick={() =>
                                  changeDraft(changeRelationSelection(review, proposalIndex, false))
                                }
                              >
                                이 연결 제외
                              </button>
                            )}
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </fieldset>
              )}

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

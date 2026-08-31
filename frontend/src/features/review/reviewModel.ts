import type {
  ApplyProposalRequest,
  DateCandidate,
  EventScheduleSelection,
  ItemCandidate,
  ItemKind,
  Proposal,
  ProposalEventScheduleCandidate,
  RelationReviewCandidate,
  TagCandidate,
} from '../../shared/api/types';
import {
  compareOffsetDateTimes,
  isValidIsoDate,
  isValidOffsetDateTime,
  nextIsoDate,
} from '../../shared/validation/dateTime';

export { isValidIsoDate, isValidOffsetDateTime } from '../../shared/validation/dateTime';

export const ITEM_KINDS: ItemKind[] = ['TASK', 'EVENT', 'INFORMATION', 'IDEA', 'RECORD'];

export function isItemKind(value: unknown): value is ItemKind {
  return typeof value === 'string' && (ITEM_KINDS as readonly string[]).includes(value);
}

export function preferredItemKind(proposal: Proposal): ItemKind | null {
  const topCandidate = proposal.typeCandidates.reduce<Proposal['typeCandidates'][number] | null>(
    (top, candidate) => (top === null || candidate.score > top.score ? candidate : top),
    null,
  );
  if (
    topCandidate &&
    proposal.typeCandidates.some(
      (candidate) => candidate.value !== topCandidate.value && candidate.score === topCandidate.score,
    )
  ) {
    return null;
  }
  return isItemKind(topCandidate?.value) ? topCandidate.value : null;
}

export type ReviewItemDraft = ItemCandidate & {
  proposalCandidateId: string | null;
  due: DateCandidate | null;
  eventSchedule: EventScheduleDraft | null;
  eventScheduleProposalCandidateId: string | null;
};

export type EventScheduleDraft = Omit<EventScheduleSelection, 'timeZone'> & {
  end: string;
};

export type ReviewDraft = {
  proposalId: string;
  proposal: Proposal;
  title: string;
  selectedType: ItemKind | null;
  tags: TagCandidate[];
  items: ReviewItemDraft[];
  selectedRelationIndexes: number[];
};

type ApplicableReviewDraft = ReviewDraft & {
  selectedType: ItemKind;
};

function cloneDate(candidate: DateCandidate): DateCandidate {
  return {
    ...candidate,
    ambiguityReasons: candidate.ambiguityReasons ? [...candidate.ambiguityReasons] : undefined,
  };
}

export function isValidDue(due: DateCandidate | null): boolean {
  if (!due || !due.surfaceText.trim()) return false;

  if (due.precision === 'DATE_ONLY') {
    return !due.timeSpecified && isValidIsoDate(due.value);
  }
  if (due.precision === 'EXACT_TIME' || due.precision === 'RELATIVE_EXACT') {
    return due.timeSpecified && isValidOffsetDateTime(due.value);
  }
  return !due.timeSpecified && (!due.value || !due.value.trim());
}

export function isValidEventSchedule(schedule: EventScheduleDraft | null): boolean {
  if (!schedule) return false;
  const end = schedule.end.trim();
  if (schedule.mode === 'ALL_DAY') {
    return isValidIsoDate(schedule.start) &&
      (!end || (isValidIsoDate(end) && end > schedule.start));
  }
  return isValidOffsetDateTime(schedule.start) &&
    (!end ||
      (isValidOffsetDateTime(end) && compareOffsetDateTimes(end, schedule.start) > 0));
}

export function createCustomTimedEventSchedule(): EventScheduleDraft {
  return { mode: 'TIMED', start: '', end: '' };
}

export function eventScheduleFromDateCandidate(
  candidate: DateCandidate,
): EventScheduleDraft | null {
  if (!isValidDue(candidate) || !candidate.value) return null;
  if (candidate.precision === 'DATE_ONLY') {
    return { mode: 'ALL_DAY', start: candidate.value, end: '' };
  }
  if (candidate.precision === 'EXACT_TIME' || candidate.precision === 'RELATIVE_EXACT') {
    return { mode: 'TIMED', start: candidate.value, end: '' };
  }
  return null;
}

export function eventScheduleFromProposalCandidate(
  proposal: Proposal,
  candidate: ProposalEventScheduleCandidate,
): EventScheduleDraft | null {
  const start = proposal.dateCandidates.find(
    (date) => date.candidateId === candidate.startDateCandidateId,
  );
  if (!start?.value) return null;

  let endValue = '';
  if (candidate.end) {
    const end = proposal.dateCandidates.find(
      (date) => date.candidateId === candidate.end!.dateCandidateId,
    );
    if (!end?.value) return null;
    endValue = candidate.end.boundary === 'INCLUSIVE_THROUGH_VALUE'
      ? nextIsoDate(end.value) ?? ''
      : end.value;
    if (!endValue) return null;
  }

  const schedule: EventScheduleDraft = {
    mode: candidate.mode,
    start: start.value,
    end: endValue,
  };
  return isValidEventSchedule(schedule) ? schedule : null;
}

export function sameEventScheduleProposalCandidate(
  schedule: EventScheduleDraft,
  proposal: Proposal,
  candidate: ProposalEventScheduleCandidate,
): boolean {
  const projected = eventScheduleFromProposalCandidate(proposal, candidate);
  return projected !== null &&
    schedule.mode === projected.mode &&
    schedule.start === projected.start &&
    schedule.end === projected.end;
}

export function sameEventScheduleCandidate(
  schedule: EventScheduleDraft,
  candidate: DateCandidate,
): boolean {
  const projected = eventScheduleFromDateCandidate(candidate);
  return projected !== null &&
    schedule.mode === projected.mode &&
    schedule.start === projected.start &&
    schedule.end.trim() === '';
}

export function usableDateCandidates(proposal: Proposal): DateCandidate[] {
  return proposal.dateCandidates
    .filter(
      (candidate) =>
        isValidDue(candidate) &&
        (candidate.precision === 'DATE_ONLY' ||
          candidate.precision === 'EXACT_TIME' ||
          candidate.precision === 'RELATIVE_EXACT'),
    )
    .map(cloneDate)
    .sort((left, right) => {
      if (left.precision === right.precision) return 0;
      if (left.precision === 'DATE_ONLY') return -1;
      if (right.precision === 'DATE_ONLY') return 1;
      return 0;
    });
}

export function referencedProposalDateCandidateIds(proposal: Proposal): Set<string> {
  const referenced = new Set<string>();
  if (proposal.schemaVersion === '1') return referenced;
  proposal.itemCandidates.forEach((item) => {
    if (item.dueDateCandidateId) referenced.add(item.dueDateCandidateId);
    if (proposal.schemaVersion !== '3') return;
    item.eventScheduleCandidates.forEach((candidate) => {
      referenced.add(candidate.startDateCandidateId);
      if (candidate.end) referenced.add(candidate.end.dateCandidateId);
    });
  });
  return referenced;
}

export function sameDateCandidate(left: DateCandidate, right: DateCandidate): boolean {
  if (
    left.candidateId !== undefined &&
    left.candidateId !== null &&
    right.candidateId !== undefined &&
    right.candidateId !== null &&
    left.candidateId !== right.candidateId
  ) {
    return false;
  }
  return (
    left.surfaceText === right.surfaceText &&
    left.value === right.value &&
    left.precision === right.precision &&
    left.timeSpecified === right.timeSpecified
  );
}

export function createCustomDateOnly(): DateCandidate {
  return {
    surfaceText: '사용자 지정 날짜',
    value: '',
    precision: 'DATE_ONLY',
    timeSpecified: false,
  };
}

export function createReviewDraft(proposalId: string, proposal: Proposal): ReviewDraft {
  const selectedType = preferredItemKind(proposal);
  const validItems = proposal.itemCandidates.filter((item) => isItemKind(item.kind));

  const baseDraft: ReviewDraft = {
    proposalId,
    proposal,
    title: proposal.suggestedTitle.value,
    selectedType,
    tags: proposal.tagCandidates.map((tag) => ({ ...tag })),
    items: validItems.map((item, index) => {
      return {
        ...item,
        proposalCandidateId: item.candidateId ?? null,
        title: index === 0 ? proposal.suggestedTitle.value : item.title,
        sourceSpan: item.sourceSpan ? { ...item.sourceSpan } : item.sourceSpan,
        due: null,
        eventSchedule: null,
        eventScheduleProposalCandidateId: null,
      };
    }),
    selectedRelationIndexes: [],
  };
  const projectedDraft = selectedType ? changeSelectedType(baseDraft, selectedType) : baseDraft;
  const usableDates = usableDateCandidates(proposal);
  const unambiguousLegacyDue =
    proposal.schemaVersion === '1' &&
    projectedDraft.items.length === 1 &&
    projectedDraft.items[0].kind === 'TASK' &&
    usableDates.length === 1
      ? usableDates[0]
      : null;
  return {
    ...projectedDraft,
    items: projectedDraft.items.map((item) => {
      let due: DateCandidate | null = null;
      if (item.kind === 'TASK') {
        if (proposal.schemaVersion !== '1' && item.dueDateCandidateId) {
          const explicitDue = usableDates.find(
            (candidate) => candidate.candidateId === item.dueDateCandidateId,
          );
          due = explicitDue ? cloneDate(explicitDue) : null;
        } else if (unambiguousLegacyDue) {
          due = cloneDate(unambiguousLegacyDue);
        }
      }
      return {
        ...item,
        due,
        eventSchedule: null,
        eventScheduleProposalCandidateId: null,
      };
    }),
  };
}

export function requiresExplicitDateMapping(review: ReviewDraft): boolean {
  const dates = usableDateCandidates(review.proposal);
  if (review.proposal.dateCandidates.length === 0) return false;
  if (dates.length !== review.proposal.dateCandidates.length) return true;

  if (review.proposal.schemaVersion === '1') {
    if (review.items.length !== 1 || review.items[0].kind !== 'TASK' || dates.length !== 1) {
      return true;
    }
    return review.items[0].due === null || !sameDateCandidate(review.items[0].due, dates[0]);
  }

  const referencedDateIds = new Set<string>();
  for (const proposedItem of review.proposal.itemCandidates) {
    const dueDateCandidateId = proposedItem.dueDateCandidateId;
    if (!dueDateCandidateId) continue;
    const expectedDate = dates.find((candidate) => candidate.candidateId === dueDateCandidateId);
    const draftItem = review.items.find((item) => item.candidateId === proposedItem.candidateId);
    if (
      !expectedDate ||
      !draftItem ||
      draftItem.kind !== 'TASK' ||
      !draftItem.due ||
      !sameDateCandidate(draftItem.due, expectedDate)
    ) {
      return true;
    }
    referencedDateIds.add(dueDateCandidateId);
  }

  if (
    review.proposal.schemaVersion === '3' &&
    review.proposal.itemCandidates.some((item) => item.eventScheduleCandidates.length > 0)
  ) {
    return true;
  }

  referencedProposalDateCandidateIds(review.proposal).forEach((candidateId) => {
    referencedDateIds.add(candidateId);
  });

  return dates.some(
    (candidate) => !candidate.candidateId || !referencedDateIds.has(candidate.candidateId),
  );
}

export function changeSelectedType(review: ReviewDraft, selectedType: ItemKind): ReviewDraft {
  if (review.items.some((item) => item.kind === selectedType) || review.items.length === 0) {
    return { ...review, selectedType };
  }

  return {
    ...review,
    selectedType,
    items: review.items.map((item, index) =>
      index === 0
        ? {
            ...item,
            kind: selectedType,
            due: selectedType === 'TASK' ? item.due : null,
            eventSchedule: selectedType === 'EVENT' ? item.eventSchedule : null,
            eventScheduleProposalCandidateId:
              selectedType === 'EVENT' ? item.eventScheduleProposalCandidateId : null,
          }
        : item,
    ),
  };
}

export function changeReviewTitle(review: ReviewDraft, title: string): ReviewDraft {
  return {
    ...review,
    title,
    items: review.items.map((item, index) => (index === 0 ? { ...item, title } : item)),
  };
}

export function changeItemTitle(
  review: ReviewDraft,
  itemIndex: number,
  title: string,
): ReviewDraft {
  return {
    ...review,
    title: itemIndex === 0 ? title : review.title,
    items: review.items.map((item, index) => (index === itemIndex ? { ...item, title } : item)),
  };
}

export function changeItemKind(
  review: ReviewDraft,
  itemIndex: number,
  kind: ItemKind,
): ReviewDraft {
  const items = review.items.map((item, index) =>
    index === itemIndex
      ? {
          ...item,
          kind,
          due: kind === 'TASK' ? item.due : null,
          eventSchedule: kind === 'EVENT' ? item.eventSchedule : null,
          eventScheduleProposalCandidateId:
            kind === 'EVENT' ? item.eventScheduleProposalCandidateId : null,
        }
      : item,
  );
  const selectedType = review.selectedType && items.some((item) => item.kind === review.selectedType)
    ? review.selectedType
    : kind;
  return { ...review, selectedType, items };
}

export function addManualItem(review: ReviewDraft): ReviewDraft {
  if (!review.selectedType || review.items.length >= 3) return review;

  let suffix = 1;
  while (review.items.some((item) => item.candidateId === `manual-${suffix}`)) {
    suffix += 1;
  }
  const isFirstItem = review.items.length === 0;
  const item: ReviewItemDraft = {
    candidateId: `manual-${suffix}`,
    proposalCandidateId: null,
    kind: review.selectedType,
    title: isFirstItem ? review.title : '',
    sourceSpan: null,
    action: null,
    object: null,
    due: null,
    eventSchedule: null,
    eventScheduleProposalCandidateId: null,
  };
  return { ...review, items: [...review.items, item] };
}

export function removeReviewItem(review: ReviewDraft, itemIndex: number): ReviewDraft {
  if (itemIndex < 0 || itemIndex >= review.items.length) return review;

  const items = review.items.filter((_, index) => index !== itemIndex);
  const selectedType =
    review.selectedType && items.some((item) => item.kind === review.selectedType)
      ? review.selectedType
      : (items[0]?.kind ?? review.selectedType);
  const remainingProposalCandidateIds = new Set(
    items
      .map((item) => item.proposalCandidateId)
      .filter((candidateId): candidateId is string => candidateId !== null),
  );
  return {
    ...review,
    selectedType,
    title: itemIndex === 0 && items[0] ? items[0].title : review.title,
    items,
    selectedRelationIndexes: review.selectedRelationIndexes.filter((proposalIndex) => {
      const relation = review.proposal.relationCandidates[proposalIndex];
      return relation !== undefined && remainingProposalCandidateIds.has(relation.sourceCandidateId);
    }),
  };
}

export function isRelationSourceApplied(
  review: ReviewDraft,
  proposalIndex: number,
): boolean {
  const relation = review.proposal.relationCandidates[proposalIndex];
  if (!relation) return false;
  return review.items.filter(
    (item) => item.proposalCandidateId === relation.sourceCandidateId,
  ).length === 1;
}

export function changeRelationSelection(
  review: ReviewDraft,
  proposalIndex: number,
  selected: boolean,
): ReviewDraft {
  if (
    !Number.isSafeInteger(proposalIndex) ||
    proposalIndex < 0 ||
    proposalIndex >= review.proposal.relationCandidates.length
  ) {
    return review;
  }

  const currentlySelected = review.selectedRelationIndexes.includes(proposalIndex);
  if (currentlySelected === selected) return review;
  if (selected && !isRelationSourceApplied(review, proposalIndex)) return review;

  return {
    ...review,
    selectedRelationIndexes: selected
      ? [...review.selectedRelationIndexes, proposalIndex].sort((left, right) => left - right)
      : review.selectedRelationIndexes.filter((index) => index !== proposalIndex),
  };
}

export function isRelationSelectionReady(
  review: ReviewDraft,
  candidates: RelationReviewCandidate[] | null,
): boolean {
  if (review.proposal.relationCandidates.length === 0) return true;
  if (!candidates || candidates.length !== review.proposal.relationCandidates.length) return false;
  return review.selectedRelationIndexes.every((proposalIndex) => {
    const candidate = candidates[proposalIndex];
    return candidate?.proposalIndex === proposalIndex &&
      candidate.available &&
      isRelationSourceApplied(review, proposalIndex);
  });
}

export function changeItemDue(
  review: ReviewDraft,
  itemIndex: number,
  due: DateCandidate | null,
): ReviewDraft {
  return {
    ...review,
    items: review.items.map((item, index) =>
      index === itemIndex ? { ...item, due: due ? cloneDate(due) : null } : item,
    ),
  };
}

export function changeItemDueValue(
  review: ReviewDraft,
  itemIndex: number,
  value: string,
): ReviewDraft {
  return {
    ...review,
    items: review.items.map((item, index) => {
      if (index !== itemIndex || !item.due) return item;
      return { ...item, due: { ...item.due, value } };
    }),
  };
}

export function changeItemEventSchedule(
  review: ReviewDraft,
  itemIndex: number,
  eventSchedule: EventScheduleDraft | null,
  eventScheduleProposalCandidateId: string | null = null,
): ReviewDraft {
  return {
    ...review,
    items: review.items.map((item, index) =>
      index === itemIndex
        ? {
            ...item,
            eventSchedule: eventSchedule ? { ...eventSchedule } : null,
            eventScheduleProposalCandidateId:
              eventSchedule ? eventScheduleProposalCandidateId : null,
          }
        : item,
    ),
  };
}

export function changeItemEventScheduleField(
  review: ReviewDraft,
  itemIndex: number,
  field: 'start' | 'end',
  value: string,
): ReviewDraft {
  return {
    ...review,
    items: review.items.map((item, index) => {
      if (index !== itemIndex || !item.eventSchedule) return item;
      return {
        ...item,
        eventSchedule: { ...item.eventSchedule, [field]: value },
        eventScheduleProposalCandidateId: null,
      };
    }),
  };
}

export function changeItemEventScheduleMode(
  review: ReviewDraft,
  itemIndex: number,
  mode: EventScheduleDraft['mode'],
): ReviewDraft {
  return changeItemEventSchedule(review, itemIndex, { mode, start: '', end: '' });
}

function hasValidEventScheduleProposalReference(
  review: ReviewDraft,
  item: ReviewItemDraft,
): boolean {
  if (item.eventScheduleProposalCandidateId === null) return true;
  if (item.kind !== 'EVENT' || !item.eventSchedule || !item.proposalCandidateId) return false;
  const proposalItem = review.proposal.itemCandidates.find(
    (candidate) => candidate.candidateId === item.proposalCandidateId,
  );
  const proposalSchedule = proposalItem?.eventScheduleCandidates.find(
    (candidate) => candidate.candidateId === item.eventScheduleProposalCandidateId,
  );
  return proposalSchedule !== undefined &&
    sameEventScheduleProposalCandidate(item.eventSchedule, review.proposal, proposalSchedule);
}

export function isValidReviewDraft(review: ReviewDraft): review is ApplicableReviewDraft {
  const proposalCandidateIds = new Set(
    review.proposal.itemCandidates.map((item) => item.candidateId),
  );
  const appliedProposalCandidateIds = review.items
    .map((item) => item.proposalCandidateId)
    .filter((candidateId): candidateId is string => candidateId !== null);
  const relationIndexesAreValid =
    review.selectedRelationIndexes.length <= review.proposal.relationCandidates.length &&
    review.selectedRelationIndexes.every(
      (proposalIndex, index) =>
        Number.isSafeInteger(proposalIndex) &&
        proposalIndex >= 0 &&
        proposalIndex < review.proposal.relationCandidates.length &&
        (index === 0 || proposalIndex > review.selectedRelationIndexes[index - 1]!) &&
        isRelationSourceApplied(review, proposalIndex),
    );
  return (
    review.selectedType !== null &&
    review.title.trim().length > 0 &&
    review.items.length > 0 &&
    review.items.length <= 3 &&
    review.items[0].title.trim() === review.title.trim() &&
    review.items.some((item) => item.kind === review.selectedType) &&
    appliedProposalCandidateIds.every((candidateId) => proposalCandidateIds.has(candidateId)) &&
    new Set(appliedProposalCandidateIds).size === appliedProposalCandidateIds.length &&
    relationIndexesAreValid &&
    review.items.every(
      (item) =>
        item.title.trim().length > 0 &&
        (item.kind === 'TASK'
          ? item.eventSchedule === null && (item.due === null || isValidDue(item.due))
          : item.due === null) &&
        (item.kind === 'EVENT'
          ? item.eventSchedule === null || isValidEventSchedule(item.eventSchedule)
          : item.eventSchedule === null) &&
        hasValidEventScheduleProposalReference(review, item),
    )
  );
}

export function buildApplyRequest(review: ReviewDraft, timeZone: string): ApplyProposalRequest {
  if (!isValidReviewDraft(review)) {
    throw new Error('검토 제안은 적용 전에 유형, 제목, 항목을 확인해야 합니다.');
  }
  const items: ApplyProposalRequest['items'] = review.items.map((item) => {
    const applyItem: ApplyProposalRequest['items'][number] = {
      proposalCandidateId: item.proposalCandidateId,
      kind: item.kind,
      title: item.title.trim(),
      due:
        item.kind === 'TASK' && item.due
          ? {
              surfaceText: item.due.surfaceText,
              value: item.due.value,
              precision: item.due.precision,
              timeZone,
              timeSpecified: item.due.timeSpecified,
            }
          : null,
    };
    if (item.kind === 'EVENT' && item.eventSchedule) {
      applyItem.eventSchedule = {
        mode: item.eventSchedule.mode,
        start: item.eventSchedule.start,
        end: item.eventSchedule.end.trim() || null,
        timeZone,
      };
    }
    return applyItem;
  });
  const request: ApplyProposalRequest = {
    expectedMemoRevision: review.proposal.memoRevision,
    selectedType: review.selectedType,
    title: review.title.trim(),
    selectedTags: review.tags.map((tag) => ({
      existingTagId: tag.existingTagId,
      newCanonicalName: tag.existingTagId ? null : tag.canonicalName.trim(),
    })),
    items,
    selectedRelations: review.selectedRelationIndexes.map((proposalIndex) => ({ proposalIndex })),
  };
  if (items.some((item) => item.eventSchedule !== undefined)) {
    request.selectionSchemaVersion = '2';
  }
  return request;
}

import type {
  ApplyProposalRequest,
  DateCandidate,
  ItemCandidate,
  ItemKind,
  Proposal,
  TagCandidate,
} from '../../shared/api/types';
import {
  isValidIsoDate,
  isValidOffsetDateTime,
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
  due: DateCandidate | null;
};

export type ReviewDraft = {
  proposalId: string;
  proposal: Proposal;
  title: string;
  selectedType: ItemKind | null;
  tags: TagCandidate[];
  items: ReviewItemDraft[];
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
        title: index === 0 ? proposal.suggestedTitle.value : item.title,
        sourceSpan: item.sourceSpan ? { ...item.sourceSpan } : item.sourceSpan,
        due: null,
      };
    }),
  };
  const projectedDraft = selectedType ? changeSelectedType(baseDraft, selectedType) : baseDraft;
  const usableDates = usableDateCandidates(proposal);
  const unambiguousDue =
    projectedDraft.items.length === 1 &&
    projectedDraft.items[0].kind === 'TASK' &&
    usableDates.length === 1
      ? usableDates[0]
      : null;
  return {
    ...projectedDraft,
    items: projectedDraft.items.map((item) => ({
      ...item,
      due: item.kind === 'TASK' && unambiguousDue ? cloneDate(unambiguousDue) : null,
    })),
  };
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
        ? { ...item, kind: selectedType, due: selectedType === 'TASK' ? item.due : null }
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
    index === itemIndex ? { ...item, kind, due: kind === 'TASK' ? item.due : null } : item,
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
    kind: review.selectedType,
    title: isFirstItem ? review.title : '',
    sourceSpan: null,
    action: null,
    object: null,
    due: null,
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
  return {
    ...review,
    selectedType,
    title: itemIndex === 0 && items[0] ? items[0].title : review.title,
    items,
  };
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

export function isValidReviewDraft(review: ReviewDraft): review is ApplicableReviewDraft {
  return (
    review.selectedType !== null &&
    review.title.trim().length > 0 &&
    review.items.length > 0 &&
    review.items.length <= 3 &&
    review.items[0].title.trim() === review.title.trim() &&
    review.items.some((item) => item.kind === review.selectedType) &&
    review.items.every(
      (item) =>
        item.title.trim().length > 0 &&
        (item.kind !== 'TASK' || item.due === null || isValidDue(item.due)),
    )
  );
}

export function buildApplyRequest(review: ReviewDraft, timeZone: string): ApplyProposalRequest {
  if (!isValidReviewDraft(review)) {
    throw new Error('검토 제안은 적용 전에 유형, 제목, 항목을 확인해야 합니다.');
  }
  return {
    expectedMemoRevision: review.proposal.memoRevision,
    selectedType: review.selectedType,
    title: review.title.trim(),
    selectedTags: review.tags.map((tag) => ({
      existingTagId: tag.existingTagId,
      newCanonicalName: tag.existingTagId ? null : tag.canonicalName.trim(),
    })),
    items: review.items.map((item) => ({
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
    })),
  };
}

import type {
  ApplyProposalRequest,
  DateCandidate,
  ItemCandidate,
  ItemKind,
  Proposal,
  TagCandidate,
} from '../../shared/api/types';

export const ITEM_KINDS: ItemKind[] = ['TASK', 'EVENT', 'INFORMATION', 'IDEA', 'RECORD'];

export type ReviewItemDraft = ItemCandidate & {
  due: DateCandidate | null;
};

export type ReviewDraft = {
  proposalId: string;
  proposal: Proposal;
  title: string;
  selectedType: ItemKind;
  tags: TagCandidate[];
  items: ReviewItemDraft[];
};

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const OFFSET_DATE_TIME = /(Z|[+-]\d{2}:\d{2})$/;

function cloneDate(candidate: DateCandidate): DateCandidate {
  return {
    ...candidate,
    ambiguityReasons: candidate.ambiguityReasons ? [...candidate.ambiguityReasons] : undefined,
  };
}

export function isValidIsoDate(value: string | null): value is string {
  if (!value) return false;
  const match = ISO_DATE.exec(value);
  if (!match) return false;

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
  );
}

export function isValidDue(due: DateCandidate | null): boolean {
  if (!due || !due.surfaceText.trim()) return false;

  if (due.precision === 'DATE_ONLY') {
    return !due.timeSpecified && isValidIsoDate(due.value);
  }
  if (due.precision === 'EXACT_TIME' || due.precision === 'RELATIVE_EXACT') {
    return Boolean(
      due.timeSpecified &&
        due.value &&
        OFFSET_DATE_TIME.test(due.value) &&
        !Number.isNaN(Date.parse(due.value)),
    );
  }
  return !due.timeSpecified && (!due.value || !due.value.trim());
}

export function usableDateCandidates(proposal: Proposal): DateCandidate[] {
  return proposal.dateCandidates
    .filter(isValidDue)
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
  const preferredDue = usableDateCandidates(proposal)[0] ?? null;
  let assignedPreferredDue = false;
  const selectedType = proposal.typeCandidates[0]?.value ?? 'RECORD';

  const draft: ReviewDraft = {
    proposalId,
    proposal,
    title: proposal.suggestedTitle.value,
    selectedType,
    tags: proposal.tagCandidates.map((tag) => ({ ...tag })),
    items: proposal.itemCandidates.map((item, index) => {
      const shouldAssignDue = item.kind === 'TASK' && preferredDue && !assignedPreferredDue;
      if (shouldAssignDue) assignedPreferredDue = true;
      return {
        ...item,
        title: index === 0 ? proposal.suggestedTitle.value : item.title,
        sourceSpan: item.sourceSpan ? { ...item.sourceSpan } : item.sourceSpan,
        due: shouldAssignDue ? cloneDate(preferredDue) : null,
      };
    }),
  };
  return changeSelectedType(draft, selectedType);
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
  const selectedType = items.some((item) => item.kind === review.selectedType)
    ? review.selectedType
    : kind;
  return { ...review, selectedType, items };
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

export function isValidReviewDraft(review: ReviewDraft): boolean {
  return (
    review.title.trim().length > 0 &&
    review.items.length > 0 &&
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

export type MemoView = {
  id: string;
  currentRevision: number;
  content: string;
  status: MemoStatus;
  analysisState: string;
  createdAt: string;
};

export type MemoStatus = 'ACTIVE' | 'TRASHED';

type UpdateMemoContent = {
  expectedRevision: number;
  content: string;
};

type UpdateMemoCaptureContext =
  | {
      clientUpdatedAt: string;
      timeZone: string;
    }
  | {
      clientUpdatedAt?: never;
      timeZone?: never;
    };

export type UpdateMemoRequest = UpdateMemoContent & UpdateMemoCaptureContext;

export type AnalysisRun = {
  id: string;
  memoId: string;
  memoRevision: number;
  status: string;
  proposalId: string;
};

export type ScoredValue<T extends string = string> = {
  value: T;
  score: number;
};

export type DatePrecision =
  | 'EXACT_TIME'
  | 'DATE_ONLY'
  | 'RELATIVE_EXACT'
  | 'APPROXIMATE'
  | 'UNKNOWN';

export type DateCandidate = {
  surfaceText: string;
  value: string | null;
  precision: DatePrecision;
  timeSpecified: boolean;
  confidence?: number;
  ambiguityReasons?: string[];
};

export type TagCandidate = {
  existingTagId: string | null;
  canonicalName: string;
  matchedAlias: string | null;
  score?: number;
  isNewProposal?: boolean;
};

export type ItemKind = 'TASK' | 'EVENT' | 'INFORMATION' | 'IDEA' | 'RECORD';
export type SemanticType = ItemKind | 'UNKNOWN';

export type ItemCandidate = {
  candidateId?: string;
  kind: ItemKind;
  title: string;
  sourceSpan?: { start: number; end: number } | null;
  action?: string | null;
  object?: string | null;
  confidence?: number;
};

export type ProposalDateCandidate = DateCandidate & {
  confidence: number;
  ambiguityReasons: string[];
};

export type ProposalTagCandidate = TagCandidate & {
  score: number;
  isNewProposal: boolean;
};

export type ProposalItemCandidate = ItemCandidate & {
  candidateId: string;
  sourceSpan: { start: number; end: number } | null;
  action: string | null;
  object: string | null;
  confidence: number;
};

export type RelationCandidate = {
  sourceCandidateId: string;
  targetType: 'MEMO' | 'TAG';
  targetId: string;
  relationType: 'RELATED_TO' | 'CONTINUES' | 'DEPENDS_ON' | 'REFERENCES';
  score: number;
};

export type Proposal = {
  schemaVersion: '1';
  memoId: string;
  memoRevision: number;
  suggestedTitle: {
    value: string;
    confidence: number;
    needsConfirmation: boolean;
  };
  typeCandidates: ScoredValue<SemanticType>[];
  dateCandidates: ProposalDateCandidate[];
  tagCandidates: ProposalTagCandidate[];
  itemCandidates: ProposalItemCandidate[];
  relationCandidates: RelationCandidate[];
  ambiguityReasons: string[];
  providerMetadata: Record<string, unknown>;
};

export type SelectedTag = {
  existingTagId: string | null;
  newCanonicalName: string | null;
};

export type ApplyProposalRequest = {
  expectedMemoRevision: number;
  selectedType: ItemKind;
  title: string;
  selectedTags: SelectedTag[];
  items: Array<{
    kind: ItemKind;
    title: string;
    due: {
      surfaceText: string;
      value: string | null;
      precision: DatePrecision;
      timeZone: string;
      timeSpecified: boolean;
    } | null;
  }>;
};

export type ApplicationResult = {
  applicationId: string;
  status: 'APPLIED' | 'UNDONE';
};

export type ReviewDispositionResult = {
  proposalId: string;
  status: 'REJECTED' | 'POSTPONED';
};

export type LatestApplication = {
  applicationId: string | null;
  status: 'NONE' | 'APPLIED' | 'UNDONE';
};

export type ProposalSummary = {
  proposalId: string;
  status: 'REVIEW_REQUIRED' | 'POSTPONED';
  createdAt: string;
  proposal: Proposal;
};

export type TaskStatus = 'TODO' | 'DONE' | 'CANCELLED';

export type Task = {
  id: string;
  title: string;
  status: TaskStatus;
  dueAt: string | null;
  dueDate?: string | null;
  overdue: boolean;
};

export type GraphNode = {
  id: string;
  kind: 'MEMO' | 'TAG';
  label: string;
  memoType?: ItemKind | null;
  taskState?: TaskStatus | 'NONE' | null;
  overdue?: boolean;
};

export type GraphEdge = {
  id: string;
  source: string;
  target: string;
  kind: 'MEMO_TAG';
};

export type GraphProjection = {
  nodes: GraphNode[];
  edges: GraphEdge[];
  truncated: boolean;
  projectionVersion: string;
};

export type MemoView = {
  id: string;
  currentRevision: number;
  content: string;
  pinned: boolean;
  status: MemoStatus;
  analysisState: string;
  createdAt: string;
  sourceTimeZone?: string;
  clientRecordedAt?: string | null;
};

export type LoginMethod = 'LOCAL' | 'GOOGLE';

export type AuthCapabilities = {
  registrationEnabled: boolean;
  googleEnabled: boolean;
  googleRegistrationEnabled: boolean;
};

export type AuthSession = {
  userId: string;
  email: string;
  displayName: string;
  loginMethods: LoginMethod[];
};

export type CsrfToken = {
  headerName: string;
  parameterName: string;
  token: string;
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
  candidateId?: string | null;
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

export type ProposalEventScheduleEnd = {
  dateCandidateId: string;
  boundary: 'EXCLUSIVE_AT_VALUE' | 'INCLUSIVE_THROUGH_VALUE';
};

export type ProposalEventScheduleCandidate = {
  candidateId: string;
  mode: 'TIMED' | 'ALL_DAY';
  startDateCandidateId: string;
  end: ProposalEventScheduleEnd | null;
  score: number;
};

export type ItemCandidate = {
  candidateId?: string;
  dueDateCandidateId?: string | null;
  eventScheduleCandidates?: ProposalEventScheduleCandidate[];
  suggestedEventScheduleCandidateId?: string | null;
  kind: ItemKind;
  title: string;
  sourceSpan?: { start: number; end: number } | null;
  action?: string | null;
  object?: string | null;
  confidence?: number;
};

export type ProposalDateCandidate = DateCandidate & {
  candidateId: string | null;
  confidence: number;
  ambiguityReasons: string[];
};

export type ProposalTagCandidate = TagCandidate & {
  score: number;
  isNewProposal: boolean;
};

export type ProposalItemCandidate = ItemCandidate & {
  candidateId: string;
  dueDateCandidateId: string | null;
  eventScheduleCandidates: ProposalEventScheduleCandidate[];
  suggestedEventScheduleCandidateId: string | null;
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
  schemaVersion: '1' | '2' | '3';
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

export type RelationReviewCandidate = {
  proposalIndex: number;
  targetType: RelationCandidate['targetType'];
  targetId: string;
  targetLabel: string | null;
  available: boolean;
};

export type SelectedRelation = {
  proposalIndex: number;
};

export type EventScheduleSelection = {
  mode: 'TIMED' | 'ALL_DAY';
  start: string;
  end: string | null;
  timeZone: string;
};

export type ApplyProposalRequest = {
  selectionSchemaVersion?: '2';
  expectedMemoRevision: number;
  selectedType: ItemKind;
  title: string;
  selectedTags: SelectedTag[];
  items: Array<{
    proposalCandidateId: string | null;
    kind: ItemKind;
    title: string;
    due: {
      surfaceText: string;
      value: string | null;
      precision: DatePrecision;
      timeZone: string;
      timeSpecified: boolean;
    } | null;
    eventSchedule?: EventScheduleSelection;
  }>;
  selectedRelations: SelectedRelation[];
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

export type ReviewOutcomeCurrentStates = {
  queued: number;
  running: number;
  reviewRequired: number;
  currentPostponed: number;
  failed: number;
  stale: number;
  applied: number;
  rejected: number;
  other: number;
};

export type ReviewOutcomeProposalCounters = {
  total: number;
  withApplication: number;
  currentStates: ReviewOutcomeCurrentStates;
};

export type ReviewOutcomeLatestApplications = {
  none: number;
  applied: number;
  undone: number;
};

export type ReviewOutcomeCounters = {
  exact: number;
  corrected: number;
  userResolved: number;
  unclassifiable: number;
  correctedFields: {
    type: number;
    title: number;
    tags: number;
    items: number;
    due: number;
  };
};

export type ReviewOutcomeAnalysisVersion = {
  route: 'MOCK' | 'LOCAL' | 'CLOUD' | 'HYBRID';
  analyzerVersion: string;
  promptVersion: string;
  localModelVersion: string;
  embeddingModelVersion: string;
  routingPolicyVersion: string;
  proposals: ReviewOutcomeProposalCounters;
  latestApplications: ReviewOutcomeLatestApplications;
  outcomes: ReviewOutcomeCounters;
};

export type AnalysisReviewOutcomeSummary = {
  schemaVersion: '1';
  comparisonPolicyVersion: 'review-default-v3';
  cohort: {
    basis: 'PROPOSAL_CREATED_AT';
    days: number;
    fromInclusive: string;
    toExclusive: string;
    maxProposals: number;
  };
  proposals: ReviewOutcomeProposalCounters;
  latestApplications: ReviewOutcomeLatestApplications;
  outcomes: ReviewOutcomeCounters;
  byAnalysisVersion: ReviewOutcomeAnalysisVersion[];
};

export type AnalysisPathEvidenceSummary = {
  schemaVersion: '1';
  aggregationPolicyVersion: 'analysis-path-evidence-summary-v1';
  cohort: {
    basis: 'ANALYSIS_RUN_CREATED_AT';
    days: number;
    fromInclusive: string;
    toExclusive: string;
    maxRuns: 1_000;
  };
  runs: {
    total: number;
    withDispatch: number;
    withoutDispatch: number;
  };
  localDecisionEvidence: {
    current: number;
    legacy: number;
  };
  lifecycle: {
    prepared: number;
    running: number;
    finalized: number;
  };
  invocationModes: {
    legacyUnknown: number;
    uncertaintyOnly: number;
    aiPreferred: number;
  };
  invocationReasons: {
    legacyUnknown: number;
    semanticUncertainty: number;
    aiPreferredPolicy: number;
  };
  dispatchRoutes: {
    localModel: number;
    externalMemoTransfer: number;
    builtInFake: number;
    legacyOrOther: number;
  };
  localModelContributions: {
    notRecorded: number;
    pending: number;
    acceptedChanged: number;
    acceptedUnchanged: number;
    localFallback: number;
  };
  approvedCorrectionSnapshots: {
    withSignals: number;
    totalSignals: number;
  };
  fallbackReasons: {
    defaultRecordFallback: number;
    unparsedTemporalCue: number;
    unrecognizedActionCue: number;
    lowTypeMargin: number;
    tagUncertainty: number;
    dateUncertainty: number;
    unresolvedReference: number;
    incompleteTask: number;
    multiIntent: number;
    candidateLimit: number;
    localConflict: number;
  };
  changedFields: {
    suggestedTitle: number;
    typeCandidates: number;
    dateCandidates: number;
    tagCandidates: number;
    itemCandidates: number;
    relationCandidates: number;
    ambiguityReasons: number;
  };
};

export type ProposalSummary = {
  proposalId: string;
  status: 'REVIEW_REQUIRED' | 'POSTPONED';
  createdAt: string;
  proposal: Proposal;
};

export type TaskStatus = 'TODO' | 'DONE' | 'CANCELLED';
export type MemoSearchTaskState = TaskStatus | 'NONE';

export type MemoSearchMatchedField = 'TITLE' | 'BODY' | 'TAG' | 'ALIAS';

export type MemoSearchRequest = {
  query: string;
  lifecycleStatus: MemoStatus;
  taskState?: MemoSearchTaskState;
  overdue?: boolean;
  revisedFrom?: string;
  revisedBefore?: string;
  limit: number;
  cursor?: string;
};

export type MemoSearchCanonicalTag = {
  id: string;
  name: string;
};

export type MemoSearchItem = {
  memoId: string;
  currentRevision: number;
  canonicalRevision: number | null;
  title: string | null;
  preview: string;
  lifecycleStatus: MemoStatus;
  canonicalTags: MemoSearchCanonicalTag[];
  taskState: MemoSearchTaskState;
  overdue: boolean;
  pinned: boolean;
  revisedAt: string;
  matchedFields: MemoSearchMatchedField[];
};

export type MemoSearchPage = {
  items: MemoSearchItem[];
  nextCursor: string | null;
  truncated: boolean;
};

export type Task = {
  id: string;
  title: string;
  status: TaskStatus;
  dueAt: string | null;
  dueDate?: string | null;
  overdue: boolean;
};

export type CalendarEvent = {
  id: string;
  title: string;
  scheduleKind: 'TIMED' | 'ALL_DAY';
  startAt: string | null;
  endAt: string | null;
  startDate: string | null;
  endDateExclusive: string | null;
  sourceTimeZone: string;
};

export type CalendarFeedDisclosureMode = 'BUSY_ONLY' | 'TITLE';

export type CalendarFeedStatus = 'ACTIVE' | 'REVOKED';

export type CalendarFeedEntryState = 'ACTIVE' | 'CANCELLED';

export const CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION =
  'calendar-feed-public-v1' as const;

export type CalendarFeedPublicationScope = 'LOCAL_ONLY' | 'PUBLIC_HTTPS';

export type CalendarFeedPublicationCapability =
  | { mode: 'LOCAL_ONLY'; publicOrigin: null; consentPolicyVersion: null }
  | {
      mode: 'PUBLIC_HTTPS';
      publicOrigin: string;
      consentPolicyVersion: typeof CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION;
    };

export type CalendarFeedEligibleEvents = {
  items: CalendarEvent[];
  truncated: boolean;
};

export type CalendarFeedSummary = {
  id: string;
  displayName: string;
  disclosureMode: CalendarFeedDisclosureMode;
  status: CalendarFeedStatus;
  version: number;
  eventCount: number;
  createdAt: string;
  updatedAt: string;
  rotatedAt: string;
  revokedAt: string | null;
  publicationScope: CalendarFeedPublicationScope;
  publicConsentPolicyVersion: typeof CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION | null;
  publicConsentGrantedAt: string | null;
};

export type CalendarFeedEntry = {
  id: string;
  eventId: string | null;
  title: string | null;
  state: CalendarFeedEntryState;
  sequence: number;
  scheduleKind: 'TIMED' | 'ALL_DAY';
  startAt: string | null;
  endAt: string | null;
  startDate: string | null;
  endDateExclusive: string | null;
  sourceTimeZone: string;
};

export type CalendarFeedDetail = CalendarFeedSummary & {
  entries: CalendarFeedEntry[];
};

export type CreateCalendarFeedRequest = {
  displayName: string;
  disclosureMode: CalendarFeedDisclosureMode;
  eventIds: string[];
  bearerSecret: string;
};

export type UpdateCalendarFeedRequest = {
  displayName: string;
  disclosureMode: CalendarFeedDisclosureMode;
  expectedVersion: number;
};

export type RotateCalendarFeedRequest = {
  bearerSecret: string;
  expectedVersion: number;
};

export type EnableExternalCalendarFeedPublicationRequest = {
  expectedVersion: number;
  bearerSecret: string;
  consentPolicyVersion: typeof CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION;
};

export type VersionedCalendarFeedRequest = {
  expectedVersion: number;
};

export type AddCalendarFeedEventRequest = VersionedCalendarFeedRequest & {
  eventId: string;
};

export type GraphNode = {
  id: string;
  kind: 'MEMO' | 'TAG';
  label: string;
  pinned: boolean;
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

export type GraphNeighborhoodPage = {
  center: GraphNode;
  neighbors: GraphNode[];
  edges: GraphEdge[];
  truncated: boolean;
  nextCursor: string | null;
};

export type MemoPinResult = {
  id: string;
  pinned: boolean;
  updated: boolean;
};

package local.personalmemo.analysis;

import static local.personalmemo.analysis.api.AnalysisController.PROPOSAL_SCHEMA_VERSION_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;
import local.personalmemo.analysis.application.AnalysisService;
import local.personalmemo.analysis.domain.AnalysisProposalSchemaValidator;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
class AnalysisProposalSchemaNegotiationIntegrationTest extends PostgresIntegrationTestSupport {

  @Autowired private AnalysisProposalSchemaValidator proposalSchemaValidator;
  @Autowired private AnalysisService analysisService;

  @Test
  void singleProposalDefaultsToStrictV1WithoutMutatingThePersistedV2Proposal() throws Exception {
    UUID proposalId = createBoundProposal("schema-single");

    JsonNode storedBefore = storedProposal(proposalId);
    assertThat(storedBefore.path("schemaVersion").asText()).isEqualTo("2");
    assertThat(storedBefore.at("/dateCandidates/0/candidateId").asText()).isEqualTo("date-1");
    assertThat(storedBefore.at("/itemCandidates/0/dueDateCandidateId").asText())
        .isEqualTo("date-1");

    var legacy = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();
    assertProposalResponseHeaders(legacy.getResponse());
    JsonNode legacyProposal = response(legacy);
    assertThat(legacyProposal.path("schemaVersion").asText()).isEqualTo("1");
    assertThat(legacyProposal.at("/dateCandidates/0/candidateId").isMissingNode()).isTrue();
    assertThat(legacyProposal.at("/itemCandidates/0/dueDateCandidateId").isMissingNode()).isTrue();
    proposalSchemaValidator.validate(legacyProposal);

    var current =
        mvc.perform(
                get("/api/v1/analysis-proposals/{id}", proposalId)
                    .header(PROPOSAL_SCHEMA_VERSION_HEADER, "2"))
            .andReturn();
    assertProposalResponseHeaders(current.getResponse());
    JsonNode currentProposal = response(current);
    assertThat(currentProposal.path("schemaVersion").asText()).isEqualTo("2");
    assertThat(currentProposal.at("/dateCandidates/0/candidateId").asText()).isEqualTo("date-1");
    assertThat(currentProposal.at("/itemCandidates/0/dueDateCandidateId").asText())
        .isEqualTo("date-1");

    var maximum =
        mvc.perform(
                get("/api/v1/analysis-proposals/{id}", proposalId)
                    .header(PROPOSAL_SCHEMA_VERSION_HEADER, "3"))
            .andReturn();
    assertProposalResponseHeaders(maximum.getResponse());
    JsonNode maximumProposal = response(maximum);
    assertThat(maximumProposal.path("schemaVersion").asText()).isEqualTo("2");
    assertThat(maximumProposal.at("/itemCandidates/0/eventScheduleCandidates").isMissingNode())
        .isTrue();

    assertThat(storedProposal(proposalId)).isEqualTo(storedBefore);
  }

  @Test
  void storedVersionThreeProjectsStrictlyToVersionTwoAndVersionOne() throws Exception {
    UUID proposalId = createBoundProposal("schema-v3-projection");
    promoteStoredProposalToVersionThree(proposalId);
    JsonNode storedBefore = storedProposal(proposalId);
    proposalSchemaValidator.validate(storedBefore);

    var versionThree =
        mvc.perform(
                get("/api/v1/analysis-proposals/{id}", proposalId)
                    .header(PROPOSAL_SCHEMA_VERSION_HEADER, "3"))
            .andReturn();
    assertProposalResponseHeaders(versionThree.getResponse());
    assertThat(response(versionThree).path("schemaVersion").asText()).isEqualTo("3");
    assertThat(response(versionThree).at("/itemCandidates/0/eventScheduleCandidates").isArray())
        .isTrue();
    assertThat(
            response(versionThree)
                .at("/itemCandidates/0/suggestedEventScheduleCandidateId")
                .isNull())
        .isTrue();

    var versionTwo =
        mvc.perform(
                get("/api/v1/analysis-proposals/{id}", proposalId)
                    .header(PROPOSAL_SCHEMA_VERSION_HEADER, "2"))
            .andReturn();
    assertProposalResponseHeaders(versionTwo.getResponse());
    JsonNode projectedV2 = response(versionTwo);
    assertThat(projectedV2.path("schemaVersion").asText()).isEqualTo("2");
    assertThat(projectedV2.at("/dateCandidates/0/candidateId").asText()).isEqualTo("date-1");
    assertThat(projectedV2.at("/itemCandidates/0/dueDateCandidateId").asText()).isEqualTo("date-1");
    assertThat(projectedV2.at("/itemCandidates/0/eventScheduleCandidates").isMissingNode())
        .isTrue();
    assertThat(
            projectedV2.at("/itemCandidates/0/suggestedEventScheduleCandidateId").isMissingNode())
        .isTrue();
    proposalSchemaValidator.validate(projectedV2);

    var versionOne = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();
    assertProposalResponseHeaders(versionOne.getResponse());
    JsonNode projectedV1 = response(versionOne);
    assertThat(projectedV1.path("schemaVersion").asText()).isEqualTo("1");
    assertThat(projectedV1.at("/dateCandidates/0/candidateId").isMissingNode()).isTrue();
    assertThat(projectedV1.at("/itemCandidates/0/dueDateCandidateId").isMissingNode()).isTrue();
    assertThat(projectedV1.at("/itemCandidates/0/eventScheduleCandidates").isMissingNode())
        .isTrue();
    proposalSchemaValidator.validate(projectedV1);
    assertThat(storedProposal(proposalId)).isEqualTo(storedBefore);
  }

  @Test
  void recoveryUsesTheRequestedSupportedVersionAndRejectsUnsupportedVersions() throws Exception {
    UUID proposalId = createBoundProposal("schema-recovery");

    var legacy =
        mvc.perform(
                get("/api/v1/analysis-proposals")
                    .param("status", "REVIEW_REQUIRED")
                    .param("limit", "1"))
            .andReturn();
    assertProposalResponseHeaders(legacy.getResponse());
    assertThat(response(legacy).at("/0/proposalId").asText()).isEqualTo(proposalId.toString());
    assertThat(response(legacy).at("/0/proposal/schemaVersion").asText()).isEqualTo("1");
    assertThat(response(legacy).at("/0/proposal/dateCandidates/0/candidateId").isMissingNode())
        .isTrue();
    assertThat(
            response(legacy).at("/0/proposal/itemCandidates/0/dueDateCandidateId").isMissingNode())
        .isTrue();
    proposalSchemaValidator.validate(response(legacy).at("/0/proposal"));

    var current =
        mvc.perform(
                get("/api/v1/analysis-proposals")
                    .param("status", "REVIEW_REQUIRED")
                    .param("limit", "1")
                    .header(PROPOSAL_SCHEMA_VERSION_HEADER, "2"))
            .andReturn();
    assertProposalResponseHeaders(current.getResponse());
    assertThat(response(current).at("/0/proposal/schemaVersion").asText()).isEqualTo("2");
    assertThat(response(current).at("/0/proposal/dateCandidates/0/candidateId").asText())
        .isEqualTo("date-1");
    assertThat(response(current).at("/0/proposal/itemCandidates/0/dueDateCandidateId").asText())
        .isEqualTo("date-1");

    var unsupportedSingle =
        mvc.perform(
                get("/api/v1/analysis-proposals/{id}", UUID.randomUUID())
                    .header(PROPOSAL_SCHEMA_VERSION_HEADER, "4"))
            .andReturn();
    assertThat(unsupportedSingle.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(unsupportedSingle).path("code").asText())
        .isEqualTo("UNSUPPORTED_PROPOSAL_SCHEMA_VERSION");

    var unsupportedRecovery =
        mvc.perform(
                get("/api/v1/analysis-proposals")
                    .param("status", "REVIEW_REQUIRED")
                    .header(PROPOSAL_SCHEMA_VERSION_HEADER, "1,2"))
            .andReturn();
    assertThat(unsupportedRecovery.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(unsupportedRecovery).path("code").asText())
        .isEqualTo("UNSUPPORTED_PROPOSAL_SCHEMA_VERSION");
  }

  @Test
  void rejectsAnUnsupportedStoredProposalVersionBeforeReturningTheRequestedV3() throws Exception {
    UUID proposalId = createBoundProposal("schema-corrupt");
    db.sql(
            "update analysis_proposals "
                + "set proposal_json=jsonb_set(proposal_json,'{schemaVersion}',to_jsonb(cast('4' as text))) "
                + "where id=:proposalId and owner_id=:ownerId")
        .param("proposalId", proposalId)
        .param("ownerId", OWNER_ID)
        .update();

    assertThatThrownBy(() -> analysisService.proposal(proposalId, "3"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Stored analysis proposal has an unsupported schema version.");
  }

  private UUID createBoundProposal(String keyPrefix) throws Exception {
    UUID memoId = UUID.randomUUID();
    assertThat(
            createMemo(memoId, keyPrefix + "-create", "11.25 OS\uACFC\uC81C \uC81C\uCD9C")
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    var started = startAnalysis(memoId, keyPrefix + "-start", 1);
    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    return UUID.fromString(response(started).path("proposalId").asText());
  }

  private JsonNode storedProposal(UUID proposalId) throws Exception {
    String proposal =
        db.sql(
                "select proposal_json::text from analysis_proposals "
                    + "where id=:proposalId and owner_id=:ownerId")
            .param("proposalId", proposalId)
            .param("ownerId", OWNER_ID)
            .query(String.class)
            .single();
    return json.readTree(proposal);
  }

  private void promoteStoredProposalToVersionThree(UUID proposalId) {
    db.sql(
            "update analysis_proposals set proposal_json="
                + "jsonb_set(jsonb_set(jsonb_set(proposal_json,"
                + "'{schemaVersion}',to_jsonb(cast('3' as text))),"
                + "'{itemCandidates,0,eventScheduleCandidates}','[]'::jsonb),"
                + "'{itemCandidates,0,suggestedEventScheduleCandidateId}','null'::jsonb) "
                + "where id=:proposalId and owner_id=:ownerId")
        .param("proposalId", proposalId)
        .param("ownerId", OWNER_ID)
        .update();
    db.sql(
            "update analysis_runs set schema_version='3' "
                + "where owner_id=:ownerId and id=(select analysis_run_id from analysis_proposals "
                + "where id=:proposalId and owner_id=:ownerId)")
        .param("proposalId", proposalId)
        .param("ownerId", OWNER_ID)
        .update();
  }

  private void assertProposalResponseHeaders(
      org.springframework.mock.web.MockHttpServletResponse response) {
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(response.getHeader("Vary")).isEqualTo(PROPOSAL_SCHEMA_VERSION_HEADER);
  }
}

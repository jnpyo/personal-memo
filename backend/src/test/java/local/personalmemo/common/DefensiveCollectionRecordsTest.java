package local.personalmemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.api.AnalysisDtos;
import local.personalmemo.analysis.domain.AmbiguityReason;
import local.personalmemo.analysis.domain.AnalysisApplicationValidator;
import local.personalmemo.analysis.domain.DatePrecision;
import local.personalmemo.analysis.domain.KoreanDateParser;
import local.personalmemo.auth.api.AuthDtos;
import local.personalmemo.graph.api.GraphDtos;
import org.junit.jupiter.api.Test;

class DefensiveCollectionRecordsTest {
  @Test
  void analysisApplySnapshotsAndProtectsRequestCollections() {
    var selectedTag = new AnalysisDtos.Tag(null, "portfolio");
    var item = new AnalysisDtos.Item("TASK", "Polish portfolio", null);
    List<AnalysisDtos.Tag> selectedTags = new ArrayList<>(List.of(selectedTag));
    List<AnalysisDtos.Item> items = new ArrayList<>(List.of(item));

    var apply = new AnalysisDtos.Apply(1, "TASK", "Polish portfolio", selectedTags, items);
    selectedTags.clear();
    items.clear();

    assertThat(apply.selectedTags()).containsExactly(selectedTag);
    assertThat(apply.items()).containsExactly(item);
    assertThatThrownBy(() -> apply.selectedTags().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> apply.items().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void validatedApplySnapshotsAndProtectsValidatedCollections() {
    var tag = new AnalysisApplicationValidator.ValidatedTag(null, "portfolio", "portfolio");
    var item = new AnalysisApplicationValidator.ValidatedItem("TASK", "Polish portfolio", null);
    List<AnalysisApplicationValidator.ValidatedTag> tags = new ArrayList<>(List.of(tag));
    List<AnalysisApplicationValidator.ValidatedItem> items = new ArrayList<>(List.of(item));

    var apply =
        new AnalysisApplicationValidator.ValidatedApply(1, "TASK", "Polish portfolio", tags, items);
    tags.clear();
    items.clear();

    assertThat(apply.selectedTags()).containsExactly(tag);
    assertThat(apply.items()).containsExactly(item);
    assertThatThrownBy(() -> apply.selectedTags().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> apply.items().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void parsedDateSnapshotsAndProtectsAmbiguityReasons() {
    Set<AmbiguityReason> reasons = new HashSet<>(Set.of(AmbiguityReason.MISSING_TIME));

    var parsedDate =
        new KoreanDateParser.ParsedDate(
            "11.25", 0, 5, "2026-11-25", DatePrecision.DATE_ONLY, false, 0.9, reasons);
    reasons.clear();

    assertThat(parsedDate.ambiguityReasons()).containsExactly(AmbiguityReason.MISSING_TIME);
    assertThatThrownBy(() -> parsedDate.ambiguityReasons().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void graphAndAuthViewsSnapshotAndProtectTheirCollections() {
    var node = new GraphDtos.Node("memo-1", "MEMO", "Memo", "TASK", null, false);
    var edge = new GraphDtos.Edge("edge-1", "memo-1", "tag-1", "TAGGED_WITH");
    List<GraphDtos.Node> nodes = new ArrayList<>(List.of(node));
    List<GraphDtos.Edge> edges = new ArrayList<>(List.of(edge));
    List<String> loginMethods = new ArrayList<>(List.of("LOCAL"));

    var home = new GraphDtos.Home(nodes, edges, false, UUID.randomUUID());
    var session =
        new AuthDtos.AuthSession(UUID.randomUUID(), "owner@example.com", "Owner", loginMethods);
    nodes.clear();
    edges.clear();
    loginMethods.clear();

    assertThat(home.nodes()).containsExactly(node);
    assertThat(home.edges()).containsExactly(edge);
    assertThat(session.loginMethods()).containsExactly("LOCAL");
    assertThatThrownBy(() -> home.nodes().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> home.edges().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> session.loginMethods().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}

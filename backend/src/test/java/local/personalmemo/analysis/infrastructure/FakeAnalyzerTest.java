package local.personalmemo.analysis.infrastructure;
import static org.assertj.core.api.Assertions.assertThat;
import tools.jackson.databind.ObjectMapper; import java.time.Instant; import java.util.UUID; import org.junit.jupiter.api.Test;
class FakeAnalyzerTest {
 @Test void preservesDateSurfaceAndResolvesAlias(){var result=new FakeAnalyzer(new ObjectMapper()).analyze(UUID.randomUUID(),1,"11.25 OS과제 제출",Instant.parse("2026-08-05T02:00:00Z"),"Asia/Seoul");assertThat(result.at("/dateCandidates/0/surfaceText").asText()).isEqualTo("11.25");assertThat(result.at("/dateCandidates/0/precision").asText()).isEqualTo("DATE_ONLY");assertThat(result.at("/tagCandidates/0/canonicalName").asText()).isEqualTo("운영체제");assertThat(result.at("/tagCandidates/0/isNewProposal").asBoolean()).isFalse();}
}

package local.personalmemo.analysis.infrastructure;
import tools.jackson.databind.node.ObjectNode; import local.personalmemo.analysis.domain.CloudAnalysisGateway; import org.springframework.stereotype.Component;
@Component public class FakeCloudAnalysisGateway implements CloudAnalysisGateway { public ObjectNode enrich(ObjectNode p){return p.deepCopy();} }

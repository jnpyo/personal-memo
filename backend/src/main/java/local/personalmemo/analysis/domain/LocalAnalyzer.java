package local.personalmemo.analysis.domain;
import tools.jackson.databind.node.ObjectNode; import java.time.Instant; import java.util.UUID;
public interface LocalAnalyzer { ObjectNode analyze(UUID memoId,int revision,String content,Instant baseInstant,String timeZone); }

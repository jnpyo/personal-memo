package local.personalmemo.analysis.infrastructure;
import tools.jackson.databind.*; import tools.jackson.databind.node.*; import java.time.*; import java.util.*; import java.util.regex.*; import local.personalmemo.analysis.domain.LocalAnalyzer; import org.springframework.stereotype.Component;
@Component public class FakeAnalyzer implements LocalAnalyzer {
  private final ObjectMapper json; public FakeAnalyzer(ObjectMapper json){this.json=json;}
  public ObjectNode analyze(UUID memoId,int revision,String content,Instant base,String zone){
    var root=json.createObjectNode(); root.put("schemaVersion","1").put("memoId",memoId.toString()).put("memoRevision",revision);
    String title=content.replaceFirst("^\\s*\\d{1,2}\\.\\d{1,2}\\s*","").trim(); root.set("suggestedTitle",json.createObjectNode().put("value",title).put("confidence",.95).put("needsConfirmation",true));
    root.set("typeCandidates",json.createArrayNode().add(json.createObjectNode().put("value","TASK").put("score",.96)));
    var dates=json.createArrayNode(); var m=Pattern.compile("(?<!\\d)(\\d{1,2})\\.(\\d{1,2})(?!\\d)").matcher(content); var ambiguity=json.createArrayNode();
    if(m.find()){int month=Integer.parseInt(m.group(1)),day=Integer.parseInt(m.group(2)); var local=LocalDateTime.ofInstant(base,ZoneId.of(zone)).toLocalDate(); var date=LocalDate.of(local.getYear(),month,day); if(date.isBefore(local))date=date.plusYears(1); dates.add(json.createObjectNode().put("surfaceText",m.group()).put("value",date.toString()).put("precision","DATE_ONLY").put("timeSpecified",false).put("confidence",.9).set("ambiguityReasons",json.createArrayNode().add("MISSING_YEAR").add("MISSING_TIME"))); ambiguity.add("MISSING_YEAR").add("MISSING_TIME");}
    root.set("dateCandidates",dates); var tags=json.createArrayNode(); if(content.toLowerCase(Locale.ROOT).contains("os"))tags.add(tag("10000000-0000-0000-0000-000000000001","운영체제","OS",.98)); if(content.contains("과제"))tags.add(tag("10000000-0000-0000-0000-000000000002","과제",null,.96)); root.set("tagCandidates",tags);
    var item=json.createObjectNode().put("candidateId","task-1").put("kind","TASK").put("title",title).putNull("sourceSpan").put("action","제출").put("object",title.replace("제출","").trim()).put("confidence",.95); root.set("itemCandidates",json.createArrayNode().add(item)); root.set("relationCandidates",json.createArrayNode()); root.set("ambiguityReasons",ambiguity); root.set("providerMetadata",json.createObjectNode().put("analyzerVersion","fake-v1").put("promptVersion","none").put("localModelVersion","fake-v1").put("embeddingModelVersion","none")); return root;
  }
  private ObjectNode tag(String id,String name,String alias,double score){var n=json.createObjectNode().put("existingTagId",id).put("canonicalName",name).put("score",score).put("isNewProposal",false);if(alias==null)n.putNull("matchedAlias");else n.put("matchedAlias",alias);return n;}
}

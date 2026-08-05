package local.personalmemo.memo.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import local.personalmemo.common.DevIdentity;
import local.personalmemo.memo.api.MemoDtos.Create;
import local.personalmemo.memo.api.MemoDtos.Update;
import local.personalmemo.memo.api.MemoDtos.View;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoService {
  private final JdbcClient db; private final DevIdentity identity;
  public MemoService(JdbcClient db, DevIdentity identity) { this.db=db; this.identity=identity; }

  @Transactional public View create(String key, Create command) {
    var prior=db.sql("select resource_id from idempotency_records where owner_id=:o and operation='MEMO_CREATE' and idempotency_key=:k").param("o",identity.ownerId()).param("k",key).query(UUID.class).optional();
    if(prior.isPresent()) return get(prior.get());
    var now=Timestamp.from(Instant.now());
    db.sql("insert into memos(id,owner_id,current_revision,status,pinned,created_at,updated_at) values(:id,:o,1,'ACTIVE',false,:n,:n)").param("id",command.id()).param("o",identity.ownerId()).param("n",now).update();
    db.sql("insert into memo_revisions(memo_id,revision,content,content_hash,created_at,created_by) values(:id,1,:c,:h,:n,:o)").param("id",command.id()).param("c",command.content()).param("h",hash(command.content())).param("n",now).param("o",identity.ownerId()).update();
    db.sql("insert into idempotency_records(owner_id,operation,idempotency_key,request_hash,resource_id,response_json,created_at) values(:o,'MEMO_CREATE',:k,:h,:id,'{}',:n)").param("o",identity.ownerId()).param("k",key).param("h",hash(command.toString())).param("id",command.id()).param("n",now).update();
    return get(command.id());
  }

  public View get(UUID id) {
    return db.sql("select m.id,m.current_revision,r.content,m.status,m.created_at from memos m join memo_revisions r on r.memo_id=m.id and r.revision=m.current_revision where m.id=:id and m.owner_id=:o").param("id",id).param("o",identity.ownerId()).query((rs,n)->new View(UUID.fromString(rs.getString(1)),rs.getInt(2),rs.getString(3),rs.getString(4),"SAVED",rs.getTimestamp(5).toInstant())).single();
  }

  @Transactional public View update(UUID id, Update command) {
    var current=get(id); if(current.currentRevision()!=command.expectedRevision()) throw new IllegalArgumentException("STALE_MEMO_REVISION");
    int next=current.currentRevision()+1; var now=Timestamp.from(Instant.now());
    db.sql("insert into memo_revisions values(:id,:r,:c,:h,:n,:o)").param("id",id).param("r",next).param("c",command.content()).param("h",hash(command.content())).param("n",now).param("o",identity.ownerId()).update();
    db.sql("update memos set current_revision=:r,updated_at=:n,version=version+1 where id=:id and owner_id=:o").param("r",next).param("n",now).param("id",id).param("o",identity.ownerId()).update();
    db.sql("update analysis_runs set status='STALE' where memo_id=:id and owner_id=:o and memo_revision<:r and status not in ('APPLIED','REJECTED','STALE')").param("id",id).param("o",identity.ownerId()).param("r",next).update();
    return get(id);
  }

  private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
}

package local.personalmemo.common.idempotency;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import local.personalmemo.common.DevIdentity;
import local.personalmemo.common.error.DomainException;
import local.personalmemo.common.security.Hashing;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotencyService {
  private static final int MAX_KEY_LENGTH = 128;

  private final JdbcClient db;
  private final DevIdentity identity;
  private final ObjectMapper json;
  private final Clock clock;

  public IdempotencyService(JdbcClient db, DevIdentity identity, ObjectMapper json) {
    this.db = db;
    this.identity = identity;
    this.json = json;
    this.clock = Clock.systemUTC();
  }

  /** Must be called inside the transaction that performs the protected mutation. */
  public Optional<StoredResult> find(String operation, String key, String requestHash) {
    String validatedKey = validateKey(key);
    acquireTransactionLock(operation, validatedKey);

    Optional<StoredRecord> stored =
        db.sql(
                """
                select request_hash, resource_id, response_json::text
                  from idempotency_records
                 where owner_id = :ownerId
                   and operation = :operation
                   and idempotency_key = :key
                """)
            .param("ownerId", identity.ownerId())
            .param("operation", operation)
            .param("key", validatedKey)
            .query(this::mapStoredRecord)
            .optional();

    if (stored.isEmpty()) {
      return Optional.empty();
    }
    if (!stored.get().requestHash().equals(requestHash)) {
      throw DomainException.conflict(
          "IDEMPOTENCY_KEY_REUSED",
          "The Idempotency-Key was already used with a different request.");
    }

    return Optional.of(
        new StoredResult(stored.get().resourceId(), parse(stored.get().responseJson())));
  }

  public void store(
      String operation, String key, String requestHash, UUID resourceId, Object response) {
    String responseJson = write(response);
    db.sql(
            """
            insert into idempotency_records(
              owner_id,
              operation,
              idempotency_key,
              request_hash,
              resource_id,
              response_json,
              created_at
            ) values (
              :ownerId,
              :operation,
              :key,
              :requestHash,
              :resourceId,
              cast(:responseJson as jsonb),
              :createdAt
            )
            """)
        .param("ownerId", identity.ownerId())
        .param("operation", operation)
        .param("key", validateKey(key))
        .param("requestHash", requestHash)
        .param("resourceId", resourceId)
        .param("responseJson", responseJson)
        .param("createdAt", Timestamp.from(Instant.now(clock)))
        .update();
  }

  public String hashRequest(Object request) {
    return Hashing.sha256(write(request));
  }

  public <T> T convert(JsonNode node, Class<T> responseType) {
    try {
      return json.treeToValue(node, responseType);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not restore an idempotent response.", exception);
    }
  }

  private void acquireTransactionLock(String operation, String key) {
    String lockScope = identity.ownerId() + ":" + operation + ":" + key;
    db.sql("select pg_advisory_xact_lock(hashtextextended(:lockScope, 0))")
        .param("lockScope", lockScope)
        .query(
            (resultSet, rowNumber) -> {
              resultSet.getObject(1);
              return rowNumber;
            })
        .single();
  }

  private StoredRecord mapStoredRecord(ResultSet resultSet, int rowNumber) throws SQLException {
    return new StoredRecord(
        resultSet.getString("request_hash"),
        resultSet.getObject("resource_id", UUID.class),
        resultSet.getString("response_json"));
  }

  private String validateKey(String key) {
    if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
      throw DomainException.invalid(
          "INVALID_IDEMPOTENCY_KEY",
          "Idempotency-Key must contain between 1 and 128 characters.");
    }
    return key;
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not serialize JSON.", exception);
    }
  }

  private JsonNode parse(String value) {
    try {
      return json.readTree(value);
    } catch (Exception exception) {
      throw new IllegalStateException("Could not parse a stored idempotent response.", exception);
    }
  }

  private record StoredRecord(String requestHash, UUID resourceId, String responseJson) {}

  public record StoredResult(UUID resourceId, JsonNode response) {}
}

package ssonin.searchapi.repository;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
import ssonin.searchapi.domain.ClientDetails;
import ssonin.searchapi.domain.GenericState;
import ssonin.searchapi.domain.error.ClientNotFoundError;
import ssonin.searchapi.domain.error.EmailAlreadyInUseError;
import ssonin.searchapi.service.ClientInput;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public final class ClientRepository {

  private final Pool pool;

  public ClientRepository(Pool pool) {
    this.pool = pool;
  }

  public Uni<ClientDetails> insert(UUID id, ClientInput input) {
    return pool.preparedQuery("""
        INSERT INTO clients (id, first_name, last_name, email, description)
        VALUES ($1, $2, $3, $4, $5)
        RETURNING id, created_at, updated_at, state, first_name, last_name, email, description;
        """
      )
      .execute(toRow(id, input))
      .map(rows -> rows.iterator().next())
      .map(ClientRepository::fromRow)
      .onFailure()
      .transform(ClientRepository::handleFailure);
  }

  public Uni<ClientDetails> get(UUID id) {
    return pool.preparedQuery("""
        SELECT id, created_at, updated_at, state, first_name, last_name, email, description
        FROM clients
        WHERE id = $1;
        """)
      .execute(Tuple.of(id))
      .map(rows -> {
        final var iterator = rows.iterator();
        if (iterator.hasNext()) {
          return ClientRepository.fromRow(iterator.next());
        }
        throw new ClientNotFoundError();
      });
  }

  private static Throwable handleFailure(Throwable t) {
    if (isDuplicateKeyError(t)) {
      return new EmailAlreadyInUseError(t);
    }
    return t;
  }

  private static boolean isDuplicateKeyError(Throwable t) {
    return (t instanceof PgException pgException) && "23505".equals(pgException.getSqlState());
  }

  private static Tuple toRow(UUID id, ClientInput input) {
    return Tuple.of(
      id,
      input.firstName(),
      input.lastName(),
      input.email(),
      input.description().orElse(null)
    );
  }

  private static ClientDetails fromRow(Row row) {
    return new ClientDetails(
      row.getUUID("id"),
      Instant.from(row.getOffsetDateTime("created_at")),
      Instant.from(row.getOffsetDateTime("updated_at")),
      GenericState.valueOf(row.getString("state")),
      row.getString("first_name"),
      row.getString("last_name"),
      row.getString("email"),
      Optional.ofNullable(row.getString("description"))
    );
  }
}

package ssonin.nvstech.repository;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.stream.Stream;

import static io.vertx.core.Future.succeededFuture;
import static java.util.Comparator.comparingDouble;
import static java.util.UUID.randomUUID;
import static org.slf4j.LoggerFactory.getLogger;
import static ssonin.nvstech.repository.SqlQueries.*;

public final class RepositoryVerticle extends VerticleBase {

  private static final Logger LOG = getLogger(RepositoryVerticle.class);

  private Pool pool;

  @Override
  public Future<?> start() {
    final var dbConfig = new PgConnectOptions(config().getJsonObject("db"));
    pool = PgBuilder
      .pool()
      .connectingTo(dbConfig)
      .with(new PoolOptions())
      .using(vertx)
      .build();
    final var eb = vertx.eventBus();
    eb.consumer("clients.create", this::createClient);
    eb.consumer("clients.get", this::getClient);
    eb.consumer("documents.create", this::createDocument);
    eb.consumer("search", this::search);
    return succeededFuture();
  }

  private void createClient(Message<JsonObject> msg) {
    final var data = msg.body();
    final var values = Tuple.of(
      randomUUID(),
      data.getString("first_name"),
      data.getString("last_name"),
      data.getString("email"),
      data.getString("description"));
    pool
      .withConnection(conn ->
        conn.preparedQuery(insertClient())
          .execute(values)
          .map(rows -> clientFromRow(rows.iterator().next())))
      .onSuccess(msg::reply)
      .onFailure(handleError(msg));
  }

  private void getClient(Message<JsonObject> msg) {
    final var clientId = msg.body().getString("clientId");
    pool
      .withConnection(conn ->
        conn.preparedQuery(selectClient())
          .execute(Tuple.of(clientId))
          .map(rows -> {
            final var it = rows.iterator();
            if (it.hasNext()) {
              return clientFromRow(it.next());
            }
            throw new ClientNotFoundException();
          }))
      .onSuccess(msg::reply)
      .onFailure(handleError(msg));
  }

  private void createDocument(Message<JsonObject> msg) {
    final var data = msg.body();
    pool.withTransaction(conn ->
        conn.preparedQuery(selectClientForUpdate())
          .execute(Tuple.of(data.getString("client_id")))
          .compose(res -> {
            if (!res.iterator().hasNext()) {
              throw new ClientNotFoundException();
            }
            return fetchEmbeddings(data.getString("content"))
              .compose(embeddings -> {
                final var values = Tuple.of(
                  randomUUID(),
                  data.getString("client_id"),
                  data.getString("title"),
                  data.getString("content"),
                  embeddings.getJsonArray(0).toString());
                return conn.preparedQuery(insertDocument())
                  .execute(values)
                  .map(rows -> documentFromRow(rows.iterator().next()));
              });
          }))
      .onSuccess(msg::reply)
      .onFailure(handleError(msg));
  }

  private void search(Message<JsonObject> msg) {
    final Comparator<JsonObject> byRank = comparingDouble(it -> it.getDouble("rank"));
    final var query = msg.body().getString("query");
    fetchEmbeddings(query)
      .compose(embeddings -> Future.all(
        searchClients(query),
        searchDocuments(query, embeddings)))
      .map(composite -> {
        final JsonArray clients = composite.resultAt(0);
        final JsonArray documents = composite.resultAt(1);
        final var results = Stream.concat(clients.stream(), documents.stream())
          .map(it -> (JsonObject) it)
          .sorted(byRank.reversed())
          .toList();
        return new JsonArray(results);
      })
      .onSuccess(msg::reply)
      .onFailure(handleError(msg));
  }

  private Future<JsonArray> searchClients(String query) {
    final var values = Tuple.of(query);
    return pool
      .withConnection(conn ->
        conn.preparedQuery(SqlQueries.searchClients())
          .execute(values)
          .map(rows -> {
            final var result = new JsonArray();
            for (final var row : rows) {
              result.add(clientSearchResultFromRow(row));
            }
            return result;
          }));
  }

  private Future<JsonArray> searchDocuments(String query, JsonArray embeddings) {
    final var values = Tuple.of(query, embeddings.getJsonArray(0).toString());
    return pool
      .withConnection(conn ->
        conn.preparedQuery(SqlQueries.searchDocuments())
          .execute(values)
          .map(rows -> {
            final var result = new JsonArray();
            for (final var row : rows) {
              result.add(documentSearchResutFromRow(row));
            }
            return result;
          }));
  }

  private Future<JsonArray> fetchEmbeddings(String... texts) {
    final var message = new JsonObject()
      .put("texts", JsonArray.of(texts));
    return vertx.eventBus()
      .<JsonArray>request("embeddings.get", message)
      .map(Message::body);
  }

  private JsonObject clientFromRow(Row row) {
    return new JsonObject()
      .put("id", row.getUUID("id").toString())
      .put("created_at", row.getOffsetDateTime("created_at").toString())
      .put("first_name", row.getString("first_name"))
      .put("last_name", row.getString("last_name"))
      .put("email", row.getString("email"))
      .put("description", row.getString("description"));
  }

  private JsonObject documentFromRow(Row row) {
    return new JsonObject()
      .put("id", row.getUUID("id").toString())
      .put("created_at", row.getOffsetDateTime("created_at").toString())
      .put("client_id", row.getUUID("client_id").toString())
      .put("title", row.getString("title"))
      .put("content", row.getString("content"));
  }

  private JsonObject clientSearchResultFromRow(Row row) {
    return clientFromRow(row)
      .put("type", row.getString("type"))
      .put("rank", row.getDouble("rank"));
  }

  private JsonObject documentSearchResutFromRow(Row row) {
    return documentFromRow(row)
      .put("type", row.getString("type"))
      .put("rank", row.getDouble("rank"));
  }

  private static Handler<Throwable> handleError(Message<JsonObject> msg) {
    return e -> {
      LOG.error("Failed to execute query", e);
      if (duplicateKeyInsert(e)) {
        msg.fail(409, "Email is already in use");
      } else if (e instanceof NotFoundException) {
        msg.fail(404, e.getMessage());
      } else {
        msg.fail(500, "Something went wrong");
      }
    };
  }

  private static boolean duplicateKeyInsert(Throwable e) {
    if (e instanceof PgException pgException) {
      return "23505".equals(pgException.getSqlState());
    }
    return false;
  }
}

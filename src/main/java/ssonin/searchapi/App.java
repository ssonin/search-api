package ssonin.searchapi;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import ssonin.searchapi.api.ApiVerticle;
import ssonin.searchapi.repository.EmbeddingVerticle;
import ssonin.searchapi.repository.RepositoryVerticle;

import java.util.Optional;

import static java.util.function.Predicate.not;

public final class App extends VerticleBase {

  public static final int DEFAULT_HTTP_PORT = 8888;

  @Override
  public JsonObject config() {
    final var dbConfig = PgConnectOptions.fromEnv();
    return new JsonObject()
      .put("http.port", httpPort())
      .put("db", dbConfig.toJson())
      .put("services", new JsonObject()
        .put("embedding", new JsonObject()
          .put("host", System.getenv("EMBEDDING_SERVICE_HOST"))
          .put("port", Integer.parseInt(System.getenv("EMBEDDING_SERVICE_PORT")))));
  }

  @Override
  public Future<?> start() {
    final var config = config();
    return vertx.executeBlocking(() -> runDbMigration(config))
      .compose(__ -> {
          final var options = new DeploymentOptions().setConfig(config);
          return Future.all(
            vertx.deployVerticle(new ApiVerticle(), options),
            vertx.deployVerticle(new RepositoryVerticle(), options),
            vertx.deployVerticle(new EmbeddingVerticle(), options));
        }
      );
  }

  private MigrateResult runDbMigration(JsonObject config) {
    final var db = new PgConnectOptions(config.getJsonObject("db"));
    final var url = "jdbc:postgresql://%s:%d/%s".formatted(db.getHost(), db.getPort(), db.getDatabase());
    return Flyway.configure()
      .dataSource(url, db.getUser(), db.getPassword())
      .schemas("public")
      .locations("classpath:db/migration")
      .validateMigrationNaming(true)
      .load()
      .migrate();
  }

  private static int httpPort() {
    return Optional.ofNullable(System.getenv("HTTP_PORT"))
      .or(() -> Optional.ofNullable(System.getenv("PORT")))
      .filter(not(String::isBlank))
      .map(Integer::parseInt)
      .orElse(DEFAULT_HTTP_PORT);
  }
}

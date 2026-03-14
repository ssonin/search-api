package ssonin.searchapi;

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith({SystemStubsExtension.class, VertxExtension.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppTest {

  private static final int TEST_HTTP_PORT = 9999;

  @Container
  private static final PostgreSQLContainer<?> postgres =
    new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
      .withDatabaseName("search_api_test")
      .withUsername("test_user")
      .withPassword("test_password");

  @SystemStub
  private static EnvironmentVariables environmentVariables;

  private WebClient webClient;
  private String deploymentId;

  @BeforeAll
  void setup(Vertx vertx, VertxTestContext ctx) {
    environmentVariables
      .set("PGHOST", postgres.getHost())
      .set("PGPORT", String.valueOf(postgres.getMappedPort(5432)))
      .set("PGDATABASE", postgres.getDatabaseName())
      .set("PGUSER", postgres.getUsername())
      .set("PGPASSWORD", postgres.getPassword())
      .set("HTTP_PORT", String.valueOf(TEST_HTTP_PORT))
      .set("EMBEDDING_SERVICE_HOST", "localhost")
      .set("EMBEDDING_SERVICE_PORT", "8080")
      .set("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");

    webClient = WebClient.create(vertx, new WebClientOptions()
      .setDefaultHost("localhost")
      .setDefaultPort(TEST_HTTP_PORT));

    vertx.deployVerticle(new App())
      .onComplete(ctx.succeeding(id -> {
        deploymentId = id;
        ctx.completeNow();
      }));
  }

  @AfterAll
  void tearDown(Vertx vertx, VertxTestContext ctx) {
    if (webClient != null) {
      webClient.close();
    }
    if (deploymentId != null) {
      vertx.undeploy(deploymentId)
        .onComplete(ctx.succeedingThenComplete());
    } else {
      ctx.completeNow();
    }
  }

  @Test
  @DisplayName("App bootstraps successfully with all child verticles deployed")
  void bootstraps_app_successfully(VertxTestContext ctx) {
    ctx.verify(() -> {
      assertThat(deploymentId)
        .as("App deployment must complete, indicating all child verticles started")
        .isNotNull()
        .isNotBlank();
      ctx.completeNow();
    });
  }

  @Test
  @DisplayName("HTTP server accepts connections after bootstrap")
  void http_server_accepts_connections(VertxTestContext ctx) {
    var nonExistentClientId = "00000000-0000-0000-0000-000000000000";

    webClient.get("/api/v1/clients/" + nonExistentClientId)
      .send()
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode())
          .as("HTTP server must be accepting requests (404 confirms routing works)")
          .isEqualTo(404);
        ctx.completeNow();
      })));
  }

  @Test
  @DisplayName("Database migrations complete successfully")
  void completes_database_migrations(Vertx vertx, VertxTestContext ctx) {
    var pgClient = PgBuilder.client()
      .connectingTo(new PgConnectOptions()
        .setHost(postgres.getHost())
        .setPort(postgres.getMappedPort(5432))
        .setDatabase(postgres.getDatabaseName())
        .setUser(postgres.getUsername())
        .setPassword(postgres.getPassword()))
      .using(vertx)
      .build();

    pgClient.query("SELECT COUNT(*) FROM flyway_schema_history")
      .execute()
      .onComplete(ctx.succeeding(rows -> ctx.verify(() -> {
        var migrationCount = rows.iterator().next().getLong(0);
        assertThat(migrationCount)
          .as("Flyway must have applied at least one migration")
          .isGreaterThan(0);
        pgClient.close();
        ctx.completeNow();
      })));
  }
}

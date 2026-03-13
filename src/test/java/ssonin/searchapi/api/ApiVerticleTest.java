package ssonin.searchapi.api;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ssonin.searchapi.embedding.EmbeddingVerticle;
import ssonin.searchapi.repository.RepositoryVerticle;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith(VertxExtension.class)
@WireMockTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiVerticleTest {

  private static final int HTTP_PORT = 18888;
  private static final String API_V1 = "/api/v1";
  private static final String EMBEDDINGS_ENDPOINT = "/embeddings";

  @Container
  private static final PostgreSQLContainer<?> postgres =
    new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
      .withDatabaseName("search_api_test")
      .withUsername("test_user")
      .withPassword("test_password");

  private WebClient webClient;
  private String createdClientId;

  @BeforeAll
  void setup(Vertx vertx, VertxTestContext ctx, WireMockRuntimeInfo wmRuntimeInfo) {
    var jdbcUrl = postgres.getJdbcUrl();
    Flyway.configure()
      .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
      .schemas("public")
      .locations("classpath:db/migration")
      .validateMigrationNaming(true)
      .load()
      .migrate();

    var dbConfig = new JsonObject()
      .put("host", postgres.getHost())
      .put("port", postgres.getMappedPort(5432))
      .put("database", postgres.getDatabaseName())
      .put("user", postgres.getUsername())
      .put("password", postgres.getPassword());

    var servicesConfig = new JsonObject()
      .put("embedding", new JsonObject()
        .put("host", "localhost")
        .put("port", wmRuntimeInfo.getHttpPort()));

    var config = new JsonObject()
      .put("db", dbConfig)
      .put("http.port", HTTP_PORT)
      .put("services", servicesConfig);

    var options = new DeploymentOptions().setConfig(config);

    var clientOptions = new WebClientOptions()
      .setDefaultHost("localhost")
      .setDefaultPort(HTTP_PORT);

    webClient = WebClient.create(vertx, clientOptions);

    vertx.deployVerticle(new RepositoryVerticle(), options)
      .compose(__ -> vertx.deployVerticle(new EmbeddingVerticle(), options))
      .compose(__ -> vertx.deployVerticle(new ApiVerticle(), options))
      .onComplete(ctx.succeedingThenComplete());
  }

  @BeforeEach
  void stubEmbeddingService() {
    stubFor(post(urlEqualTo(EMBEDDINGS_ENDPOINT))
      .willReturn(aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(new JsonObject()
          .put("embeddings", new JsonArray().add(generateMockEmbedding()))
          .encode())));
  }

  @AfterEach
  void resetStubs() {
    WireMock.reset();
  }

  @AfterAll
  void tearDown() {
    if (webClient != null) {
      webClient.close();
    }
  }

  private JsonArray generateMockEmbedding() {
    var embedding = new JsonArray();
    for (var i = 0; i < 384; i++) {
      embedding.add(Math.sin(i * 0.1) * 0.5);
    }
    return embedding;
  }

  @Test
  @Order(1)
  @DisplayName("POST /clients: returns 201 with Location header")
  void creates_client_with_location_header(VertxTestContext ctx) {
    var clientData = new JsonObject()
      .put("first_name", "Monica")
      .put("last_name", "Geller")
      .put("email", "monica.geller@neviswealth.com");

    webClient.post(API_V1 + "/clients")
      .putHeader("Content-Type", "application/json")
      .sendJsonObject(clientData)
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.getHeader("Content-Type")).isEqualTo("application/json");
        assertThat(response.getHeader("Location")).isNotNull();

        createdClientId = response.bodyAsJsonObject().getString("id");
        assertThat(response.getHeader("Location")).endsWith("/" + createdClientId);

        ctx.completeNow();
      })));
  }

  @Test
  @Order(2)
  @DisplayName("POST /clients: returns 400 when body is empty")
  void returns_400_when_body_empty(VertxTestContext ctx) {
    webClient.post(API_V1 + "/clients")
      .putHeader("Content-Type", "application/json")
      .send()
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.getHeader("Content-Type")).isEqualTo("application/json");
        ctx.completeNow();
      })));
  }

  @Test
  @Order(3)
  @DisplayName("POST /clients: returns 400 when JSON is malformed")
  void returns_400_when_json_malformed(VertxTestContext ctx) {
    webClient.post(API_V1 + "/clients")
      .putHeader("Content-Type", "application/json")
      .sendBuffer(Buffer.buffer("{invalid json"))
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(400);
        ctx.completeNow();
      })));
  }

  @Test
  @Order(4)
  @DisplayName("POST /clients: returns 400 when required field missing")
  void returns_400_when_required_field_missing(VertxTestContext ctx) {
    var invalidClient = new JsonObject()
      .put("first_name", "Test");

    webClient.post(API_V1 + "/clients")
      .putHeader("Content-Type", "application/json")
      .sendJsonObject(invalidClient)
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(400);
        ctx.completeNow();
      })));
  }

  @Test
  @Order(5)
  @DisplayName("POST /clients: returns 400 when email format invalid")
  void returns_400_when_email_invalid(VertxTestContext ctx) {
    var invalidClient = new JsonObject()
      .put("first_name", "Test")
      .put("last_name", "User")
      .put("email", "not-an-email");

    webClient.post(API_V1 + "/clients")
      .putHeader("Content-Type", "application/json")
      .sendJsonObject(invalidClient)
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(400);
        ctx.completeNow();
      })));
  }

  @Test
  @Order(10)
  @DisplayName("GET /clients/:id: returns 400 when ID is not a UUID")
  void returns_400_when_client_id_invalid(VertxTestContext ctx) {
    webClient.get(API_V1 + "/clients/not-a-uuid")
      .send()
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(400);

        var body = response.bodyAsJsonObject();
        assertThat(body.getString("error")).containsIgnoringCase("Invalid client ID");

        ctx.completeNow();
      })));
  }

  @Test
  @Order(11)
  @DisplayName("GET /clients/:id: returns 404 with JSON error body")
  void returns_404_with_json_error(VertxTestContext ctx) {
    var nonExistentId = UUID.randomUUID().toString();

    webClient.get(API_V1 + "/clients/" + nonExistentId)
      .send()
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.getHeader("Content-Type")).isEqualTo("application/json");

        var body = response.bodyAsJsonObject();
        assertThat(body.getString("error")).isEqualTo("Client not found");

        ctx.completeNow();
      })));
  }

  @Test
  @Order(20)
  @DisplayName("POST /clients/:id/documents: returns 201 with Location header")
  void creates_document_with_location_header(VertxTestContext ctx) {
    var documentData = new JsonObject()
      .put("title", "Test Document")
      .put("content", "Test content for document creation");

    webClient.post(API_V1 + "/clients/" + createdClientId + "/documents")
      .putHeader("Content-Type", "application/json")
      .sendJsonObject(documentData)
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.getHeader("Content-Type")).isEqualTo("application/json");
        assertThat(response.getHeader("Location")).isNotNull();

        var documentId = response.bodyAsJsonObject().getString("id");
        assertThat(response.getHeader("Location")).endsWith("/" + documentId);

        ctx.completeNow();
      })));
  }

  @Test
  @Order(21)
  @DisplayName("POST /clients/:id/documents: returns 400 when body is empty")
  void returns_400_when_document_body_empty(VertxTestContext ctx) {
    webClient.post(API_V1 + "/clients/" + createdClientId + "/documents")
      .putHeader("Content-Type", "application/json")
      .send()
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(400);
        ctx.completeNow();
      })));
  }

  @Test
  @Order(22)
  @DisplayName("POST /clients/:id/documents: returns 400 when title is empty")
  void returns_400_when_title_empty(VertxTestContext ctx) {
    var invalidDocument = new JsonObject()
      .put("title", "")
      .put("content", "Some content");

    webClient.post(API_V1 + "/clients/" + createdClientId + "/documents")
      .putHeader("Content-Type", "application/json")
      .sendJsonObject(invalidDocument)
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(400);
        ctx.completeNow();
      })));
  }

  @Test
  @Order(23)
  @DisplayName("POST /clients/:id/documents: returns 400 when client ID invalid")
  void returns_400_when_document_client_id_invalid(VertxTestContext ctx) {
    var documentData = new JsonObject()
      .put("title", "Test")
      .put("content", "Content");

    webClient.post(API_V1 + "/clients/invalid-uuid/documents")
      .putHeader("Content-Type", "application/json")
      .sendJsonObject(documentData)
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(400);

        var body = response.bodyAsJsonObject();
        assertThat(body.getString("error")).containsIgnoringCase("Invalid client ID");

        ctx.completeNow();
      })));
  }

  @Test
  @Order(30)
  @DisplayName("GET /search: returns 400 when query parameter missing")
  void returns_400_when_query_missing(VertxTestContext ctx) {
    webClient.get(API_V1 + "/search")
      .send()
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(400);
        ctx.completeNow();
      })));
  }

  @Test
  @Order(31)
  @DisplayName("GET /search: returns 400 when query parameter empty")
  void returns_400_when_query_empty(VertxTestContext ctx) {
    webClient.get(API_V1 + "/search")
      .addQueryParam("q", "")
      .send()
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(400);
        ctx.completeNow();
      })));
  }

  @Test
  @Order(32)
  @DisplayName("GET /search: returns 400 when query parameter is whitespace")
  void returns_400_when_query_whitespace(VertxTestContext ctx) {
    webClient.get(API_V1 + "/search")
      .addQueryParam("q", "   ")
      .send()
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(400);
        ctx.completeNow();
      })));
  }

  @Test
  @Order(33)
  @DisplayName("GET /search: returns 200 with JSON array")
  void returns_200_with_json_array(VertxTestContext ctx) {
    webClient.get(API_V1 + "/search")
      .addQueryParam("q", "Monica")
      .send()
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.getHeader("Content-Type")).isEqualTo("application/json");

        var results = response.bodyAsJsonArray();
        assertThat(results).isNotNull();

        ctx.completeNow();
      })));
  }

  @Test
  @Order(40)
  @DisplayName("GET /search: returns 500 when embedding service unavailable")
  void returns_500_when_embedding_service_down(VertxTestContext ctx) {
    stubFor(post(urlEqualTo(EMBEDDINGS_ENDPOINT))
      .willReturn(serverError()));

    webClient.get(API_V1 + "/search")
      .addQueryParam("q", "test query")
      .send()
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(500);
        ctx.completeNow();
      })));
  }

  @Test
  @Order(41)
  @DisplayName("POST /clients/:id/documents: succeeds even when embedding service unavailable")
  void succeeds_when_embedding_service_down_on_document_create(VertxTestContext ctx) {
    stubFor(post(urlEqualTo(EMBEDDINGS_ENDPOINT))
      .willReturn(serverError()));

    var documentData = new JsonObject()
      .put("title", "Test")
      .put("content", "Content");

    webClient.post(API_V1 + "/clients/" + createdClientId + "/documents")
      .putHeader("Content-Type", "application/json")
      .sendJsonObject(documentData)
      .onComplete(ctx.succeeding(response -> ctx.verify(() -> {
        assertThat(response.statusCode()).isEqualTo(201);
        ctx.completeNow();
      })));
  }
}

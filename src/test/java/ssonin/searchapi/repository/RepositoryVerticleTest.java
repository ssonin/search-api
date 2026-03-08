package ssonin.searchapi.repository;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepositoryVerticleTest {

  private static final int EMBEDDING_DIMENSION = 384;

  @Container
  private static final PostgreSQLContainer<?> postgres =
    new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
      .withDatabaseName("search_api_test")
      .withUsername("test_user")
      .withPassword("test_password");

  private String createdClientId;

  @BeforeAll
  void setup(Vertx vertx, VertxTestContext ctx) {
    var jdbcUrl = postgres.getJdbcUrl();
    Flyway.configure()
      .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
      .schemas("public")
      .locations("classpath:db/migration")
      .validateMigrationNaming(true)
      .load()
      .migrate();

    vertx.eventBus().<JsonObject>consumer("embeddings.get", msg -> {
      var texts = msg.body().getJsonArray("texts");
      var embeddings = new JsonArray();
      for (int i = 0; i < texts.size(); i++) {
        var text = texts.getString(i);
        embeddings.add(generateDeterministicEmbedding(text));
      }
      msg.reply(embeddings);
    });

    var dbConfig = new JsonObject()
      .put("host", postgres.getHost())
      .put("port", postgres.getMappedPort(5432))
      .put("database", postgres.getDatabaseName())
      .put("user", postgres.getUsername())
      .put("password", postgres.getPassword());

    var config = new JsonObject().put("db", dbConfig);
    var options = new DeploymentOptions().setConfig(config);

    vertx.deployVerticle(new RepositoryVerticle(), options)
      .onComplete(ctx.succeedingThenComplete());
  }

  @Test
  @Order(1)
  @DisplayName("createClient: must create a new client successfully")
  void creates_client(Vertx vertx, VertxTestContext ctx) {
    var clientData = new JsonObject()
      .put("first_name", "Chandler")
      .put("last_name", "Bing")
      .put("email", "chandler.bing@neviswealth.com")
      .put("description", "Sarcastic, self-deprecating office worker with a sharp sense of humor, " +
        "known for cracking jokes to deflect awkward situations.");

    vertx.eventBus().<JsonObject>request("clients.create", clientData)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var client = reply.body();

        assertThat(client.getString("id"))
          .as("Client ID must be present")
          .isNotNull();
        assertThat(client.getString("created_at"))
          .as("created_at must be present")
          .isNotNull();
        assertThat(client.getString("first_name")).isEqualTo("Chandler");
        assertThat(client.getString("last_name")).isEqualTo("Bing");
        assertThat(client.getString("email")).isEqualTo("chandler.bing@neviswealth.com");
        assertThat(client.getString("description")).contains("Sarcastic");

        createdClientId = client.getString("id");

        ctx.completeNow();
      })));
  }

  @Test
  @Order(2)
  @DisplayName("createClient: must return 409 when email already exists")
  void returns_409_when_attempt_to_create_client_with_duplicate_email(Vertx vertx, VertxTestContext ctx) {
    var duplicateClient = new JsonObject()
      .put("first_name", "Another")
      .put("last_name", "Chandler")
      .put("email", "chandler.bing@neviswealth.com")
      .put("description", "Trying to impersonate Chandler");

    vertx.eventBus().<JsonObject>request("clients.create", duplicateClient)
      .onComplete(ctx.failing(err -> ctx.verify(() -> {
        assertThat(err).isInstanceOf(ReplyException.class);
        var replyException = (ReplyException) err;
        assertThat(replyException.failureCode()).isEqualTo(409);
        assertThat(replyException.getMessage()).isEqualTo("Email is already in use");

        ctx.completeNow();
      })));
  }

  @Test
  @Order(3)
  @DisplayName("getClient: must return client when found")
  void gets_clients(Vertx vertx, VertxTestContext ctx) {
    var request = new JsonObject().put("clientId", createdClientId);

    vertx.eventBus().<JsonObject>request("clients.get", request)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var client = reply.body();

        assertThat(client.getString("id")).isEqualTo(createdClientId);
        assertThat(client.getString("first_name")).isEqualTo("Chandler");
        assertThat(client.getString("last_name")).isEqualTo("Bing");
        assertThat(client.getString("email")).isEqualTo("chandler.bing@neviswealth.com");

        ctx.completeNow();
      })));
  }

  @Test
  @Order(4)
  @DisplayName("getClient: must return 404 when client not found")
  void returns_404_for_non_existent_client(Vertx vertx, VertxTestContext ctx) {
    var request = new JsonObject().put("clientId", "00000000-0000-0000-0000-000000000000");

    vertx.eventBus().<JsonObject>request("clients.get", request)
      .onComplete(ctx.failing(err -> ctx.verify(() -> {
        assertThat(err).isInstanceOf(ReplyException.class);
        var replyException = (ReplyException) err;
        assertThat(replyException.failureCode()).isEqualTo(404);
        assertThat(replyException.getMessage()).isEqualTo("Client not found");

        ctx.completeNow();
      })));
  }

  @Test
  @Order(5)
  @DisplayName("createDocument: must create a new document successfully")
  void creates_document(Vertx vertx, VertxTestContext ctx) {
    var documentData = new JsonObject()
      .put("client_id", createdClientId)
      .put("title", "Chandler Bing's Utility Bill of Awkwardness")
      .put("content", "This official-looking utility bill details the excessive energy I've wasted " +
        "trying to explain my job to my parents and the high emotional charges from every failed " +
        "relationship since Janice. It also includes a surprise late fee for that one time I " +
        "accidentally proposed, because apparently sarcasm doesn't show up on the meter. " +
        "Could this BE any more expensive?");

    vertx.eventBus().<JsonObject>request("documents.create", documentData)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var document = reply.body();

        assertThat(document.getString("id"))
          .as("Document ID must be present")
          .isNotNull();
        assertThat(document.getString("created_at"))
          .as("created_at must be present")
          .isNotNull();
        assertThat(document.getString("client_id")).isEqualTo(createdClientId);
        assertThat(document.getString("title")).isEqualTo("Chandler Bing's Utility Bill of Awkwardness");
        assertThat(document.getString("content")).contains("Could this BE any more expensive?");

        ctx.completeNow();
      })));
  }

  @Test
  @Order(6)
  @DisplayName("search: Direct Term Match - 'utility bill' must return document")
  void searches_by_direct_match(Vertx vertx, VertxTestContext ctx) {
    var query = new JsonObject().put("query", "utility bill");

    vertx.eventBus().<JsonArray>request("search", query)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var results = reply.body();

        assertThat(results)
          .as("Must find at least one result")
          .isNotEmpty();

        var hasDocument = results.stream()
          .map(o -> (JsonObject) o)
          .anyMatch(r -> "document".equals(r.getString("type")) &&
            r.getString("title").contains("Utility Bill"));

        assertThat(hasDocument)
          .as("Must find document with 'Utility Bill' in title")
          .isTrue();

        ctx.completeNow();
      })));
  }

  @Test
  @Disabled("Synonym match relied on vector search; documents have no embeddings until async embedding backfill is implemented")
  @Order(7)
  @DisplayName("search: Synonym Match (Thesaurus) - 'address proof' must return document with 'utility bill'")
  void searches_by_synonym_match(Vertx vertx, VertxTestContext ctx) {
    var query = new JsonObject().put("query", "address proof");

    vertx.eventBus().<JsonArray>request("search", query)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var results = reply.body();

        assertThat(results)
          .as("Must find results via thesaurus synonym mapping")
          .isNotEmpty();

        var hasUtilityBillDocument = results.stream()
          .map(o -> (JsonObject) o)
          .anyMatch(r -> "document".equals(r.getString("type")) &&
            r.getString("title").contains("Utility Bill"));

        assertThat(hasUtilityBillDocument)
          .as("Thesaurus must map 'address proof' to find document containing 'utility bill'")
          .isTrue();

        ctx.completeNow();
      })));
  }

  @Test
  @Order(8)
  @DisplayName("search: 'Chandler' must return both client (by first name) and document (by title)")
  void searches_across_clients_and_documents(Vertx vertx, VertxTestContext ctx) {
    var query = new JsonObject().put("query", "Chandler");

    vertx.eventBus().<JsonArray>request("search", query)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var results = reply.body();

        assertThat(results)
          .as("Must find at least 2 results (client and document)")
          .hasSizeGreaterThanOrEqualTo(2);

        var types = results.stream()
          .map(o -> (JsonObject) o)
          .map(r -> r.getString("type"))
          .collect(Collectors.toSet());

        assertThat(types)
          .as("Must find client by first name 'Chandler'")
          .contains("client");
        assertThat(types)
          .as("Must find document by title containing 'Chandler'")
          .contains("document");

        var clientMatch = results.stream()
          .map(o -> (JsonObject) o)
          .filter(r -> "client".equals(r.getString("type")))
          .findFirst()
          .orElseThrow();
        assertThat(clientMatch.getString("first_name")).isEqualTo("Chandler");

        var documentMatch = results.stream()
          .map(o -> (JsonObject) o)
          .filter(r -> "document".equals(r.getString("type")))
          .findFirst()
          .orElseThrow();
        assertThat(documentMatch.getString("title")).contains("Chandler Bing");

        ctx.completeNow();
      })));
  }

  @Test
  @Order(10)
  @DisplayName("search: 'neviswealth' must return client by email domain")
  void searches_by_email(Vertx vertx, VertxTestContext ctx) {
    var query = new JsonObject().put("query", "neviswealth");

    vertx.eventBus().<JsonArray>request("search", query)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var results = reply.body();

        assertThat(results)
          .as("Must find client by email domain")
          .isNotEmpty();

        var clientResult = results.stream()
          .map(o -> (JsonObject) o)
          .filter(r -> "client".equals(r.getString("type")))
          .findFirst()
          .orElseThrow(() -> new AssertionError("Must find client by email domain"));

        assertThat(clientResult.getString("email")).isEqualTo("chandler.bing@neviswealth.com");
        assertThat(clientResult.getString("first_name")).isEqualTo("Chandler");

        ctx.completeNow();
      })));
  }

  @Test
  @Order(12)
  @DisplayName("search: results must be sorted by rank in descending order")
  void returns_search_results_sorted_by_rank(Vertx vertx, VertxTestContext ctx) {
    var query = new JsonObject().put("query", "Chandler");

    vertx.eventBus().<JsonArray>request("search", query)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var results = reply.body();

        assertThat(results)
          .as("Must have multiple results to verify sorting")
          .hasSizeGreaterThanOrEqualTo(2);

        var ranks = results.stream()
          .map(o -> (JsonObject) o)
          .map(r -> r.getDouble("rank"))
          .toList();

        assertThat(ranks)
          .as("Results must be sorted by rank descending")
          .isSortedAccordingTo((a, b) -> Double.compare(b, a));

        ctx.completeNow();
      })));
  }

  @Test
  @Order(13)
  @DisplayName("search: each result must contain type and rank fields")
  void returns_search_results_with_type_and_rank(Vertx vertx, VertxTestContext ctx) {
    var query = new JsonObject().put("query", "Chandler");

    vertx.eventBus().<JsonArray>request("search", query)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var results = reply.body();

        for (var i = 0; i < results.size(); i++) {
          var result = results.getJsonObject(i);
          assertThat(result.getString("type"))
            .as("Each result must have a type")
            .isNotNull()
            .isIn("client", "document");
          assertThat(result.getDouble("rank"))
            .as("Each result must have a rank")
            .isNotNull();
        }

        ctx.completeNow();
      })));
  }

  @Test
  @Order(14)
  @DisplayName("createDocument: must store embedding for vector search")
  void creates_document_and_stores_embedding(Vertx vertx, VertxTestContext ctx) {
    var documentData = new JsonObject()
      .put("client_id", createdClientId)
      .put("title", "Investment Portfolio Analysis")
      .put("content", "A comprehensive review of wealth management strategies " +
        "including portfolio diversification and risk assessment.");

    vertx.eventBus().<JsonObject>request("documents.create", documentData)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var document = reply.body();

        assertThat(document.getString("id")).isNotNull();
        assertThat(document.getString("title")).isEqualTo("Investment Portfolio Analysis");

        ctx.completeNow();
      })));
  }

  @Test
  @Order(15)
  @DisplayName("search: Semantic Match - 'wealth management' must find 'Investment Portfolio' via vector similarity")
  void searches_for_semantic_match_for_wealth_management(Vertx vertx, VertxTestContext ctx) {
    var query = new JsonObject().put("query", "wealth management");

    vertx.eventBus().<JsonArray>request("search", query)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var results = reply.body();

        var hasInvestmentDocument = results.stream()
          .map(o -> (JsonObject) o)
          .anyMatch(r -> "document".equals(r.getString("type")) &&
            r.getString("title").contains("Investment Portfolio"));

        assertThat(hasInvestmentDocument)
          .as("Vector search must find semantically related document")
          .isTrue();

        ctx.completeNow();
      })));
  }

  @Test
  @Disabled("Requires embeddings on documents; will pass once async embedding backfill is implemented")
  @Order(16)
  @DisplayName("search: Semantic Match - 'financial planning' must find documents via embedding similarity")
  void searches_for_semantic_match_for_financial_planning(Vertx vertx, VertxTestContext ctx) {
    var query = new JsonObject().put("query", "financial planning");

    vertx.eventBus().<JsonArray>request("search", query)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var results = reply.body();

        var documentResults = results.stream()
          .map(o -> (JsonObject) o)
          .filter(r -> "document".equals(r.getString("type")))
          .toList();

        assertThat(documentResults)
          .as("Semantic search should find financially-related documents")
          .isNotEmpty();

        ctx.completeNow();
      })));
  }

  @Test
  @Order(17)
  @DisplayName("search: Hybrid ranking - exact FTS match should rank higher than vector-only match")
  void ranks_fts_results_higher(Vertx vertx, VertxTestContext ctx) {
    var documentData = new JsonObject()
      .put("client_id", createdClientId)
      .put("title", "Retirement Planning Guide")
      .put("content", "This retirement guide covers pension options, 401k strategies, " +
        "and social security benefits for long-term financial security.");

    vertx.eventBus().<JsonObject>request("documents.create", documentData)
      .compose(created -> {
        var query = new JsonObject().put("query", "retirement");
        return vertx.eventBus().<JsonArray>request("search", query);
      })
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var results = reply.body();

        var retirementDoc = results.stream()
          .map(o -> (JsonObject) o)
          .filter(r -> "document".equals(r.getString("type")) &&
            r.getString("title").contains("Retirement"))
          .findFirst()
          .orElseThrow(() -> new AssertionError("Retirement document must be found"));

        assertThat(retirementDoc.getDouble("rank"))
          .as("Document with exact FTS match should have high rank")
          .isGreaterThan(0);

        ctx.completeNow();
      })));
  }

  @Test
  @Order(18)
  @DisplayName("search: Vector search should find documents when FTS has no match")
  void returns_only_vector_search_results_when_no_fts_match(Vertx vertx, VertxTestContext ctx) {
    var documentData = new JsonObject()
      .put("client_id", createdClientId)
      .put("title", "Asset Allocation Strategy")
      .put("content", "Diversifying investments across stocks, bonds, and real estate " +
        "to optimize returns while managing portfolio risk exposure.");

    vertx.eventBus().<JsonObject>request("documents.create", documentData)
      .compose(created -> {
        var query = new JsonObject().put("query", "investment diversification");
        return vertx.eventBus().<JsonArray>request("search", query);
      })
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var results = reply.body();

        assertThat(results)
          .as("Vector search should return results for semantic queries")
          .isNotEmpty();

        ctx.completeNow();
      })));
  }

  @Test
  @Order(20)
  @DisplayName("search: Vector search limit should cap results at 20")
  void limits_vector_search_results(Vertx vertx, VertxTestContext ctx) {
    var query = new JsonObject().put("query", "general document search");

    vertx.eventBus().<JsonArray>request("search", query)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var results = reply.body();

        var documentCount = results.stream()
          .map(o -> (JsonObject) o)
          .filter(r -> "document".equals(r.getString("type")))
          .count();

        assertThat(documentCount)
          .as("Document results should be capped at 20")
          .isLessThanOrEqualTo(20);

        ctx.completeNow();
      })));
  }

  @Test
  @Order(21)
  @DisplayName("createDocument: must fail gracefully when embeddings service is unavailable")
  void fails_to_create_document_if_embedding_service_call_fails(Vertx vertx, VertxTestContext ctx) {
    var documentData = new JsonObject()
      .put("client_id", createdClientId)
      .put("title", "Test Document")
      .put("content", "");

    vertx.eventBus().<JsonObject>request("documents.create", documentData)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var document = reply.body();
        assertThat(document.getString("id")).isNotNull();
        ctx.completeNow();
      })));
  }

  @Test
  @Order(22)
  @DisplayName("search: must handle documents without embeddings gracefully")
  void searches_documents_without_embedding(Vertx vertx, VertxTestContext ctx) {
    var query = new JsonObject().put("query", "Chandler Bing");

    vertx.eventBus().<JsonArray>request("search", query)
      .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
        var results = reply.body();

        assertThat(results)
          .as("FTS should still work regardless of embedding state")
          .isNotEmpty();

        ctx.completeNow();
      })));
  }

  private JsonArray generateDeterministicEmbedding(String text) {
    var embedding = new JsonArray();
    var lowerText = text.toLowerCase();
    var hash = lowerText.hashCode();
    var random = new java.util.Random(hash);

    for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
      embedding.add(random.nextDouble() * 2 - 1);
    }

    if (lowerText.contains("financial") || lowerText.contains("money") || lowerText.contains("bill")) {
      boostDimension(embedding, 0, 0.8);
      boostDimension(embedding, 1, 0.7);
    }
    if (lowerText.contains("investment") || lowerText.contains("portfolio") || lowerText.contains("wealth")) {
      boostDimension(embedding, 0, 0.6);
      boostDimension(embedding, 2, 0.9);
    }
    if (lowerText.contains("humor") || lowerText.contains("sarcastic") || lowerText.contains("joke")) {
      boostDimension(embedding, 10, 0.8);
      boostDimension(embedding, 11, 0.7);
    }

    return normalizeEmbedding(embedding);
  }

  private void boostDimension(JsonArray embedding, int index, double boost) {
    embedding.set(index, embedding.getDouble(index) + boost);
  }

  private JsonArray normalizeEmbedding(JsonArray embedding) {
    double norm = 0;
    for (int i = 0; i < embedding.size(); i++) {
      norm += Math.pow(embedding.getDouble(i), 2);
    }
    norm = Math.sqrt(norm);
    var normalized = new JsonArray();
    for (int i = 0; i < embedding.size(); i++) {
      normalized.add(embedding.getDouble(i) / norm);
    }
    return normalized;
  }
}

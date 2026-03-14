package ssonin.searchapi.embedding;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(VertxExtension.class)
@WireMockTest
class EmbeddingVerticleTest {

  private static final String EMBEDDINGS_ADDRESS = "embeddings.get";
  private static final String EMBEDDINGS_ENDPOINT = "/embeddings";

  @BeforeEach
  void deploy_verticle(Vertx vertx, VertxTestContext testContext, WireMockRuntimeInfo wmRuntimeInfo) {
    var config = new JsonObject()
      .put("services", new JsonObject()
        .put("embedding", new JsonObject()
          .put("host", "localhost")
          .put("port", wmRuntimeInfo.getHttpPort())));

    vertx.deployVerticle(new EmbeddingVerticle(), new DeploymentOptions().setConfig(config))
      .onComplete(testContext.succeedingThenComplete());
  }

  @Test
  @DisplayName("returns embeddings when service responds successfully")
  void returns_embeddings_when_service_responds_successfully(
    Vertx vertx,
    VertxTestContext testContext
  ) throws InterruptedException {
    var inputTexts = new JsonArray()
      .add("hello world")
      .add("test embedding");

    var expectedEmbeddings = new JsonArray()
      .add(new JsonArray().add(0.1).add(0.2).add(0.3))
      .add(new JsonArray().add(0.4).add(0.5).add(0.6));

    var expectedRequestBody = new JsonObject()
      .put("texts", inputTexts);

    stubFor(post(urlEqualTo(EMBEDDINGS_ENDPOINT))
      .withHeader("Content-Type", containing("application/json"))
      .withRequestBody(equalToJson(expectedRequestBody.encode()))
      .willReturn(aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(new JsonObject()
          .put("embeddings", expectedEmbeddings)
          .encode())));

    var request = new JsonObject()
      .put("texts", inputTexts);

    vertx.eventBus().<JsonArray>request(EMBEDDINGS_ADDRESS, request)
      .onComplete(testContext.succeeding(reply -> testContext.verify(() -> {
        assertThat(reply.body())
          .isNotNull()
          .isEqualTo(expectedEmbeddings);

        assertThat(reply.body().size())
          .isEqualTo(2);

        verify(postRequestedFor(urlEqualTo(EMBEDDINGS_ENDPOINT))
          .withHeader("Content-Type", containing("application/json"))
          .withRequestBody(equalToJson(expectedRequestBody.encode())));

        testContext.completeNow();
      })));

    assertThat(testContext.awaitCompletion(5, TimeUnit.SECONDS))
      .isTrue();
  }

  @Test
  @DisplayName("returns empty array when service returns empty embeddings")
  void returns_empty_array_when_service_returns_empty_embeddings(
    Vertx vertx,
    VertxTestContext testContext
  ) throws InterruptedException {
    var emptyEmbeddings = new JsonArray();

    stubFor(post(urlEqualTo(EMBEDDINGS_ENDPOINT))
      .willReturn(aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(new JsonObject()
          .put("embeddings", emptyEmbeddings)
          .encode())));

    var request = new JsonObject()
      .put("texts", new JsonArray());

    vertx.eventBus().<JsonArray>request(EMBEDDINGS_ADDRESS, request)
      .onComplete(testContext.succeeding(reply -> testContext.verify(() -> {
        assertThat(reply.body())
          .isNotNull()
          .isEmpty();

        testContext.completeNow();
      })));

    assertThat(testContext.awaitCompletion(5, TimeUnit.SECONDS))
      .isTrue();
  }

  @Test
  @DisplayName("fails with error code 500 when service returns server error")
  void fails_with_error_500_when_service_returns_server_error(
    Vertx vertx,
    VertxTestContext testContext
  ) throws InterruptedException {
    stubFor(post(urlEqualTo(EMBEDDINGS_ENDPOINT))
      .willReturn(serverError()
        .withBody("Internal Server Error")));

    var request = new JsonObject()
      .put("texts", new JsonArray().add("test"));

    vertx.eventBus().<JsonArray>request(EMBEDDINGS_ADDRESS, request)
      .onComplete(testContext.failing(error -> testContext.verify(() -> {
        assertThat(error)
          .isInstanceOf(io.vertx.core.eventbus.ReplyException.class);

        var replyException = (io.vertx.core.eventbus.ReplyException) error;
        assertThat(replyException.failureCode())
          .isEqualTo(500);

        assertThat(replyException.getMessage())
          .contains("Failed to fetch embeddings");

        testContext.completeNow();
      })));

    assertThat(testContext.awaitCompletion(5, TimeUnit.SECONDS))
      .isTrue();
  }

  @Test
  @DisplayName("fails when service is unavailable")
  void fails_when_service_unavailable(
    Vertx vertx,
    VertxTestContext testContext
  ) throws InterruptedException {
    stubFor(post(urlEqualTo(EMBEDDINGS_ENDPOINT))
      .willReturn(aResponse()
        .withStatus(503)
        .withBody("Service Unavailable")));

    var request = new JsonObject()
      .put("texts", new JsonArray().add("test"));

    vertx.eventBus().<JsonArray>request(EMBEDDINGS_ADDRESS, request)
      .onComplete(testContext.failing(error -> testContext.verify(() -> {
        assertThat(error)
          .isInstanceOf(io.vertx.core.eventbus.ReplyException.class);

        var replyException = (io.vertx.core.eventbus.ReplyException) error;
        assertThat(replyException.failureCode())
          .isEqualTo(500);

        testContext.completeNow();
      })));

    assertThat(testContext.awaitCompletion(5, TimeUnit.SECONDS))
      .isTrue();
  }

  @Test
  @DisplayName("handles single text embedding request")
  void handles_single_text_embedding_request(
    Vertx vertx,
    VertxTestContext testContext
  ) throws InterruptedException {
    var singleText = new JsonArray().add("single text");
    var singleEmbedding = new JsonArray()
      .add(new JsonArray().add(0.7).add(0.8).add(0.9));

    stubFor(post(urlEqualTo(EMBEDDINGS_ENDPOINT))
      .willReturn(aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(new JsonObject()
          .put("embeddings", singleEmbedding)
          .encode())));

    var request = new JsonObject()
      .put("texts", singleText);

    vertx.eventBus().<JsonArray>request(EMBEDDINGS_ADDRESS, request)
      .onComplete(testContext.succeeding(reply -> testContext.verify(() -> {
        assertThat(reply.body())
          .isNotNull()
          .hasSize(1);

        var firstEmbedding = reply.body().getJsonArray(0);
        assertThat(firstEmbedding)
          .containsExactly(0.7, 0.8, 0.9);

        testContext.completeNow();
      })));

    assertThat(testContext.awaitCompletion(5, TimeUnit.SECONDS))
      .isTrue();
  }
}

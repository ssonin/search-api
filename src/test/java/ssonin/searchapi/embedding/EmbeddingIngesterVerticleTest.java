package ssonin.searchapi.embedding;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddingIngesterVerticleTest {

  @Container
  private static final KafkaContainer kafka =
    new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.9.0"));

  @BeforeAll
  void setup(Vertx vertx, VertxTestContext ctx) {
    var config = new JsonObject()
      .put("services", new JsonObject()
        .put("kafka", new JsonObject()
          .put("bootstrap.servers", kafka.getBootstrapServers())));

    vertx.deployVerticle(new EmbeddingIngesterVerticle(), new DeploymentOptions().setConfig(config))
      .onComplete(ctx.succeedingThenComplete());
  }

  @Test
  @DisplayName("must forward documentId and embedding to the documents.embedding.update event bus address")
  void forwards_embedding_to_event_bus(Vertx vertx, VertxTestContext ctx) {
    var docId = UUID.randomUUID().toString();
    var embedding = new JsonArray().add(0.1).add(0.2).add(0.3);

    vertx.eventBus().<JsonObject>consumer("documents.embedding.update", msg -> {
      ctx.verify(() -> {
        assertThat(msg.body().getString("documentId")).isEqualTo(docId);
        assertThat(msg.body().getJsonArray("embedding")).isEqualTo(embedding);
      });
      ctx.completeNow();
    });

    var producerConfig = Map.of(
      "bootstrap.servers", kafka.getBootstrapServers(),
      "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
      "value.serializer", "org.apache.kafka.common.serialization.StringSerializer"
    );
    var producer = KafkaProducer.<String, String>create(vertx, producerConfig);
    var message = new JsonObject()
      .put("documentId", docId)
      .put("embedding", embedding)
      .encode();

    producer.send(KafkaProducerRecord.create("searchapi.document.embedding", docId, message))
      .onFailure(ctx::failNow);
  }
}

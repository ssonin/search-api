package ssonin.searchapi.embedding;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.consumer.KafkaConsumer;

import java.util.Map;

public final class EmbeddingIngesterVerticle extends VerticleBase {

  @Override
  public Future<?> start() {
    final var config = config().getJsonObject("services").getJsonObject("kafka");
    final var kafkaConfig = Map.of(
      "bootstrap.servers", config.getString("bootstrap.servers", "kafka:9092"),
      "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
      "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
      "group.id", "embedding-consumer",
      "auto.offset.reset", "earliest"
    );
    var consumer = KafkaConsumer.<String, String>create(vertx, kafkaConfig);
    consumer.handler(event -> {
      var payload = new JsonObject(event.value());
      vertx.eventBus()
        .send("documents.embedding.update", new JsonObject()
          .put("documentId", payload.getString("documentId"))
          .put("embedding", payload.getJsonArray("embedding")));
    });
    return consumer.subscribe("searchapi.document.embedding");
  }
}

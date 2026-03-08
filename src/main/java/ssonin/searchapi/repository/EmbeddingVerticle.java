package ssonin.searchapi.repository;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import org.slf4j.Logger;

import static io.vertx.core.Future.succeededFuture;
import static org.slf4j.LoggerFactory.getLogger;

public final class EmbeddingVerticle extends VerticleBase {

  private static final Logger LOG = getLogger(EmbeddingVerticle.class);

  private String host;
  private int port;
  private WebClient webClient;

  @Override
  public Future<?> start() {
    final var embeddingConfig = config()
      .getJsonObject("services")
      .getJsonObject("embedding");
    host =  embeddingConfig.getString("host");
    port = embeddingConfig.getInteger("port");

    webClient = WebClient.create(vertx);
    final var eb = vertx.eventBus();
    eb.consumer("embeddings.get", this::getEmbeddings);
    return succeededFuture();
  }

  private void getEmbeddings(Message<JsonObject> msg) {
    final var body = msg.body();
    final var request = new JsonObject()
      .put("texts", body.getJsonArray("texts"));
    webClient.post(port, host, "/embeddings")
      .as(BodyCodec.jsonObject())
      .sendJson(request)
      .map(response -> response.body()
        .getJsonArray("embeddings"))
      .onSuccess(msg::reply)
      .onFailure(e -> {
        final var errorMessage = "Failed to fetch embeddings";
        LOG.error(errorMessage, e);
        msg.fail(500, errorMessage);
      });
  }
}

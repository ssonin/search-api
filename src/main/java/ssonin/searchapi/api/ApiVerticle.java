package ssonin.searchapi.api;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.json.schema.*;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.vertx.core.Future.failedFuture;
import static io.vertx.core.Future.succeededFuture;
import static io.vertx.json.schema.common.dsl.Keywords.minLength;
import static io.vertx.json.schema.common.dsl.Keywords.pattern;
import static io.vertx.json.schema.common.dsl.Schemas.objectSchema;
import static io.vertx.json.schema.common.dsl.Schemas.stringSchema;
import static org.slf4j.LoggerFactory.getLogger;

public final class ApiVerticle extends VerticleBase {

  private static final Logger LOG = getLogger(ApiVerticle.class);
  private static final String API_V_1 = "/api/v1";

  private Validator clientValidator;
  private Validator documentValidator;

  @Override
  public Future<?> start() {
    initialiseValidators();

    final var router = Router.router(vertx);
    router
      .route()
      .handler(LoggerHandler.create());
    router
      .post()
      .handler(BodyHandler.create());
    router
      .post(API_V_1 + "/clients")
      .handler(this::createClient);
    router
      .get(API_V_1 + "/clients/:clientId")
      .handler(this::getClient);
    router
      .post(API_V_1 + "/clients/:clientId/documents")
      .handler(this::createDocument);
    router
      .get(API_V_1 + "/search")
      .handler(this::search);
    router
      .route()
      .failureHandler(this::handleError);
    return vertx
      .createHttpServer()
      .requestHandler(router)
      .listen(config().getInteger("http.port"))
      .onSuccess(httpServer -> LOG.info("HTTP server started on port {}", httpServer.actualPort()));
  }

  private void initialiseValidators() {
    final var schemaOptions = new JsonSchemaOptions()
      .setDraft(Draft.DRAFT202012)
      .setBaseUri("https://search-api.local/schemas");

    final var clientSchemaJson = objectSchema()
      .requiredProperty("first_name", stringSchema().with(minLength(1)))
      .requiredProperty("last_name", stringSchema().with(minLength(1)))
      .requiredProperty("email", stringSchema()
        .with(minLength(1))
        .with(pattern(Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"))))
      .optionalProperty("description", stringSchema())
      .toJson();

    clientValidator = Validator.create(
      JsonSchema.of(clientSchemaJson),
      schemaOptions);

    final var documentSchemaJson = objectSchema()
      .requiredProperty("title", stringSchema().with(minLength(1)))
      .requiredProperty("content", stringSchema().with(minLength(1)))
      .toJson();

    documentValidator = Validator.create(
      JsonSchema.of(documentSchemaJson),
      schemaOptions);
  }

  private void createClient(RoutingContext ctx) {
    validatePayload(ctx, clientValidator)
      .compose(payload ->
        vertx.eventBus().<JsonObject>request("clients.create", payload))
      .onSuccess(reply ->
        ctx.response()
          .setStatusCode(201)
          .putHeader("Location", "%s/%s".formatted(ctx.request().absoluteURI(), reply.body().getString("id")))
          .putHeader("Content-Type", "application/json")
          .end(reply.body().toString()))
      .onFailure(ctx::fail);
  }

  private void getClient(RoutingContext ctx) {
    fetchClient(ctx)
      .onSuccess(reply ->
        ctx.response()
          .setStatusCode(200)
          .putHeader("Content-Type", "application/json")
          .end(reply.body().toString()))
      .onFailure(ctx::fail);
  }

  private void createDocument(RoutingContext ctx) {
    fetchClient(ctx)
      .compose(client ->
        validatePayload(ctx, documentValidator)
          .map(payload -> payload.put("client_id", client.body().getString("id"))))
      .compose(payload ->
        vertx.eventBus().<JsonObject>request("documents.create", payload))
      .onSuccess(reply ->
        ctx.response()
          .setStatusCode(201)
          .putHeader("Location", "%s/%s".formatted(ctx.request().absoluteURI(), reply.body().getString("id")))
          .putHeader("Content-Type", "application/json")
          .end(reply.body().toString()))
      .onFailure(ctx::fail);
  }

  private void search(RoutingContext ctx) {
    final var queryParam = ctx.request().getParam("q");
    if (queryParam == null || queryParam.isBlank()) {
      ctx.response().setStatusCode(400).end("Required query parameter is missing");
      return;
    }
    final var payload = new JsonObject().put("query", queryParam.toLowerCase());
    vertx.eventBus()
      .<JsonArray>request("search", payload)
      .onSuccess(reply ->
        ctx.response()
          .setStatusCode(200)
          .putHeader("Content-Type", "application/json")
          .end(reply.body().toString()))
      .onFailure(ctx::fail);
  }

  private Future<Message<JsonObject>> fetchClient(RoutingContext ctx) {
    return uuidPathParam(ctx)
      .compose(clientId -> {
        final var payload = new JsonObject().put("clientId", clientId.toString());
        return vertx.eventBus()
          .request("clients.get", payload);
      });
  }

  private Future<UUID> uuidPathParam(RoutingContext ctx) {
    final var clientId = ctx.pathParam("clientId");
    try {
      return succeededFuture(UUID.fromString(clientId));
    } catch (IllegalArgumentException e) {
      LOG.warn("Invalid client ID: {}", clientId);
      return failedFuture(new HttpException(400, "Invalid client ID", e));
    }
  }

  private Future<JsonObject> validatePayload(RoutingContext ctx, Validator validator) {
    final JsonObject payload;
    try {
      payload = ctx.body().asJsonObject();
    } catch (Exception e) {
      LOG.warn("Invalid JSON payload: {}", e.getMessage());
      return failedFuture(new HttpException(400, "Invalid JSON payload"));
    }

    if (payload == null) {
      return failedFuture(new HttpException(400, "Request body is required"));
    }

    final var result = validator.validate(payload);
    if (result.getValid()) {
      return succeededFuture(payload);
    }

    final var errorMessage = formatValidationErrors(result);
    LOG.warn("Payload validation failed: {}", errorMessage);
    return failedFuture(new HttpException(400, errorMessage));
  }

  private String formatValidationErrors(OutputUnit result) {
    final var errors = result.getErrors();
    if (errors == null || errors.isEmpty()) {
      return "Validation failed";
    }

    return errors.stream()
      .map(this::formatSingleError)
      .collect(Collectors.joining("; "));
  }

  private String formatSingleError(OutputUnit error) {
    final var instanceLocation = error.getInstanceLocation();
    final var errorMessage = error.getError();

    if (instanceLocation != null && !instanceLocation.isEmpty() && !"/".equals(instanceLocation)) {
      final var fieldName = instanceLocation.startsWith("/")
        ? instanceLocation.substring(1)
        : instanceLocation;
      return "%s: %s".formatted(fieldName, errorMessage);
    }

    return errorMessage != null ? errorMessage : "Validation failed";
  }

  private void handleError(RoutingContext ctx) {
    final var failure = ctx.failure();
    if (failure instanceof ReplyException e) {
      ctx.response()
        .setStatusCode(e.failureCode())
        .putHeader("Content-Type", "application/json")
        .end(new JsonObject().put("error", e.getMessage()).encode());
      return;
    }
    if (failure instanceof HttpException e) {
      ctx.response()
        .setStatusCode(e.getStatusCode())
        .putHeader("Content-Type", "application/json")
        .end(new JsonObject().put("error", e.getPayload()).encode());
      return;
    }
    LOG.error("Unhandled error", failure);
    ctx.response()
      .setStatusCode(500)
      .putHeader("Content-Type", "application/json")
      .end(new JsonObject().put("error", "Internal server error").encode());
  }
}

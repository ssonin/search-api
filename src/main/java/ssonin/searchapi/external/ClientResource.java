package ssonin.searchapi.external;

import io.quarkiverse.httpproblem.HttpProblem;
import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import ssonin.searchapi.domain.ClientDetails;
import ssonin.searchapi.domain.error.InvalidClientIdError;
import ssonin.searchapi.external.request.ClientCreateRequest;
import ssonin.searchapi.external.response.ClientResponse;
import ssonin.searchapi.service.ClientInput;
import ssonin.searchapi.service.ClientService;

import java.util.UUID;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.CREATED;
import static org.eclipse.microprofile.openapi.annotations.enums.SchemaType.STRING;

@Path("/clients")
public class ClientResource {

  private final ClientService clientService;

  public ClientResource(ClientService clientService) {
    this.clientService = clientService;
  }

  @POST
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  @APIResponse(
    responseCode = "201",
    description = "Client created",
    content = @Content(
      mediaType = APPLICATION_JSON,
      schema = @Schema(implementation = ClientResponse.class)
    ),
    headers = @Header(
      name = "Location",
      description = "URI of the created client resource",
      required = true,
      schema = @Schema(type = STRING, format = "uri-reference")
    )
  )
  @APIResponse(
    responseCode = "400",
    description = "Malformed request body or invalid client fields",
    content = @Content(
      mediaType = "application/problem+json",
      schema = @Schema(implementation = HttpProblem.class)
    )
  )
  @APIResponse(
    responseCode = "409",
    description = "Email already in use by an active client; comparison is case-insensitive",
    content = @Content(
      mediaType = "application/problem+json",
      schema = @Schema(implementation = HttpProblem.class)
    )
  )
  public Uni<RestResponse<ClientResponse>> createClient(@NotNull @Valid ClientCreateRequest request,
                                                        @Context UriInfo uriInfo) {
    final var pathBuilder = uriInfo.getAbsolutePathBuilder();
    return clientService.create(toClientInput(request))
      .map(ClientResource::toClientResponse)
      .map(client -> ResponseBuilder.create(CREATED, client)
        .location(pathBuilder.path(client.id().toString()).build())
        .build());
  }

  @GET
  @Path("/{clientId}")
  @Produces(APPLICATION_JSON)
  @APIResponse(
    responseCode = "200",
    description = "Existing client",
    content = @Content(
      mediaType = APPLICATION_JSON,
      schema = @Schema(implementation = ClientResponse.class)
    )
  )
  @APIResponse(
    responseCode = "400",
    description = "Invalid client identifier",
    content = @Content(
      mediaType = "application/problem+json",
      schema = @Schema(implementation = HttpProblem.class)
    )
  )
  @APIResponse(
    responseCode = "404",
    description = "Client not found",
    content = @Content(
      mediaType = "application/problem+json",
      schema = @Schema(implementation = HttpProblem.class)
    )
  )
  public Uni<RestResponse<ClientResponse>> get(
    @PathParam("clientId")
    @Parameter(description = "Client UUID; canonical textual spelling is not required")
    String clientId
  ) {
    return Uni.createFrom()
      .item(clientId)
      .map(UUID::fromString)
      .onFailure(IllegalArgumentException.class)
      .transform(InvalidClientIdError::new)
      .flatMap(clientService::get)
      .map(ClientResource::toClientResponse)
      .map(client -> ResponseBuilder.ok(client).build());
  }

  private static ClientInput toClientInput(ClientCreateRequest request) {
    return new ClientInput(
      request.firstName(),
      request.lastName(),
      request.email(),
      request.description()
    );
  }

  private static ClientResponse toClientResponse(ClientDetails clientDetails) {
    return new ClientResponse(
      clientDetails.id(),
      clientDetails.createdAt(),
      clientDetails.firstName(),
      clientDetails.lastName(),
      clientDetails.email(),
      clientDetails.description()
    );
  }
}

package ssonin.searchapi.external;

import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import ssonin.searchapi.domain.ClientDetails;
import ssonin.searchapi.external.request.ClientCreateRequest;
import ssonin.searchapi.external.response.ClientResponse;
import ssonin.searchapi.service.ClientInput;
import ssonin.searchapi.service.ClientService;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.CREATED;

@Path("/clients")
public class ClientResource {

  private final ClientService clientService;

  public ClientResource(ClientService clientService) {
    this.clientService = clientService;
  }

  @POST
  @Consumes(APPLICATION_JSON)
  @Produces(APPLICATION_JSON)
  public Uni<RestResponse<ClientResponse>> createClient(@NotNull @Valid ClientCreateRequest request,
                                                        @Context UriInfo uriInfo) {
    final var pathBuilder = uriInfo.getAbsolutePathBuilder();
    return clientService.create(toClientInput(request))
      .map(ClientResource::toClientResponse)
      .map(client -> ResponseBuilder.create(CREATED, client)
        .location(pathBuilder.path(client.id().toString()).build())
        .build());
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

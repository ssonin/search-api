package ssonin.searchapi.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import ssonin.searchapi.domain.ClientDetails;
import ssonin.searchapi.repository.ClientRepository;

import java.util.UUID;

import static java.util.UUID.randomUUID;

@ApplicationScoped
public final class ClientService {

  private final ClientRepository clientRepository;

  public ClientService(ClientRepository clientRepository) {
    this.clientRepository = clientRepository;
  }

  public Uni<ClientDetails> create(ClientInput client) {
    return clientRepository.insert(randomUUID(), client);
  }

  public Uni<ClientDetails> get(UUID clientId) {
    return clientRepository.get(clientId);
  }
}

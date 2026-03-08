package ssonin.searchapi.repository;

abstract sealed class NotFoundException extends RuntimeException permits ClientNotFoundException {

  NotFoundException(final String message) {
    super(message);
  }
}

package ssonin.searchapi.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

import static com.fasterxml.jackson.databind.cfg.CoercionAction.Fail;
import static com.fasterxml.jackson.databind.type.LogicalType.Textual;

@Singleton
class CustomModuleCustomiser implements ObjectMapperCustomizer {

  @Override
  public void customize(ObjectMapper mapper) {
    mapper.coercionConfigFor(Textual)
      .setCoercion(CoercionInputShape.Integer, Fail)
      .setCoercion(CoercionInputShape.Float, Fail)
      .setCoercion(CoercionInputShape.Boolean, Fail);
  }
}

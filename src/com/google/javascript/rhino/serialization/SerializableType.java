package com.google.javascript.rhino.serialization;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.javascript.jscomp.serialization.Type;
import java.util.function.Supplier;

/**
 * A wrapper around AST types to aid in serialization.
 *
 * The primary features are a more fine-grained notion of equality to make sure that we serialize
 * all the types we need, and a function to perform serialization.
 */
final class SerializableType<T> {
  private final T wrappedType;
  private final Supplier<Type> serialize;

  private SerializableType(T wrappedType, Supplier<Type> serialize) {
    this.wrappedType = checkNotNull(wrappedType);
    this.serialize = checkNotNull(serialize);
  }

  static <S> SerializableType<S> create(S wrappedType, Supplier<Type> serialize) {
    return new SerializableType<>(wrappedType, serialize);
  }

  /**
   * Generates the serialized type for this AST type
   */
  Type serializeToConcrete() {
    return serialize.get();
  }

  /**
   * Checks for serialization-time equality of the wrapped types.
   * Since two types may be equal in the type system, but still need to be serialized separately,
   * we may need more fine-grained equivalence classes, which identity equality gives us.
   */
  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof SerializableType)) {
      return false;
    }
    SerializableType<?> o = (SerializableType<?>) obj;
    return wrappedType.equals(o.wrappedType);
  }

  @Override
  public int hashCode() {
    return wrappedType.hashCode();
  }

  @Override
  public String toString() {
    return wrappedType.toString();
  }
}

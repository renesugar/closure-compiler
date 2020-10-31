/*
 * Copyright 2020 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp.serialization;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.javascript.jscomp.IdGenerator;
import com.google.javascript.jscomp.InvalidatingTypes;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.jstype.EnumType;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.JSTypeNative;
import com.google.javascript.rhino.jstype.JSTypeRegistry;
import com.google.javascript.rhino.jstype.ObjectType;
import com.google.javascript.rhino.jstype.UnionType;
import com.google.javascript.rhino.serialization.SerializationOptions;
import com.google.javascript.rhino.serialization.TypePoolCreator;
import java.util.Collection;

final class JSTypeSerializer {

  private final TypePoolCreator<JSType> typePoolCreator;
  private final ImmutableMap<JSType, TypePointer> nativeTypePointers;
  private final TypePointer unknownPointer;
  private final InvalidatingTypes invalidatingTypes;
  private final IdGenerator idGenerator;
  private final SerializationOptions serializationMode;

  private static final TypeDebugInfo EMPTY_DEBUG_INFO = TypeDebugInfo.getDefaultInstance();

  private JSTypeSerializer(
      TypePoolCreator<JSType> typePoolCreator,
      ImmutableMap<JSType, TypePointer> nativeTypePointers,
      TypePointer unknownPointer,
      InvalidatingTypes invalidatingTypes,
      IdGenerator idGenerator,
      SerializationOptions serializationMode) {
    this.typePoolCreator = typePoolCreator;
    this.nativeTypePointers = nativeTypePointers;
    this.unknownPointer = unknownPointer;
    this.invalidatingTypes = invalidatingTypes;
    this.idGenerator = idGenerator;
    this.serializationMode = serializationMode;
  }

  public static JSTypeSerializer create(
      TypePoolCreator<JSType> typePoolCreator,
      JSTypeRegistry registry,
      InvalidatingTypes invalidatingTypes,
      SerializationOptions serializationMode) {
    ImmutableMap<JSType, TypePointer> nativeTypePointers = buildNativeTypeMap(registry);
    IdGenerator idGenerator = new IdGenerator();

    return new JSTypeSerializer(
        typePoolCreator,
        nativeTypePointers,
        nativeTypePointers.get(registry.getNativeType(JSTypeNative.UNKNOWN_TYPE)),
        invalidatingTypes,
        idGenerator,
        serializationMode);
  }

  /** Returns a pointer to the given type. If it is not already serialized, serializes it too */
  TypePointer serializeType(JSType type) {
    if (nativeTypePointers.containsKey(type)) {
      return nativeTypePointers.get(type);
    }

    if (type.isNamedType()) {
      return serializeType(type.toMaybeNamedType().getReferencedType());
    }

    if (type.isEnumElementType()) {
      // replace with the corresponding primitive
      return serializeType(type.toMaybeEnumElementType().getPrimitiveType());
    }

    if (type.isTemplateType()) {
      // template types are not serialized because optimizations don't seem to care about them.
      // serialize as the UNKNOWN_TYPE because bounded generics are unsupported
      return unknownPointer;
    }

    if (type.isTemplatizedType()) {
      return serializeType(type.toMaybeTemplatizedType().getReferencedType());
    }

    if (type.isUnionType()) {
      return serializeUnionType(type.toMaybeUnionType());
    }

    if (type.isFunctionType() && type.toMaybeFunctionType().getCanonicalRepresentation() != null) {
      return serializeType(type.toMaybeFunctionType().getCanonicalRepresentation());
    }

    if (type.isNoResolvedType()) {
      // The Closure type system creates separate instances of NoResolvedType per type name. For
      // optimizations treat them all identically.
      return unknownPointer;
    }

    if (type.toObjectType() != null) {
      TypePointer serialized =
          typePoolCreator.typeToPointer(
              type,
              () ->
                  com.google.javascript.jscomp.serialization.Type.newBuilder()
                      .setObject(serializeObjectType(type.toObjectType()))
                      .build());
      addSupertypeEdges(type.toMaybeObjectType(), serialized);
      return serialized;
    }

    throw new IllegalStateException("Unsupported type " + type);
  }

  private void addSupertypeEdges(ObjectType subtype, TypePointer serializedSubtype) {
    for (TypePointer ancestor : ownAncestorInterfacesOf(subtype)) {
      typePoolCreator.addDisambiguationEdge(serializedSubtype, ancestor);
    }
    if (subtype.getImplicitPrototype() != null) {
      typePoolCreator.addDisambiguationEdge(
          serializedSubtype, serializeType(subtype.getImplicitPrototype()));
    }
  }

  private TypePointer serializeUnionType(UnionType type) {
    ImmutableSet<TypePointer> serializedAlternates =
        type.getAlternates().stream().map(this::serializeType).collect(toImmutableSet());
    if (serializedAlternates.size() == 1) {
      return Iterables.getOnlyElement(serializedAlternates);
    }
    return typePoolCreator.typeToPointer(type, () -> serializeUnionType(serializedAlternates));
  }

  private static Type serializeUnionType(ImmutableSet<TypePointer> serializedAlternates) {
    return com.google.javascript.jscomp.serialization.Type.newBuilder()
        .setUnion(
            com.google.javascript.jscomp.serialization.UnionType.newBuilder()
                .addAllUnionMember(serializedAlternates)
                .build())
        .build();
  }

  private static TypeDebugInfo getDebugInfo(ObjectType type) {
    TypeDebugInfo defaultDebugInfo = defaultDebugInfo(type);
    if (type.isInstanceType()) {
      return instanceDebugInfo(type, defaultDebugInfo);
    } else if (type.isEnumType()) {
      return enumDebugInfo(type.toMaybeEnumType(), defaultDebugInfo);
    } else if (type.isFunctionType()) {
      return functionDebugInfo(type.toMaybeFunctionType(), defaultDebugInfo);
    }
    return defaultDebugInfo;
  }

  private static TypeDebugInfo defaultDebugInfo(ObjectType type) {
    TypeDebugInfo.Builder builder = TypeDebugInfo.newBuilder();
    Node ownerNode = type.getOwnerFunction() != null ? type.getOwnerFunction().getSource() : null;
    if (ownerNode != null) {
      builder.setFilename(ownerNode.getSourceFileName());
    }
    String className = type.getReferenceName();
    if (className != null) {
      builder.setClassName(className);
    }
    return builder.build();
  }

  private com.google.javascript.jscomp.serialization.ObjectType serializeObjectType(
      ObjectType type) {
    TypeDebugInfo debugInfo =
        SerializationOptions.SKIP_DEBUG_INFO.equals(serializationMode)
            ? EMPTY_DEBUG_INFO
            : getDebugInfo(type);
    return serializeObjectType(type, debugInfo);
  }

  private com.google.javascript.jscomp.serialization.ObjectType serializeObjectType(
      ObjectType type, TypeDebugInfo debug) {
    com.google.javascript.jscomp.serialization.ObjectType.Builder objBuilder =
        com.google.javascript.jscomp.serialization.ObjectType.newBuilder();
    if (type.isFunctionType()) {
      FunctionType fnType = type.toMaybeFunctionType();
      // Serialize prototypes and instance types for instantiable types. Even if these types never
      // appear on the AST, optimizations need to know that at runtime these types may be present.
      if (fnType.hasInstanceType() && fnType.getInstanceType() != null) {
        objBuilder
            .setPrototype(serializeType(fnType.getPrototype()))
            .setInstanceType(serializeType(fnType.getInstanceType()));
      }
    }
    if (!EMPTY_DEBUG_INFO.equals(debug)) {
      objBuilder.setDebugInfo(debug);
    }
    return objBuilder
        .setIsInvalidating(invalidatingTypes.isInvalidating(type))
        // NOTE: We need a better format than sequential integers in order to have an id that
        // can be consistent across compilation units. For now, using a sequential integers for each
        // type depends on the invariant that we serialize each distinct type exactly once and from
        // a single compilation unit.
        .setUuid(Integer.toHexString(idGenerator.newId()))
        .build();
  }

  private static TypeDebugInfo instanceDebugInfo(ObjectType type, TypeDebugInfo defaultDebugInfo) {
    FunctionType constructor = type.getConstructor();
    String className = constructor.getReferenceName();
    TypeDebugInfo.Builder builder = TypeDebugInfo.newBuilder(defaultDebugInfo);
    if (className != null && !className.isEmpty()) {
      builder.setClassName(className + " instance");
    }
    if (builder.getFilename().isEmpty() && constructor.getSource() != null) {
      String filename = constructor.getSource().getSourceFileName();
      builder.setFilename(filename);
    }
    return builder.build();
  }

  private static TypeDebugInfo enumDebugInfo(EnumType type, TypeDebugInfo defaultDebugInfo) {
    TypeDebugInfo.Builder builder = TypeDebugInfo.newBuilder(defaultDebugInfo);
    if (type.getSource() != null) {
      builder.setFilename(type.getSource().getSourceFileName());
    }
    return builder.build();
  }

  private static TypeDebugInfo functionDebugInfo(
      FunctionType type, TypeDebugInfo defaultDebugInfo) {
    Node source = type.getSource();
    TypeDebugInfo.Builder builder = TypeDebugInfo.newBuilder(defaultDebugInfo);
    if (source != null) {
      String filename = source.getSourceFileName();
      if (filename != null) {
        builder.setFilename(filename);
      }
    }
    return builder.build();
  }

  /**
   * Returns the interfaces directly implemented or extended by {@code type}.
   *
   * <p>This is distinct from any of the methods on {@link FunctionType}. Specifically, the result
   * only contains:
   *
   * <ul>
   *   <li>own/direct supertypes
   *   <li>supertypes that are actually interfaces
   * </ul>
   */
  private ImmutableList<TypePointer> ownAncestorInterfacesOf(ObjectType type) {
    FunctionType ctorType = type.getConstructor();
    if (ctorType == null) {
      return ImmutableList.of();
    }

    final Collection<ObjectType> ifaceTypes;
    if (ctorType.isInterface()) {
      ifaceTypes = ctorType.getExtendedInterfaces();
    } else if (ctorType.isConstructor()) {
      ifaceTypes = ctorType.getOwnImplementedInterfaces();
    } else {
      throw new AssertionError();
    }

    if (ifaceTypes.isEmpty()) {
      return ImmutableList.of();
    }

    return ifaceTypes.stream()
        .filter((t) -> t.getConstructor() != null && t.getConstructor().isInterface())
        .map(this::serializeType)
        .collect(toImmutableList());
  }

  private static ImmutableMap<JSType, TypePointer> buildNativeTypeMap(JSTypeRegistry registry) {
    ImmutableMap.Builder<JSType, TypePointer> nativeTypes = ImmutableMap.builder();
    for (JSTypeNative jsNativeType : JSTypeNative.values()) {
      NativeType serializedNativeType = translateNativeType(jsNativeType);
      if (serializedNativeType != null) {
        nativeTypes.put(
            registry.getNativeType(jsNativeType),
            TypePointer.newBuilder().setNativeType(serializedNativeType).build());
      }
    }
    return nativeTypes.build();
  }

  /**
   * Maps between {@link JSTypeNative} and {@link NativeType}.
   *
   * <p>Not one-to-one or onto. Some Closure native types are not natively serialized and multiple
   * Closure native types correspond go the "UNKNOWN" serialized type.
   */
  private static NativeType translateNativeType(JSTypeNative nativeType) {
    switch (nativeType) {
      case STRING_TYPE:
        return NativeType.STRING_TYPE;

      case BOOLEAN_TYPE:
        return NativeType.BOOLEAN_TYPE;

      case NUMBER_TYPE:
        return NativeType.NUMBER_TYPE;

      case SYMBOL_TYPE:
        return NativeType.SYMBOL_TYPE;

      case BIGINT_TYPE:
        return NativeType.BIGINT_TYPE;

      case NULL_TYPE:
      case VOID_TYPE:
        return NativeType.NULL_OR_VOID_TYPE;

        // The optimizer doesn't distinguish between any of these types: they are all
        // invalidating objects.
      case OBJECT_TYPE:
      case OBJECT_FUNCTION_TYPE:
      case OBJECT_PROTOTYPE:
      case FUNCTION_PROTOTYPE:
      case FUNCTION_TYPE:
      case FUNCTION_FUNCTION_TYPE:
        return NativeType.TOP_OBJECT;

      case ALL_TYPE:
      case UNKNOWN_TYPE:
      case CHECKED_UNKNOWN_TYPE:
      case NO_TYPE:
      case NO_OBJECT_TYPE:
      case NO_RESOLVED_TYPE:
        return NativeType.UNKNOWN_TYPE;

      default:
        return null;
    }
  }
}

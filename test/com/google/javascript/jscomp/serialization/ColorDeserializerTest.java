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

import static com.google.common.truth.Truth.assertThat;
import static com.google.javascript.jscomp.testing.ColorSubject.assertThat;
import static com.google.javascript.rhino.testing.Asserts.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.javascript.jscomp.colors.Color;
import com.google.javascript.jscomp.colors.ObjectColor;
import com.google.javascript.jscomp.colors.PrimitiveColor;
import com.google.javascript.jscomp.colors.UnionColor;
import com.google.javascript.jscomp.serialization.ColorDeserializer.InvalidSerializedFormatException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ColorDeserializerTest {

  @Test
  public void deserializesPrimitiveTypesFromEmptyTypePool() {
    ColorDeserializer deserializer =
        ColorDeserializer.buildFromTypePool(TypePool.getDefaultInstance());

    assertThat(deserializer.pointerToColor(nativeTypePointer(NativeType.NUMBER_TYPE)))
        .isEqualTo(PrimitiveColor.NUMBER);
    assertThat(deserializer.pointerToColor(nativeTypePointer(NativeType.STRING_TYPE)))
        .isEqualTo(PrimitiveColor.STRING);
    assertThat(deserializer.pointerToColor(nativeTypePointer(NativeType.UNKNOWN_TYPE)))
        .isEqualTo(PrimitiveColor.UNKNOWN);
  }

  @Test
  public void deserializesNativeObjectTypesFromEmptyTypePool() {
    ColorDeserializer deserializer =
        ColorDeserializer.buildFromTypePool(TypePool.getDefaultInstance());

    Color object = deserializer.pointerToColor(nativeTypePointer(NativeType.TOP_OBJECT));
    assertThat(object).isObject();
    assertThat(object).isInvalidating();
  }

  @Test
  public void deserializesSimpleObject() {
    TypePool typePool =
        TypePool.newBuilder()
            .addType(Type.newBuilder().setObject(ObjectType.newBuilder().setUuid("Foo")))
            .build();
    ColorDeserializer deserializer = ColorDeserializer.buildFromTypePool(typePool);

    assertThat(deserializer.pointerToColor(poolPointer(0)))
        .isEqualTo(createObjectColorBuilder().setId("Foo").build());
  }

  @Test
  public void deserializesObjectWithPrototypeAndInstanceType() {
    TypePool typePool =
        TypePool.newBuilder()
            .addType(Type.newBuilder().setObject(ObjectType.newBuilder().setUuid("Foo.prototype")))
            .addType(Type.newBuilder().setObject(ObjectType.newBuilder().setUuid("Foo instance")))
            .addType(
                Type.newBuilder()
                    .setObject(
                        ObjectType.newBuilder()
                            .setUuid("Foo")
                            .setPrototype(poolPointer(0))
                            .setInstanceType(poolPointer(1))))
            .build();
    ColorDeserializer deserializer = ColorDeserializer.buildFromTypePool(typePool);

    assertThat(deserializer.pointerToColor(poolPointer(2)))
        .isEqualTo(
            createObjectColorBuilder()
                .setId("Foo")
                .setInstanceColor(createObjectColorBuilder().setId("Foo instance").build())
                .setPrototype(createObjectColorBuilder().setId("Foo.prototype").build())
                .build());
  }

  @Test
  public void deserializesInvalidatingObject() {
    TypePool typePool =
        TypePool.newBuilder()
            .addType(
                Type.newBuilder()
                    .setObject(ObjectType.newBuilder().setUuid("Foo").setIsInvalidating(true)))
            .build();
    ColorDeserializer deserializer = ColorDeserializer.buildFromTypePool(typePool);

    assertThat(deserializer.pointerToColor(poolPointer(0)))
        .isEqualTo(createObjectColorBuilder().setId("Foo").setInvalidating(true).build());
  }

  @Test
  public void addsSingleSupertypeToDirectSupertypesField() {
    TypePool typePool =
        TypePool.newBuilder()
            .addType(Type.newBuilder().setObject(ObjectType.newBuilder().setUuid("Foo")))
            .addType(Type.newBuilder().setObject(ObjectType.newBuilder().setUuid("Bar")))
            // Bar is a subtype of Foo
            .addDisambiguationEdges(
                SubtypingEdge.newBuilder()
                    .setSubtype(TypePointer.newBuilder().setPoolOffset(1))
                    .setSupertype(TypePointer.newBuilder().setPoolOffset(0)))
            .build();
    ColorDeserializer deserializer = ColorDeserializer.buildFromTypePool(typePool);

    assertThat(deserializer.pointerToColor(poolPointer(1)))
        .hasDisambiguationSupertypes(createObjectColorBuilder().setId("Foo").build());
  }

  @Test
  public void addsMultipleSupertypesToDirectSupertypesField() {
    TypePool typePool =
        TypePool.newBuilder()
            .addType(Type.newBuilder().setObject(ObjectType.newBuilder().setUuid("Foo")))
            .addType(Type.newBuilder().setObject(ObjectType.newBuilder().setUuid("Bar")))
            .addType(Type.newBuilder().setObject(ObjectType.newBuilder().setUuid("Baz")))
            // Bar is a subtype of Foo
            .addDisambiguationEdges(
                SubtypingEdge.newBuilder()
                    .setSubtype(TypePointer.newBuilder().setPoolOffset(1))
                    .setSupertype(TypePointer.newBuilder().setPoolOffset(0)))
            // Bar is also a subtype of Baz
            .addDisambiguationEdges(
                SubtypingEdge.newBuilder()
                    .setSubtype(TypePointer.newBuilder().setPoolOffset(1))
                    .setSupertype(TypePointer.newBuilder().setPoolOffset(2)))
            .build();
    ColorDeserializer deserializer = ColorDeserializer.buildFromTypePool(typePool);

    assertThat(deserializer.pointerToColor(poolPointer(1)))
        .hasDisambiguationSupertypes(
            createObjectColorBuilder().setId("Foo").build(),
            createObjectColorBuilder().setId("Baz").build());
  }

  @Test
  public void throwsErrorIfDisambiguationEdgesContainsInvalidId() {
    TypePool typePool =
        TypePool.newBuilder()
            .addType(Type.newBuilder().setObject(ObjectType.newBuilder().setUuid("Foo")))
            .addDisambiguationEdges(
                SubtypingEdge.newBuilder()
                    .setSubtype(TypePointer.newBuilder().setPoolOffset(0))
                    .setSupertype(TypePointer.newBuilder().setPoolOffset(1)))
            .build();
    assertThrows(
        InvalidSerializedFormatException.class,
        () -> ColorDeserializer.buildFromTypePool(typePool).pointerToColor(poolPointer(0)));
  }

  @Test
  public void throwsErrorIfDisambiguationEdgesContainsNativeType() {
    // Disambiguation doesn't care about supertyping/subtyping for native types. It's assumed that
    // every type is a subtype of NativeType.UNKNOWN_TYPE.
    TypePool typePool =
        TypePool.newBuilder()
            .addDisambiguationEdges(
                SubtypingEdge.newBuilder()
                    .setSubtype(TypePointer.newBuilder().setNativeType(NativeType.UNKNOWN_TYPE))
                    .setSupertype(TypePointer.newBuilder().setNativeType(NativeType.NUMBER_TYPE)))
            .build();
    assertThrows(
        InvalidSerializedFormatException.class,
        () -> ColorDeserializer.buildFromTypePool(typePool));
  }

  @Test
  public void deserializesMultipleUnionsOfNativeTypes() {
    TypePool typePool =
        TypePool.newBuilder()
            .addType(
                Type.newBuilder()
                    .setUnion(
                        UnionType.newBuilder()
                            .addUnionMember(nativeTypePointer(NativeType.NUMBER_TYPE))
                            .addUnionMember(nativeTypePointer(NativeType.STRING_TYPE))))
            .addType(
                Type.newBuilder()
                    .setUnion(
                        UnionType.newBuilder()
                            .addUnionMember(nativeTypePointer(NativeType.NUMBER_TYPE))
                            .addUnionMember(nativeTypePointer(NativeType.BIGINT_TYPE))))
            .build();

    ColorDeserializer deserializer = ColorDeserializer.buildFromTypePool(typePool);

    assertThat(deserializer.pointerToColor(poolPointer(0)))
        .isEqualTo(
            UnionColor.create(ImmutableSet.of(PrimitiveColor.STRING, PrimitiveColor.NUMBER)));
    assertThat(deserializer.pointerToColor(poolPointer(1)))
        .isEqualTo(
            UnionColor.create(ImmutableSet.of(PrimitiveColor.BIGINT, PrimitiveColor.NUMBER)));
  }

  @Test
  public void deserializesUnionReferencingEarlierTypeInPool() {
    TypePool typePool =
        TypePool.newBuilder()
            // U0 := (number, string)
            .addType(
                Type.newBuilder()
                    .setUnion(
                        UnionType.newBuilder()
                            .addUnionMember(nativeTypePointer(NativeType.NUMBER_TYPE))
                            .addUnionMember(nativeTypePointer(NativeType.STRING_TYPE))))
            // U1 := (U1, bigint)
            .addType(
                Type.newBuilder()
                    .setUnion(
                        UnionType.newBuilder()
                            .addUnionMember(poolPointer(0))
                            .addUnionMember(nativeTypePointer(NativeType.BIGINT_TYPE))))
            .build();

    ColorDeserializer deserializer = ColorDeserializer.buildFromTypePool(typePool);

    assertThat(deserializer.pointerToColor(poolPointer(0)))
        .isEqualTo(
            UnionColor.create(ImmutableSet.of(PrimitiveColor.STRING, PrimitiveColor.NUMBER)));
    assertThat(deserializer.pointerToColor(poolPointer(1)))
        .isEqualTo(
            UnionColor.create(
                ImmutableSet.of(
                    PrimitiveColor.BIGINT, PrimitiveColor.NUMBER, PrimitiveColor.STRING)));
  }

  @Test
  public void deserializingUnionsInCycleThrowsErrors() {
    // Create two union types U0 and U1. U0 := (U1 | NUMBER), U1 := (U0 | number)
    // These cycles are at least possible to construct in the Closure type system.
    // (Note - cycles are much more likely in object types)
    TypePool typePool =
        TypePool.newBuilder()
            .addType(
                Type.newBuilder()
                    .setUnion(
                        UnionType.newBuilder()
                            .addUnionMember(nativeTypePointer(NativeType.NUMBER_TYPE))
                            .addUnionMember(
                                TypePointer.newBuilder()
                                    .setPoolOffset(1)
                                    .setDescriptionForDebug("U1"))))
            .addType(
                Type.newBuilder()
                    .setUnion(
                        UnionType.newBuilder()
                            .addUnionMember(nativeTypePointer(NativeType.NUMBER_TYPE))
                            .addUnionMember(
                                TypePointer.newBuilder()
                                    .setPoolOffset(0)
                                    .setDescriptionForDebug("U0"))))
            .build();

    // Eventually we may need to support this case, but for now throwing an explicit exception is
    // better than infinite recursion.

    assertThrows(
        InvalidSerializedFormatException.class,
        () -> ColorDeserializer.buildFromTypePool(typePool).pointerToColor(poolPointer(0)));
  }

  @Test
  public void throwsExceptionOnTypePointerWithoutKind() {
    ColorDeserializer deserializer =
        ColorDeserializer.buildFromTypePool(TypePool.getDefaultInstance());

    assertThrows(
        InvalidSerializedFormatException.class,
        () -> deserializer.pointerToColor(TypePointer.getDefaultInstance()));
  }

  @Test
  public void throwsExceptionOnUnrecognizedNativeType() {
    ColorDeserializer deserializer =
        ColorDeserializer.buildFromTypePool(TypePool.getDefaultInstance());

    assertThrows(
        InvalidSerializedFormatException.class,
        () -> deserializer.pointerToColor(TypePointer.newBuilder().setNativeTypeValue(-1).build()));
  }

  @Test
  public void throwsExceptionOnTypeWithoutKindCase() {
    TypePool typePool = TypePool.newBuilder().addType(Type.getDefaultInstance()).build();

    assertThrows(
        InvalidSerializedFormatException.class,
        () -> ColorDeserializer.buildFromTypePool(typePool).pointerToColor(poolPointer(0)));
  }

  @Test
  public void throwsExceptionOnSerializedUnionOfSingleElement() {
    TypePool typePool =
        TypePool.newBuilder()
            .addType(
                Type.newBuilder()
                    .setUnion(
                        UnionType.newBuilder()
                            .addUnionMember(nativeTypePointer(NativeType.NUMBER_TYPE))))
            .build();

    assertThrows(
        InvalidSerializedFormatException.class,
        () -> ColorDeserializer.buildFromTypePool(typePool).pointerToColor(poolPointer(0)));
  }

  private static ObjectColor.Builder createObjectColorBuilder() {
    return ObjectColor.builder().setId("");
  }

  @Test
  public void unionThatDeduplicatesIntoSingleElementBecomesSingularColor() {
    TypePool typePool =
        TypePool.newBuilder()
            .addType(
                Type.newBuilder()
                    .setUnion(
                        UnionType.newBuilder()
                            .addUnionMember(nativeTypePointer(NativeType.NUMBER_TYPE))
                            .addUnionMember(nativeTypePointer(NativeType.NUMBER_TYPE))))
            .build();

    ColorDeserializer deserializer = ColorDeserializer.buildFromTypePool(typePool);

    assertThat(deserializer.pointerToColor(poolPointer(0))).isEqualTo(PrimitiveColor.NUMBER);
    assertThat(deserializer.pointerToColor(poolPointer(0)).isUnion()).isFalse();
  }

  private static TypePointer nativeTypePointer(NativeType nativeType) {
    return TypePointer.newBuilder().setNativeType(nativeType).build();
  }

  private static TypePointer poolPointer(int offset) {
    return TypePointer.newBuilder().setPoolOffset(offset).build();
  }
}

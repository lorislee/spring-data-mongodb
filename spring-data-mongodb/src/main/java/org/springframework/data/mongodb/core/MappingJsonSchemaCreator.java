/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mongodb.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.bson.Document;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.SimplePropertyHandler;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoId;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.data.mongodb.core.schema.IdentifiableJsonSchemaProperty.ObjectJsonSchemaProperty;
import org.springframework.data.mongodb.core.schema.JsonSchemaObject;
import org.springframework.data.mongodb.core.schema.JsonSchemaObject.Type;
import org.springframework.data.mongodb.core.schema.JsonSchemaProperty;
import org.springframework.data.mongodb.core.schema.MongoJsonSchema;
import org.springframework.data.mongodb.core.schema.MongoJsonSchema.MongoJsonSchemaBuilder;
import org.springframework.data.mongodb.core.schema.TypedJsonSchemaObject;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

/**
 * {@link JsonSchemaCreator} implementation using both {@link MongoConverter} and {@link MappingContext} to obtain
 * domain type meta information which considers {@link org.springframework.data.mongodb.core.mapping.Field field names}
 * and {@link org.springframework.data.mongodb.core.convert.MongoCustomConversions custom conversions}.
 * 
 * @author Christoph Strobl
 * @since 2.2
 */
public class MappingJsonSchemaCreator implements JsonSchemaCreator {

	private MongoConverter converter;
	private MappingContext mappingContext;

	/**
	 * Create a new instance of {@link MappingJsonSchemaCreator}.
	 *
	 * @param converter
	 */
	public MappingJsonSchemaCreator(MongoConverter converter) {

		this.converter = converter;
		this.mappingContext = converter.getMappingContext();

	}

	/*
	 * (non-Javadoc)
	 * org.springframework.data.mongodb.core.JsonSchemaCreator#createSchemaFor(java.lang.Class)
	 */
	@Override
	public MongoJsonSchema createSchemaFor(Class<?> type) {

		PersistentEntity<?, ?> entity = mappingContext.getPersistentEntity(type);
		MongoJsonSchemaBuilder schemaBuilder = MongoJsonSchema.builder();

		List<PotentiallyRequiredSchemaProperty> schemaProperties = computePropertiesForEntity(Collections.emptyList(),
				entity);
		schemaBuilder.properties(schemaProperties.toArray(new JsonSchemaProperty[0]));

		schemaProperties.stream() //
				.filter(PotentiallyRequiredSchemaProperty::isRequired) //
				.map(JsonSchemaProperty::getIdentifier) //
				.forEach(schemaBuilder::required);

		return schemaBuilder.build();

	}

	private List<PotentiallyRequiredSchemaProperty> computePropertiesForEntity(List<PersistentProperty> path,
			PersistentEntity<?, ?> entity) {

		List<PotentiallyRequiredSchemaProperty> schemaProperties = new ArrayList<>();
		entity.doWithProperties((SimplePropertyHandler) nested -> {

			ArrayList<PersistentProperty> currentPath = new ArrayList<>(path);

			if (path.contains(nested)) { // cycle guard
				schemaProperties.add(createSchemaProperty(computePropertyFieldName(CollectionUtils.lastElement(currentPath)),
						Object.class, false));
				return;
			}

			currentPath.add(nested);
			PotentiallyRequiredSchemaProperty jsonSchemaProperty = computeSchemaForProperty(currentPath, entity);
			if (jsonSchemaProperty != null) {
				schemaProperties.add(jsonSchemaProperty);
			}
		});

		return schemaProperties;
	}

	private PotentiallyRequiredSchemaProperty computeSchemaForProperty(List<PersistentProperty> path,
			PersistentEntity<?, ?> parent) {

		PersistentProperty property = CollectionUtils.lastElement(path);

		boolean required = isRequiredProperty(parent, property);
		Class<?> rawTargetType = computeTargetType(property); // target type before conversion
		Class<?> targetType = converter.computeWriteTarget(rawTargetType); // conversion target type

		if (property.isEntity() && ObjectUtils.nullSafeEquals(rawTargetType, targetType)) {
			return createObjectSchemaPropertyForEntity(path, property, required);
		}

		String fieldName = computePropertyFieldName(property);

		if (property.isCollectionLike()) {
			return createSchemaProperty(fieldName, targetType, required);
		} else if (property.isMap()) {
			return createSchemaProperty(fieldName, Type.objectType(), required);
		} else if (ClassUtils.isAssignable(Enum.class, targetType)) {
			return createEnumSchemaProperty(fieldName, targetType, required);
		}

		return createSchemaProperty(fieldName, targetType, required);
	}

	private PotentiallyRequiredSchemaProperty createObjectSchemaPropertyForEntity(List<PersistentProperty> path,
			PersistentProperty property, boolean required) {

		ObjectJsonSchemaProperty target = JsonSchemaProperty.object(property.getName());
		List<PotentiallyRequiredSchemaProperty> nestedProperties = computePropertiesForEntity(path,
				mappingContext.getPersistentEntity(property));

		for (PotentiallyRequiredSchemaProperty helper : nestedProperties) {
			if (helper.isRequired()) {
				target = target.required(helper.getIdentifier());
			}
		}

		return new PotentiallyRequiredSchemaProperty(target.properties(nestedProperties.toArray(new JsonSchemaProperty[0])),
				required);
	}

	private PotentiallyRequiredSchemaProperty createEnumSchemaProperty(String fieldName, Class<?> targetType,
			boolean required) {

		List<Object> possibleValues = new ArrayList<>();
		for (Object enumValue : EnumSet.allOf((Class) targetType)) {
			possibleValues.add(converter.convertToMongoType(enumValue));
		}

		targetType = possibleValues.isEmpty() ? targetType : possibleValues.iterator().next().getClass();
		return createSchemaProperty(fieldName, targetType, required, possibleValues);
	}

	PotentiallyRequiredSchemaProperty createSchemaProperty(String fieldName, Object type, boolean required) {
		return createSchemaProperty(fieldName, type, required, Collections.emptyList());
	}

	PotentiallyRequiredSchemaProperty createSchemaProperty(String fieldName, Object type, boolean required,
			Collection<?> possibleValues) {

		TypedJsonSchemaObject schemaObject = type instanceof Type ? JsonSchemaObject.of(Type.class.cast(type))
				: JsonSchemaObject.of(Class.class.cast(type));

		if (!CollectionUtils.isEmpty(possibleValues)) {
			schemaObject = schemaObject.possibleValues(possibleValues);
		}

		return new PotentiallyRequiredSchemaProperty(JsonSchemaProperty.named(fieldName).with(schemaObject), required);
	}

	private String computePropertyFieldName(PersistentProperty property) {

		return property instanceof MongoPersistentProperty ? ((MongoPersistentProperty) property).getFieldName()
				: property.getName();
	}

	private boolean isRequiredProperty(PersistentEntity<?, ?> parent, PersistentProperty property) {

		return (parent.isConstructorArgument(property) && !property.isAnnotationPresent(Nullable.class))
				|| property.getType().isPrimitive();
	}

	private Class<?> computeTargetType(PersistentProperty<?> property) {

		if (!(property instanceof MongoPersistentProperty)) {
			return property.getType();
		}

		MongoPersistentProperty mongoProperty = (MongoPersistentProperty) property;
		if (!mongoProperty.isIdProperty()) {
			return mongoProperty.getFieldType();
		}

		if (mongoProperty.isAnnotationPresent(MongoId.class)) {
			return mongoProperty.findAnnotation(MongoId.class).value().getJavaClass();
		}

		return mongoProperty.getFieldType() != mongoProperty.getActualType() ? Object.class : mongoProperty.getFieldType();
	}

	/**
	 * Delegating {@link JsonSchemaProperty} implementation having a {@liteal required} flag for evaluation during schema
	 * creation process.
	 * 
	 * @author Christoph Strobl
	 * @since 2.2
	 */
	private static class PotentiallyRequiredSchemaProperty implements JsonSchemaProperty {

		private final JsonSchemaProperty delegate;
		private final boolean required;

		PotentiallyRequiredSchemaProperty(JsonSchemaProperty delegate, boolean required) {

			this.delegate = delegate;
			this.required = required;
		}

		@Override
		public String getIdentifier() {
			return delegate.getIdentifier();
		}

		@Override
		public Set<Type> getTypes() {
			return delegate.getTypes();
		}

		@Override
		public Document toDocument() {
			return delegate.toDocument();
		}

		boolean isRequired() {
			return required;
		}
	}
}

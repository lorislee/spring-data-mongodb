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

import org.springframework.data.mongodb.core.schema.MongoJsonSchema;

/**
 * {@link JsonSchemaCreator} extracts the {@link MongoJsonSchema} for a given {@link Class} by applying the following
 * mapping rules.
 * <p>
 * <strong>Required Properties</strong><br />
 * - All Constructor arguments annotated with {@link org.springframework.lang.Nullable}. <br />
 * - Properties of primitive type. <br />
 * </p>
 * <p>
 * <strong>Ignored Properties</strong><br />
 * - All properties annotated with {@link org.springframework.data.annotation.Transient}. <br />
 * </p>
 * <p>
 * <strong>Property Type Mapping</strong><br />
 * - {@link java.lang.Object} -> {@code type : 'object'} <br />
 * - {@link java.util.Arrays} -> {@code type : 'array'} <br />
 * - {@link java.util.Collection} -> {@code type : 'array'} <br />
 * - {@link java.util.Map} -> {@code type : 'object'} <br />
 * - {@link java.lang.Enum} -> {@code type : 'string', enum : [the enum values]} <br />
 * - Simple Types -> {@code type : 'the corresponding bson type' } <br />
 * - Domain Types -> {@code type : 'object', properties : &#123;the types properties&#125; } <br />
 * <br />
 * {@link org.springframework.data.annotation.Id _id} properties using types that can be converted into
 * {@link org.bson.types.ObjectId} like {@link String} will be mapped to {@code type : 'object'} unless there is more
 * specific information available via the {@link org.springframework.data.mongodb.core.mapping.MongoId} annotation.
 * </p>
 * 
 * @author Christoph Strobl
 * @since 2.2
 */
public interface JsonSchemaCreator {

	/**
	 * Create the {@link MongoJsonSchema} for the given {@link Class type}.
	 *
	 * @param type must not be {@literal null}.
	 * @return never {@literal null}.
	 */
	MongoJsonSchema createSchemaFor(Class<?> type);
}

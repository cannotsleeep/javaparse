/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.formats.avro.typeutils;

import org.apache.flink.annotation.Internal;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Parser;
import org.apache.avro.reflect.Nullable;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;

/** A wrapper for Avro {@link Schema}, that is Java serializable. */
@Internal
final class SerializableAvroSchema implements Serializable {

    private static final long serialVersionUID = 1;

    private transient @Nullable Schema schema;

    SerializableAvroSchema() {}

    SerializableAvroSchema(Schema schema) {
        this.schema = schema;
    }

    Schema getAvroSchema() {
        return schema;
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        if (schema == null) {
            oos.writeBoolean(false);
        } else {
            oos.writeBoolean(true);
            byte[] schemaStrInBytes = schema.toString(false).getBytes(StandardCharsets.UTF_8);
            oos.writeInt(schemaStrInBytes.length);
            oos.write(schemaStrInBytes);
        }
    }

    private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
        if (ois.readBoolean()) {
            int len = ois.readInt();
            byte[] content = new byte[len];
            ois.readFully(content);
            this.schema = new Parser().parse(new String(content, StandardCharsets.UTF_8));
        } else {
            this.schema = null;
        }
    }
}

/*
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
package com.facebook.presto.accumulo.model;

import com.facebook.presto.accumulo.index.IndexQueryParameters;
import com.facebook.presto.accumulo.serializers.LexicoderRowSerializer;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.spi.type.Type;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.FromStringDeserializer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.json.JsonCodec;
import io.airlift.json.JsonCodecFactory;
import io.airlift.json.ObjectMapperProvider;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ENGLISH;
import static org.testng.Assert.assertEquals;

public class TestAccumuloSplit
{
    private final JsonCodec<AccumuloSplit> codec;

    public TestAccumuloSplit()
    {
        ObjectMapperProvider provider = new ObjectMapperProvider();
        provider.setJsonDeserializers(ImmutableMap.of(Type.class, new TestingTypeDeserializer()));
        JsonCodecFactory codecFactory = new JsonCodecFactory(provider);
        codec = codecFactory.jsonCodec(AccumuloSplit.class);
    }

    @Test
    public void testJsonRoundTrip()
    {
        AccumuloSplit expected = new AccumuloSplit(
                "accumulo",
                "schema",
                "table",
                "id",
                LexicoderRowSerializer.class.getCanonicalName(),
                ImmutableList.of(new AccumuloRange(), new AccumuloRange("bar".getBytes(UTF_8), "foo".getBytes(UTF_8)), new AccumuloRange("bar".getBytes(UTF_8), false, "baz".getBytes(UTF_8), false)),
                ImmutableList.of(new AccumuloRange(), new AccumuloRange("bar".getBytes(UTF_8), "foo".getBytes(UTF_8)), new AccumuloRange("bar".getBytes(UTF_8), false, "baz".getBytes(UTF_8), false)),
                Optional.of(
                        new IndexQueryParameters(
                                new IndexColumn(ImmutableList.of("bar", "baz")),
                                "index-family",
                                ImmutableList.of(new AccumuloRange("b".getBytes(UTF_8), "c".getBytes(UTF_8)))
                        )
                ),
                ImmutableList.of(
                        new AccumuloColumnConstraint(
                                "id",
                                "fam1",
                                "qual1",
                                VARCHAR,
                                Optional.empty()),
                        new AccumuloColumnConstraint(
                                "bar",
                                "fam2",
                                "qual2",
                                VARCHAR,
                                Optional.empty())),
                Optional.of("foo,bar"));

        String json = codec.toJson(expected);
        AccumuloSplit actual = codec.fromJson(json);
        assertSplit(actual, expected);
    }

    @Test
    public void testJsonRoundTripEmptyThings()
    {
        AccumuloSplit expected = new AccumuloSplit(
                "accumulo",
                "schema",
                "table",
                "id",
                LexicoderRowSerializer.class.getCanonicalName(),
                ImmutableList.of(),
                ImmutableList.of(),
                Optional.empty(),
                ImmutableList.of(),
                Optional.empty());

        String json = codec.toJson(expected);
        AccumuloSplit actual = codec.fromJson(json);
        assertSplit(actual, expected);
    }

    private static void assertSplit(AccumuloSplit actual, AccumuloSplit expected)
    {
        assertEquals(actual.getAddresses(), expected.getAddresses());
        assertEquals(actual.getConnectorId(), expected.getConnectorId());
        assertEquals(actual.getConstraints(), expected.getConstraints());
        assertEquals(actual.getRowId(), expected.getRowId());
        assertEquals(actual.getWrappedRanges(), expected.getWrappedRanges());
        assertEquals(actual.getRowIdRanges(), expected.getRowIdRanges());
        assertEquals(actual.getIndexQueryParameters(), expected.getIndexQueryParameters());
        assertEquals(actual.getRowId(), expected.getRowId());
        assertEquals(actual.getScanAuthorizations(), expected.getScanAuthorizations());
        assertEquals(actual.getSchema(), expected.getSchema());
        assertEquals(actual.getSerializerClass(), expected.getSerializerClass());
        assertEquals(actual.getSerializerClassName(), expected.getSerializerClassName());
        assertEquals(actual.getTable(), expected.getTable());
    }

    public static final class TestingTypeDeserializer
            extends FromStringDeserializer<Type>
    {
        private final Map<String, Type> types = ImmutableMap.of(StandardTypes.VARCHAR, VARCHAR);

        public TestingTypeDeserializer()
        {
            super(Type.class);
        }

        @Override
        protected Type _deserialize(String value, DeserializationContext context)
        {
            Type type = types.get(value.toLowerCase(ENGLISH));
            checkArgument(type != null, "Unknown type %s", value);
            return type;
        }
    }
}

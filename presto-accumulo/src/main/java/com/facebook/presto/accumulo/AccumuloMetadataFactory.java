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
package com.facebook.presto.accumulo;

import com.facebook.presto.accumulo.conf.AccumuloConfig;

import javax.inject.Inject;

import static java.util.Objects.requireNonNull;

public class AccumuloMetadataFactory
{
    private final AccumuloConnectorId connectorId;
    private final AccumuloClient client;
    private final boolean supportMetadataDeletes;

    @Inject
    public AccumuloMetadataFactory(
            AccumuloConnectorId connectorId,
            AccumuloClient client,
            AccumuloConfig config)
    {
        this.connectorId = requireNonNull(connectorId, "connectorId is null");
        this.client = requireNonNull(client, "client is null");
        this.supportMetadataDeletes = requireNonNull(config).isSupportMetadataDeletes();
    }

    public AccumuloMetadata create()
    {
        return new AccumuloMetadata(connectorId, client, supportMetadataDeletes);
    }
}

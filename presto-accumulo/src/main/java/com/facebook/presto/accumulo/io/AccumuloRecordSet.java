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
package com.facebook.presto.accumulo.io;

import com.facebook.presto.accumulo.conf.AccumuloSessionProperties;
import com.facebook.presto.accumulo.index.IndexLookup;
import com.facebook.presto.accumulo.model.AccumuloColumnConstraint;
import com.facebook.presto.accumulo.model.AccumuloColumnHandle;
import com.facebook.presto.accumulo.model.AccumuloSplit;
import com.facebook.presto.accumulo.serializers.AccumuloRowSerializer;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.RecordSet;
import com.facebook.presto.spi.type.Type;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.security.Authorizations;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.accumulo.AccumuloErrorCode.UNEXPECTED_ACCUMULO_ERROR;
import static com.facebook.presto.accumulo.conf.AccumuloSessionProperties.isTracingEnabled;
import static com.facebook.presto.spi.StandardErrorCode.NOT_FOUND;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Implementation of a Presto RecordSet, responsible for returning the column types and the RecordCursor to the framework.
 *
 * @see AccumuloRecordCursor
 * @see AccumuloRecordSetProvider
 */
public class AccumuloRecordSet
        implements RecordSet
{
    private static final Logger LOG = Logger.get(AccumuloRecordSet.class);
    private static final Splitter COMMA_SPLITTER = Splitter.on(',').omitEmptyStrings().trimResults();

    private final List<AccumuloColumnHandle> columnHandles;
    private final List<AccumuloColumnConstraint> constraints;
    private final List<Type> columnTypes;
    private final AccumuloRowSerializer serializer;
    private final BatchScanner scanner;
    private final String rowIdName;
    private boolean useNullScanner = false;

    private Optional<String> traceName = Optional.empty();

    public AccumuloRecordSet(
            Connector connector,
            ConnectorSession session,
            AccumuloSplit split,
            String username,
            List<AccumuloColumnHandle> columnHandles)
    {
        requireNonNull(session, "session is null");
        requireNonNull(split, "split is null");
        requireNonNull(username, "username is null");
        constraints = requireNonNull(split.getConstraints(), "constraints is null");

        rowIdName = split.getRowId();

        // Factory the serializer based on the split configuration
        try {
            this.serializer = split.getSerializerClass().getConstructor().newInstance();
        }
        catch (Exception e) {
            throw new PrestoException(NOT_FOUND, "Failed to factory serializer class.  Is it on the classpath?", e);
        }

        // Save off the column handles and create a list of the Accumulo types
        this.columnHandles = requireNonNull(columnHandles, "column handles is null");
        ImmutableList.Builder<Type> types = ImmutableList.builder();
        for (AccumuloColumnHandle column : columnHandles) {
            types.add(column.getType());
        }
        this.columnTypes = types.build();

        if (isTracingEnabled(session)) {
            traceName = Optional.of(format("%s:%s:AccumuloRecordCursor:%s", session.getQueryId(), split.getFullTableName(), split.getRanges().size()));
        }

        try {
            if (split.getIndexQueryParameters().isPresent()) {
                List<Range> ranges = new IndexLookup().getIndexRanges(
                        connector,
                        session,
                        split.getIndexTableName(),
                        ImmutableList.of(split.getIndexQueryParameters().get()),
                        split.getRowIdRanges(),
                        getScanAuthorizations(session, split, connector, username));

                if (ranges.isEmpty()) {
                    useNullScanner = true;
                    scanner = null;
                }
                else {
                    // Create the BatchScanner and set the ranges from the split
                    scanner = connector.createBatchScanner(split.getFullTableName(), getScanAuthorizations(session, split, connector, username), 10);
                    scanner.setRanges(ranges);
                    useNullScanner = false;
                }
            }
            else {
                useNullScanner = false;
                scanner = connector.createBatchScanner(split.getFullTableName(), getScanAuthorizations(session, split, connector, username), 10);
                scanner.setRanges(split.getRanges());
            }
        }
        catch (Exception e) {
            throw new PrestoException(UNEXPECTED_ACCUMULO_ERROR, format("Failed to create batch scanner for table %s", split.getFullTableName()), e);
        }
    }

    /**
     * Gets the scanner authorizations to use for scanning tables.
     * <p>
     * In order of priority: session username authorizations, then table property, then the default connector auths.
     *
     * @param session Current session
     * @param split Accumulo split
     * @param connector Accumulo connector
     * @param username Accumulo username
     * @return Scan authorizations
     * @throws AccumuloException If a generic Accumulo error occurs
     * @throws AccumuloSecurityException If a security exception occurs
     */
    private static Authorizations getScanAuthorizations(ConnectorSession session, AccumuloSplit split, Connector connector, String username)
            throws AccumuloException, AccumuloSecurityException
    {
        String sessionScanUser = AccumuloSessionProperties.getScanUsername(session);
        if (sessionScanUser != null) {
            Authorizations scanAuths = connector.securityOperations().getUserAuthorizations(sessionScanUser);
            LOG.debug("Using session scanner auths for user %s: %s", sessionScanUser, scanAuths);
            return scanAuths;
        }

        Optional<String> scanAuths = split.getScanAuthorizations();
        if (scanAuths.isPresent()) {
            Authorizations auths = new Authorizations(Iterables.toArray(COMMA_SPLITTER.split(scanAuths.get()), String.class));
            LOG.debug("scan_auths table property set: %s", auths);
            return auths;
        }
        else {
            Authorizations auths = connector.securityOperations().getUserAuthorizations(username);
            LOG.debug("scan_auths table property not set, using user auths: %s", auths);
            return auths;
        }
    }

    @Override
    public List<Type> getColumnTypes()
    {
        return columnTypes;
    }

    @Override
    public RecordCursor cursor()
    {
        if (useNullScanner) {
            return new NullRecordCursor();
        }

        return new AccumuloRecordCursor(serializer, scanner, rowIdName, columnHandles, constraints, traceName);
    }

    private static class NullRecordCursor
            implements RecordCursor
    {
        @Override
        public long getTotalBytes()
        {
            return 0;
        }

        @Override
        public long getCompletedBytes()
        {
            return 0;
        }

        @Override
        public long getReadTimeNanos()
        {
            return 0;
        }

        @Override
        public Type getType(int field)
        {
            return null;
        }

        @Override
        public boolean advanceNextPosition()
        {
            return false;
        }

        @Override
        public boolean getBoolean(int field)
        {
            return false;
        }

        @Override
        public long getLong(int field)
        {
            return 0;
        }

        @Override
        public double getDouble(int field)
        {
            return 0;
        }

        @Override
        public Slice getSlice(int field)
        {
            return null;
        }

        @Override
        public Object getObject(int field)
        {
            return null;
        }

        @Override
        public boolean isNull(int field)
        {
            return false;
        }

        @Override
        public void close() {}
    }
}

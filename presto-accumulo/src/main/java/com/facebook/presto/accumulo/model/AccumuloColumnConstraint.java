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

import com.facebook.presto.spi.predicate.Domain;
import com.facebook.presto.spi.type.Type;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

public class AccumuloColumnConstraint
{
    private final String name;
    private final String family;
    private final String qualifier;
    private final Type type;
    private final Optional<Domain> domain;

    @JsonCreator
    public AccumuloColumnConstraint(
            @JsonProperty("name") String name,
            @JsonProperty("family") String family,
            @JsonProperty("qualifier") String qualifier,
            @JsonProperty("type") Type type,
            @JsonProperty("domain") Optional<Domain> domain)
    {
        this.name = requireNonNull(name, "name is null");
        this.family = requireNonNull(family, "family is null");
        this.qualifier = requireNonNull(qualifier, "qualifier is null");
        this.type = requireNonNull(type, "type is null");
        this.domain = requireNonNull(domain, "domain is null");
    }

    @JsonProperty
    public String getName()
    {
        return name;
    }

    @JsonProperty
    public String getFamily()
    {
        return family;
    }

    @JsonProperty
    public String getQualifier()
    {
        return qualifier;
    }

    @JsonProperty
    public Type getType()
    {
        return type;
    }

    @JsonProperty
    public Optional<Domain> getDomain()
    {
        return domain;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(name, family, qualifier, type, domain);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }

        if ((obj == null) || (getClass() != obj.getClass())) {
            return false;
        }

        AccumuloColumnConstraint other = (AccumuloColumnConstraint) obj;
        return Objects.equals(this.name, other.name)
                && Objects.equals(this.family, other.family)
                && Objects.equals(this.qualifier, other.qualifier)
                && Objects.equals(this.type, other.type)
                && Objects.equals(this.domain, other.domain);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("name", this.name)
                .add("family", this.family)
                .add("qualifier", this.qualifier)
                .add("type", this.type)
                .add("domain", this.domain)
                .toString();
    }
}

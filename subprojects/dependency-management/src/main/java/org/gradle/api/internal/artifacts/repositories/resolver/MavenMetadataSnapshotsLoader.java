/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.repositories.resolver;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import org.gradle.api.resources.MissingResourceException;
import org.gradle.api.resources.ResourceException;
import org.gradle.internal.resource.ExternalResource;
import org.gradle.internal.resource.local.DefaultLocallyAvailableExternalResource;
import org.gradle.internal.resource.local.DefaultLocallyAvailableResource;

/**
 * Read the Maven metadata from the maven-metadata-snapshots.xml file
 *
 */
class MavenMetadataSnapshotsLoader extends AbstractMavenMetadataLoader {
    
    public MavenMetadata load(URI metadataLocation) throws ResourceException {
        MavenMetadata metadata = new MavenMetadata();
        try {
            parseMavenMetadataInfo(metadataLocation, metadata);
        } catch (MissingResourceException e) {
            throw e;
        } catch (Exception e) {
            throw new ResourceException(metadataLocation, String.format("Unable to load Maven meta-data from %s.", metadataLocation), e);
        }
        return metadata;
    }

    private void parseMavenMetadataInfo(final URI metadataLocation, final MavenMetadata metadata) throws IOException {
        ExternalResource resource = new DefaultLocallyAvailableExternalResource(metadataLocation, new DefaultLocallyAvailableResource(new File(metadataLocation)));
        try {
            parseMavenMetadataInto(resource, metadata);
        } finally {
            resource.close();
        }
    }
}
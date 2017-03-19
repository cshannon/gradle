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
import java.io.InputStream;
import java.net.URI;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.ivy.util.ContextualSAXHandler;
import org.apache.ivy.util.XMLHelper;
import org.gradle.api.resources.MissingResourceException;
import org.gradle.api.resources.ResourceException;
import org.gradle.internal.ErroringAction;
import org.gradle.internal.resource.ExternalResource;
import org.gradle.internal.resource.local.DefaultLocallyAvailableExternalResource;
import org.gradle.internal.resource.local.DefaultLocallyAvailableResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * Read the Maven metadata from the maven-metadata-local.xml file
 *
 */
class MavenMetadataLocalLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(MavenMetadataLocalLoader.class);

    public MavenMetadata load(URI metadataLocation) throws ResourceException {
        MavenMetadata metadata = new MavenMetadata();
        try {
            parseMavenMetadataInfo(metadataLocation, metadata);
        } catch (MissingResourceException e) {
            throw e;
        } catch (Exception e) {
            throw new ResourceException(metadataLocation,
                    String.format("Unable to load Maven meta-data from %s.", metadataLocation), e);
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

    private void parseMavenMetadataInto(ExternalResource metadataResource, final MavenMetadata mavenMetadata) {
        LOGGER.debug("parsing maven-metadata: {}", metadataResource);
        metadataResource.withContent(new ErroringAction<InputStream>() {
            @Override
            public void doExecute(InputStream inputStream)
                    throws ParserConfigurationException, SAXException, IOException {
                XMLHelper.parse(inputStream, null, new ContextualSAXHandler() {
                    @Override
                    public void endElement(String uri, String localName, String qName) throws SAXException {
                        if ("metadata/versioning/lastUpdated".equals(getContext())) {
                            mavenMetadata.timestamp = getText();
                        }
                        super.endElement(uri, localName, qName);
                    }
                }, null);
            }
        });
    }
}
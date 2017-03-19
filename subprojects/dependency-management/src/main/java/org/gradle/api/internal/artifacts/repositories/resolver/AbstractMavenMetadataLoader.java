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

import java.io.IOException;
import java.io.InputStream;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.ivy.util.ContextualSAXHandler;
import org.apache.ivy.util.XMLHelper;
import org.gradle.internal.ErroringAction;
import org.gradle.internal.resource.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

public class AbstractMavenMetadataLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractMavenMetadataLoader.class);
    
    protected void parseMavenMetadataInto(ExternalResource metadataResource, final MavenMetadata mavenMetadata) {
        LOGGER.debug("parsing maven-metadata: {}", metadataResource);
        metadataResource.withContent(new ErroringAction<InputStream>() {
            @Override
            public void doExecute(InputStream inputStream) throws ParserConfigurationException, SAXException, IOException {
                XMLHelper.parse(inputStream, null, new ContextualSAXHandler() {
                    @Override
                    public void endElement(String uri, String localName, String qName)
                            throws SAXException {
                        if ("metadata/versioning/snapshot/timestamp".equals(getContext())) {
                            mavenMetadata.timestamp = getText();
                        }
                        if ("metadata/versioning/snapshot/buildNumber".equals(getContext())) {
                            mavenMetadata.buildNumber = getText();
                        }
                        if ("metadata/versioning/versions/version".equals(getContext())) {
                            mavenMetadata.versions.add(getText().trim());
                        }
                        super.endElement(uri, localName, qName);
                    }
                }, null);
            }
        });
    }
    
}

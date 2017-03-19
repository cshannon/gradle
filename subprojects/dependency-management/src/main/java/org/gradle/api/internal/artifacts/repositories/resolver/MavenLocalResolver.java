/*
 * Copyright 2013 the original author or authors.
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

import java.net.URI;
import java.util.Date;

import org.gradle.api.Nullable;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.DescriptorParseContext;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.MetaDataParser;
import org.gradle.api.internal.artifacts.repositories.transport.RepositoryTransport;
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier;
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata;
import org.gradle.internal.component.external.model.MutableMavenModuleResolveMetadata;
import org.gradle.internal.resolve.result.DefaultResourceAwareResolveResult;
import org.gradle.internal.resolve.result.ResourceAwareResolveResult;
import org.gradle.internal.resource.ExternalResourceName;
import org.gradle.internal.resource.local.FileStore;
import org.gradle.internal.resource.local.LocallyAvailableExternalResource;
import org.gradle.internal.resource.local.LocallyAvailableResourceFinder;
import org.gradle.internal.resource.transfer.CacheAwareExternalResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MavenLocalResolver extends MavenResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(MavenResolver.class);
    
    private final MavenMetadataLocalLoader mavenMetaDataLocalLoader = new MavenMetadataLocalLoader();
    private final MavenMetadataSnapshotsLoader mavenMetaDataSnapshotsLoader = new MavenMetadataSnapshotsLoader();

    public MavenLocalResolver(String name, URI rootUri, RepositoryTransport transport,
                              LocallyAvailableResourceFinder<ModuleComponentArtifactMetadata> locallyAvailableResourceFinder,
                              FileStore<ModuleComponentArtifactIdentifier> artifactFileStore,
                              MetaDataParser<MutableMavenModuleResolveMetadata> pomParser,
                              ImmutableModuleIdentifierFactory moduleIdentifierFactory,
                              CacheAwareExternalResourceAccessor cacheAwareExternalResourceAccessor) {
        super(name, rootUri, transport, locallyAvailableResourceFinder, artifactFileStore, pomParser, moduleIdentifierFactory, cacheAwareExternalResourceAccessor, null);
    }

    @Override
    protected MutableMavenModuleResolveMetadata parseMetaDataFromResource(
            ModuleComponentIdentifier moduleComponentIdentifier, LocallyAvailableExternalResource cachedResource,
            DescriptorParseContext context) {

        MutableMavenModuleResolveMetadata metaData = super.parseMetaDataFromResource(moduleComponentIdentifier, cachedResource, context);
        
        if (metaData.isChanging() && metaData.getSnapshotTimestamp() == null) {
            ExternalResourceName localMetadataLocation = getWholePattern().toModuleVersionPath(moduleComponentIdentifier).resolve("maven-metadata-local.xml");
            ExternalResourceName localSnapshotMetadataLocation = getWholePattern().toModuleVersionPath(moduleComponentIdentifier).resolve("maven-metadata-snapshots.xml");
            MavenMetadata mavenMetadataLocal = null;
            MavenMetadata mavenMetadataSnapshots = null;
            try {
                mavenMetadataLocal = mavenMetaDataLocalLoader.load(localMetadataLocation.getUri());          
            } catch (Exception e) {
                LOGGER.debug("Could not parse maven-metadata-local {}, skipping", e.getMessage());
            }
            try {
                mavenMetadataSnapshots = mavenMetaDataSnapshotsLoader.load(localSnapshotMetadataLocation.getUri());         
            } catch (Exception e) {
                LOGGER.debug("Could not parse maven-metadata-snapshots {}, skipping", e.getMessage());
            }
            
            if (mavenMetadataLocal == null && mavenMetadataSnapshots != null) {
                metaData.setSnapshotTimestamp(mavenMetadataSnapshots.timestamp);
            } else if (mavenMetadataLocal != null && mavenMetadataSnapshots == null) {
                metaData.setSnapshotTimestamp(mavenMetadataLocal.timestamp);
            } else {
                try {
                    Date localDate = MavenMetadata.parseTimestamp(mavenMetadataLocal.timestamp);
                    Date snapshotDate = MavenMetadata.parseTimestamp(mavenMetadataSnapshots.timestamp);
                    if (localDate.after(snapshotDate)) {
                        metaData.setSnapshotTimestamp(mavenMetadataLocal.timestamp);
                    } else {
                        metaData.setSnapshotTimestamp(mavenMetadataSnapshots.timestamp);
                    }
                } catch (Exception e) {
                    LOGGER.debug("Could not set snapshot timestamp {}, skipping", e.getMessage());
                }
            }
        }
        
        return metaData;
    }


    @Override
    @Nullable
    protected MutableMavenModuleResolveMetadata parseMetaDataFromArtifact(ModuleComponentIdentifier moduleComponentIdentifier, ExternalResourceArtifactResolver artifactResolver, ResourceAwareResolveResult result) {
        MutableMavenModuleResolveMetadata metadata = super.parseMetaDataFromArtifact(moduleComponentIdentifier, artifactResolver, result);
        if (metadata == null) {
            return null;
        }

        if (isOrphanedPom(metadata, artifactResolver)) {
            return null;
        }
        return metadata;
    }

    private boolean isOrphanedPom(MutableMavenModuleResolveMetadata metaData, ExternalResourceArtifactResolver artifactResolver) {
        if (metaData.isPomPackaging()) {
            return false;
        }

        // check custom packaging
        ModuleComponentArtifactMetadata artifact;
        if (metaData.isKnownJarPackaging()) {
            artifact = metaData.artifact("jar", "jar", null);
        } else {
            artifact = metaData.artifact(metaData.getPackaging(), metaData.getPackaging(), null);
        }

        if (artifactResolver.artifactExists(artifact, new DefaultResourceAwareResolveResult())) {
            return false;
        }

        LOGGER.debug("POM file found for module '{}' in repository '{}' but no artifact found. Ignoring.", metaData.getId(), getName());
        return true;
    }
}

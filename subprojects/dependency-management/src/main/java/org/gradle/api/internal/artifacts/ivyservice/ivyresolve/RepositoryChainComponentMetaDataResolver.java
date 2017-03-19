/*
 * Copyright 2011 the original author or authors.
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

package org.gradle.api.internal.artifacts.ivyservice.ivyresolve;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.gradle.api.Transformer;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.internal.artifacts.repositories.resolver.MavenMetadata;
import org.gradle.internal.component.external.model.MavenModuleResolveMetadata;
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata;
import org.gradle.internal.component.model.ComponentOverrideMetadata;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver;
import org.gradle.internal.resolve.result.BuildableComponentResolveResult;
import org.gradle.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepositoryChainComponentMetaDataResolver implements ComponentMetaDataResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryChainComponentMetaDataResolver.class);

    private final List<ModuleComponentRepository> repositories = new ArrayList<ModuleComponentRepository>();
    private final List<String> repositoryNames = new ArrayList<String>();
    private final VersionedComponentChooser versionedComponentChooser;
    private final Transformer<ModuleComponentResolveMetadata, RepositoryChainModuleResolution> metaDataFactory;

    public RepositoryChainComponentMetaDataResolver(VersionedComponentChooser componentChooser, Transformer<ModuleComponentResolveMetadata, RepositoryChainModuleResolution> metaDataFactory) {
        this.versionedComponentChooser = componentChooser;
        this.metaDataFactory = metaDataFactory;
    }

    public void add(ModuleComponentRepository repository) {
        repositories.add(repository);
        repositoryNames.add(repository.getName());
    }

    @Override
    public void resolve(ComponentIdentifier identifier, ComponentOverrideMetadata componentOverrideMetadata, BuildableComponentResolveResult result) {
        if (!(identifier instanceof ModuleComponentIdentifier)) {
            throw new UnsupportedOperationException("Can resolve meta-data for module components only.");
        }

        resolveModule((ModuleComponentIdentifier) identifier, componentOverrideMetadata, result);
    }

    private void resolveModule(ModuleComponentIdentifier identifier, ComponentOverrideMetadata componentOverrideMetadata, BuildableComponentResolveResult result) {
        LOGGER.debug("Attempting to resolve component for {} using repositories {}", identifier, repositoryNames);

        List<Throwable> errors = new ArrayList<Throwable>();

        List<ComponentMetaDataResolveState> resolveStates = new ArrayList<ComponentMetaDataResolveState>();
        for (ModuleComponentRepository repository : repositories) {
            resolveStates.add(new ComponentMetaDataResolveState(identifier, componentOverrideMetadata, repository, versionedComponentChooser));
        }

        final RepositoryChainModuleResolution latestResolved = findBestMatch(resolveStates, errors);
        if (latestResolved != null) {
            LOGGER.debug("Using {} from {}", latestResolved.module.getId(), latestResolved.repository);
            for (Throwable error : errors) {
                LOGGER.debug("Discarding resolve failure.", error);
            }

            result.resolved(metaDataFactory.transform(latestResolved));
            return;
        }
        if (!errors.isEmpty()) {
            result.failed(new ModuleVersionResolveException(identifier, errors));
        } else {
            for (ComponentMetaDataResolveState resolveState : resolveStates) {
                resolveState.applyTo(result);
            }
            result.notFound(identifier);
        }
    }

    private RepositoryChainModuleResolution findBestMatch(List<ComponentMetaDataResolveState> resolveStates, Collection<Throwable> failures) {
        LinkedList<ComponentMetaDataResolveState> queue = new LinkedList<ComponentMetaDataResolveState>();
        queue.addAll(resolveStates);

        LinkedList<ComponentMetaDataResolveState> missing = new LinkedList<ComponentMetaDataResolveState>();

        // A first pass to do local resolves only
        RepositoryChainModuleResolution best = findBestMatch(queue, failures, missing);
        if (best != null) {
            return best;
        }

        // Nothing found - do a second pass
        queue.addAll(missing);
        missing.clear();
        return findBestMatch(queue, failures, missing);
    }

    private RepositoryChainModuleResolution findBestMatch(LinkedList<ComponentMetaDataResolveState> queue, Collection<Throwable> failures, Collection<ComponentMetaDataResolveState> missing) {
        boolean searchForMostRecent = versionedComponentChooser.isSearchForMostRecentChangingModules();
        LOGGER.debug("Search for most recent changing modules is set to {}", searchForMostRecent);
        
        RepositoryChainModuleResolution best = null;
        while (!queue.isEmpty()) {
            ComponentMetaDataResolveState request = queue.removeFirst();
            BuildableModuleComponentMetaDataResolveResult metaDataResolveResult;
            try {
                metaDataResolveResult = request.resolve();
            } catch (Throwable t) {
                failures.add(t);
                continue;
            }
            switch (metaDataResolveResult.getState()) {
                case Failed:
                    failures.add(metaDataResolveResult.getFailure());
                    break;
                case Missing:
                    // Queue this up for checking again later
                    if (request.canMakeFurtherAttempts()) {
                        missing.add(request);
                    }
                    break;
                case Resolved:
                    RepositoryChainModuleResolution moduleResolution = new RepositoryChainModuleResolution(request.repository, metaDataResolveResult.getMetaData());
                    //don't return if searchForMostRecent is true and module is changing because we need to search all repositories
                    if ((!searchForMostRecent || !moduleResolution.module.isChanging()) && !metaDataResolveResult.getMetaData().isGenerated()) {
                        return moduleResolution;
                    }
                    best = best != null ? best : moduleResolution;
                    //compare to other repos
                    if (searchForMostRecent && moduleResolution.module.isChanging()) {
                        if (best.module instanceof MavenModuleResolveMetadata && moduleResolution.module instanceof MavenModuleResolveMetadata) {
                            MavenModuleResolveMetadata bestModule = (MavenModuleResolveMetadata) best.module;
                            MavenModuleResolveMetadata resolveModule = (MavenModuleResolveMetadata) moduleResolution.module;
    
                            try {
                                Date bestDate = MavenMetadata.parseTimestamp(bestModule.getSnapshotTimestamp());
                                Date moduleDate = MavenMetadata.parseTimestamp(resolveModule.getSnapshotTimestamp());
    
                                if (bestDate != null && moduleDate != null) {
                                    best = bestDate.after(moduleDate) ? best : moduleResolution;
                                    LOGGER.debug("Timestamp for {} in repository {} is {}", metaDataResolveResult.getMetaData().getComponentId(), best.repository.getName(), bestDate);
                                    LOGGER.debug("Timestamp for {} in repository {} is {}", metaDataResolveResult.getMetaData().getComponentId(), moduleResolution.repository.getName(), moduleDate);
                                    LOGGER.debug("Updating best resolution for {} to be from repository {}",
                                            metaDataResolveResult.getMetaData().getComponentId(),
                                            best.repository.getName());
                                }
                            } catch (Exception e) {
                                LOGGER.debug("Could not parse timestamp dates: {}", e.getMessage(), e);
                            }
                        }
                    }
                    break;
                default:
                    throw new IllegalStateException("Unexpected state for resolution: " + metaDataResolveResult.getState());
            }
        }

        return best;
    }
}

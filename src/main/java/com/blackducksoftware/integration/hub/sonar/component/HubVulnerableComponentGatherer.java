/**
 * Black Duck Hub Plugin for SonarQube
 *
 * Copyright (C) 2017 Black Duck Software, Inc.
 * http://www.blackducksoftware.com/
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.blackducksoftware.integration.hub.sonar.component;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.sonar.api.config.Settings;

import com.blackducksoftware.integration.exception.IntegrationException;
import com.blackducksoftware.integration.hub.api.item.MetaService;
import com.blackducksoftware.integration.hub.api.vulnerablebomcomponent.VulnerableBomComponentRequestService;
import com.blackducksoftware.integration.hub.global.HubServerConfig;
import com.blackducksoftware.integration.hub.model.view.MatchedFilesView;
import com.blackducksoftware.integration.hub.model.view.ProjectVersionView;
import com.blackducksoftware.integration.hub.model.view.ProjectView;
import com.blackducksoftware.integration.hub.model.view.VulnerableComponentView;
import com.blackducksoftware.integration.hub.model.view.components.FilePathView;
import com.blackducksoftware.integration.hub.request.HubPagedRequest;
import com.blackducksoftware.integration.hub.request.HubRequestFactory;
import com.blackducksoftware.integration.hub.rest.RestConnection;
import com.blackducksoftware.integration.hub.service.HubResponseService;
import com.blackducksoftware.integration.hub.service.HubServicesFactory;
import com.blackducksoftware.integration.hub.sonar.HubPropertyConstants;
import com.blackducksoftware.integration.hub.sonar.HubSonarLogger;
import com.blackducksoftware.integration.hub.sonar.utils.HubSonarUtils;

public class HubVulnerableComponentGatherer implements ComponentGatherer {

    // TODO add "matched-files" to MetaService links
    public static final String MATCHED_FILES = "matched-files";

    private final Settings settings;
    private final HubSonarLogger logger;

    private String hubProjectName;
    private String hubProjectVersionName;

    public HubVulnerableComponentGatherer(final HubSonarLogger logger, final Settings settings) {
        this.logger = logger;
        this.settings = settings;
        setProjectAndVersion(settings);
    }

    @Override
    public List<String> gatherComponents() {
        final HubServerConfig hubServerConfig = HubSonarUtils.getHubServerConfig(settings);
        logger.info(hubServerConfig.toString());
        RestConnection restConnection = null;
        try {
            restConnection = HubSonarUtils.getRestConnection(logger, hubServerConfig);
            restConnection.connect();
        } catch (final IntegrationException e) {
            logger.error(String.format("Error connecting to the Hub server: ", e));
        }

        final HubServicesFactory services = new HubServicesFactory(restConnection);
        final HubRequestFactory hubRequestFactory = new HubRequestFactory(restConnection);
        final HubResponseService hubResponseService = services.createHubResponseService();
        final MetaService metaService = services.createMetaService(logger);

        ProjectVersionView version = null;
        try {
            final ProjectView project = services.createProjectRequestService(logger).getProjectByName(hubProjectName);
            logger.debug(String.format("Hub Project: %s", project == null ? null : project.name));
            version = services.createProjectVersionRequestService(logger).getProjectVersion(project, hubProjectVersionName);
            logger.debug(String.format("Hub Version: %s", version == null ? null : version.versionName));
        } catch (final IntegrationException e) {
            logger.error(e);
        }

        final List<String> allMatchedFiles = new ArrayList<>();
        final List<VulnerableComponentView> components = getVulnerableComponents(version, services.createVulnerableBomComponentRequestService(), metaService);
        if (components != null) {
            for (final VulnerableComponentView component : components) {
                allMatchedFiles.addAll(getMatchedFiles(component, hubRequestFactory, hubResponseService, metaService));
            }
        } else {
            logger.warn("List of vulnerable Hub components was null. No files will be matched.");
        }

        return allMatchedFiles;
    }

    private List<VulnerableComponentView> getVulnerableComponents(final ProjectVersionView version, final VulnerableBomComponentRequestService vulnerableBomComponentRequestService, final MetaService metaService) {
        final String vulnerableBomComponentsLink = metaService.getFirstLinkSafely(version, MetaService.VULNERABLE_COMPONENTS_LINK);
        List<VulnerableComponentView> components = null;
        try {
            logger.debug("Attempting to get vulnerable components from the Hub Project-Version...");
            components = vulnerableBomComponentRequestService.getVulnerableComponentsMatchingComponentName(vulnerableBomComponentsLink);
            logger.debug("Success!");
        } catch (final IntegrationException e) {
            logger.error(e);
        }

        return components;
    }

    private List<String> getMatchedFiles(final VulnerableComponentView component, final HubRequestFactory hubRequestFactory, final HubResponseService hubResponseService, final MetaService metaService) {
        final List<String> matchedFiles = new ArrayList<>();
        final String matchedFilesLink = metaService.getFirstLinkSafely(component, MATCHED_FILES);
        final HubPagedRequest hubPagedRequest = hubRequestFactory.createPagedRequest(matchedFilesLink);
        List<MatchedFilesView> allMatchedFiles = null;
        try {
            allMatchedFiles = hubResponseService.getAllItems(hubPagedRequest, MatchedFilesView.class);
        } catch (final IntegrationException e) {
            logger.error(e);
        }

        if (allMatchedFiles != null) {
            for (final MatchedFilesView matchedFile : allMatchedFiles) {
                final String filePath = getFilePath(matchedFile.filePath);
                if (filePath != null) {
                    matchedFiles.add(filePath);
                }
            }
        }

        return matchedFiles;
    }

    private String getFilePath(final FilePathView filePath) {
        final String composite = filePath.compositePathContext;
        final int lastIndex = composite.length() - 1;
        final int archiveMarkIndex = composite.indexOf("!");
        final int otherMarkIndex = composite.indexOf("#");
        final int startIndex = (otherMarkIndex >= 0 && otherMarkIndex < lastIndex) ? otherMarkIndex + 1 : 0;
        final int endIndex = (archiveMarkIndex > startIndex) ? archiveMarkIndex : lastIndex;

        final String candidateFilePath = composite.substring(startIndex, endIndex);

        // TODO may want to validate OR compare against exclusion patterns

        return candidateFilePath;
    }

    private void setProjectAndVersion(final Settings settings) {
        hubProjectName = HubSonarUtils.getAndTrimProp(settings, HubSonarUtils.SONAR_PROJECT_NAME_KEY);
        logger.debug(String.format("Default Hub Project to look for: %s", hubProjectName));
        hubProjectVersionName = HubSonarUtils.getAndTrimProp(settings, HubSonarUtils.SONAR_PROJECT_VERSION_KEY);
        logger.debug(String.format("Default Hub Project-Version to look for: %s", hubProjectVersionName));

        // Only override if the user provides a project AND project version
        final String hubProjectNameOverride = HubSonarUtils.getAndTrimProp(settings, HubPropertyConstants.HUB_PROJECT_OVERRIDE);
        final String hubProjectVersionNameOverride = HubSonarUtils.getAndTrimProp(settings, HubPropertyConstants.HUB_PROJECT_VERSION_OVERRIED);
        if (StringUtils.isNotEmpty(hubProjectNameOverride) && StringUtils.isNotEmpty(hubProjectVersionNameOverride)) {
            hubProjectName = hubProjectNameOverride;
            logger.debug(String.format("Overriden Hub Project to look for: %s", hubProjectName));
            hubProjectVersionName = hubProjectVersionNameOverride;
            logger.debug(String.format("Overriden Hub Project-Version to look for: %s", hubProjectVersionName));
        }
    }

}

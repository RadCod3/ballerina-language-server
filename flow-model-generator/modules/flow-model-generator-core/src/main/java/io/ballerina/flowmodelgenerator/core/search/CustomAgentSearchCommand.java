/*
 *  Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com)
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.flowmodelgenerator.core.search;

import io.ballerina.centralconnector.CentralAPI;
import io.ballerina.centralconnector.RemoteCentral;
import io.ballerina.centralconnector.response.PackageResponse;
import io.ballerina.flowmodelgenerator.core.model.AvailableNode;
import io.ballerina.flowmodelgenerator.core.model.Category;
import io.ballerina.flowmodelgenerator.core.model.Codedata;
import io.ballerina.flowmodelgenerator.core.model.Item;
import io.ballerina.flowmodelgenerator.core.model.Metadata;
import io.ballerina.flowmodelgenerator.core.model.NodeKind;
import io.ballerina.modelgenerator.commons.CommonUtils;
import io.ballerina.modelgenerator.commons.SearchResult;
import io.ballerina.projects.Project;
import io.ballerina.tools.text.LineRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles the search command for custom agents with keyword "Type/Agent" from Ballerina Central.
 *
 * @since 1.2.0
 */
public class CustomAgentSearchCommand extends SearchCommand {

    private static final String KEYWORD = "\"Type/Agent\"";
    private static final String CATEGORY_NAME = "Custom Agents";
    private static final String INIT_SYMBOL = "init";

    private List<AvailableNode> cachedAgents;

    public CustomAgentSearchCommand(Project project, LineRange position, Map<String, String> queryMap) {
        super(project, position, queryMap);
    }

    @Override
    protected List<Item> defaultView() {
        List<AvailableNode> agents = fetchAgentsFromCentral(null);
        if (agents.isEmpty()) {
            return rootBuilder.build().items();
        }

        Category.Builder categoryBuilder = rootBuilder.stepIn(CATEGORY_NAME, null, null);
        agents.forEach(categoryBuilder::node);
        return rootBuilder.build().items();
    }

    @Override
    protected List<Item> search() {
        List<AvailableNode> agents = fetchAgentsFromCentral(query);
        if (agents.isEmpty()) {
            return rootBuilder.build().items();
        }

        // Filter by query if provided
        List<AvailableNode> filteredAgents = agents.stream()
                .filter(agent -> query == null || query.isEmpty() ||
                        agent.metadata().label().toLowerCase(Locale.ROOT)
                                .contains(query.toLowerCase(Locale.ROOT)))
                .toList();

        Category.Builder categoryBuilder = rootBuilder.stepIn(CATEGORY_NAME, null, null);
        filteredAgents.forEach(categoryBuilder::node);
        return rootBuilder.build().items();
    }

    @Override
    protected Map<String, List<SearchResult>> fetchPopularItems() {
        return Collections.emptyMap();
    }

    /**
     * Fetches packages from Ballerina Central with the "Type/Agent" keyword.
     *
     * @param searchQuery optional search query to filter results
     * @return list of AvailableNode representing the packages
     */
    private List<AvailableNode> fetchAgentsFromCentral(String searchQuery) {
        if (cachedAgents != null && (searchQuery == null || searchQuery.isEmpty())) {
            return cachedAgents;
        }

        List<AvailableNode> agents = new ArrayList<>();
        try {
            CentralAPI centralClient = RemoteCentral.getInstance();
            Map<String, String> queryMap = new HashMap<>();
            queryMap.put("keyword", KEYWORD);
            queryMap.put("limit", String.valueOf(limit));
            queryMap.put("offset", String.valueOf(offset));

            if (searchQuery != null && !searchQuery.isEmpty()) {
                queryMap.put("q", searchQuery);
            }

            PackageResponse response = centralClient.searchPackages(queryMap);
            if (response != null && response.packages() != null) {
                for (PackageResponse.Package pkg : response.packages()) {
                    AvailableNode node = generateAvailableNode(pkg);
                    agents.add(node);
                }
            }

            // Cache the results only for default view (no search query)
            if (searchQuery == null || searchQuery.isEmpty()) {
                cachedAgents = agents;
            }
        } catch (Exception e) {
            // Log error and return empty list
            // In production, consider proper logging
        }

        return agents;
    }

    /**
     * Generates an AvailableNode from a package response.
     *
     * @param pkg the package from Central API response
     * @return AvailableNode representing the package
     */
    private static AvailableNode generateAvailableNode(PackageResponse.Package pkg) {
        Metadata metadata = new Metadata.Builder<>(null)
                .label(pkg.name())
                .description(pkg.summary())
                .icon(CommonUtils.generateIcon(pkg.organization(), pkg.name(), pkg.version()))
                .build();

        Codedata codedata = new Codedata.Builder<>(null)
                .node(NodeKind.CUSTOM_AGENT)
                .org(pkg.organization())
                .module(pkg.name())
                .packageName(pkg.name())
                .symbol(INIT_SYMBOL)
                .version(pkg.version())
                .build();

        return new AvailableNode(metadata, codedata, true);
    }
}

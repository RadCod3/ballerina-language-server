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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import io.ballerina.centralconnector.CentralAPI;
import io.ballerina.centralconnector.RemoteCentral;
import io.ballerina.centralconnector.response.PackageResponse;
import io.ballerina.flowmodelgenerator.core.LocalIndexCentral;
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
 * Handles the search command for agents. Supports both local agent search (from LocalIndexCentral) and remote agent
 * search (from Ballerina Central with "Type/Agent" keyword).
 *
 * <p>When {@code remote=true} is passed in the queryMap, the command searches agents from
 * Ballerina Central. Otherwise, it uses the local search behavior via LocalIndexCentral.</p>
 *
 * @since 1.2.0
 */
public class AgentSearchCommand extends SearchCommand {

    private static final Gson GSON = new Gson();
    private static final String KEYWORD = "\"Type/Agent\"";
    private static final String CUSTOM_AGENTS_CATEGORY = "Custom Agents";
    private static final String INIT_SYMBOL = "init";

    private List<Item> cachedLocalAgents;
    private List<AvailableNode> cachedRemoteAgents;
    private final String orgName;
    private final boolean remote;

    public AgentSearchCommand(Project project, LineRange position, Map<String, String> queryMap) {
        super(project, position, queryMap);
        orgName = queryMap.get("orgName");
        remote = Boolean.parseBoolean(queryMap.getOrDefault("remote", "false"));
    }

    @Override
    protected List<Item> defaultView() {
        if (remote) {
            return getRemoteAgents(null);
        }
        return getLocalAgents();
    }

    @Override
    protected List<Item> search() {
        if (remote) {
            return getRemoteAgents(query);
        }
        return searchLocalAgents();
    }

    private List<Item> searchLocalAgents() {
        List<Item> agents = getLocalAgents();
        if (agents.isEmpty() || !(agents.getFirst() instanceof Category agentCategory)) {
            return agents;
        }

        List<Item> stores = agentCategory.items();
        List<Item> matchingStores = stores.stream()
                .filter(item -> item instanceof AvailableNode availableNode &&
                        (orgName == null || availableNode.codedata().org().equalsIgnoreCase(orgName)) &&
                        (query == null || availableNode.metadata().label().toLowerCase(Locale.ROOT)
                                .contains(query.toLowerCase(Locale.ROOT))))
                .toList();

        stores.clear();
        stores.addAll(matchingStores);

        return List.of(agentCategory);
    }

    @Override
    protected Map<String, List<SearchResult>> fetchPopularItems() {
        return Collections.emptyMap();
    }

    @Override
    public JsonArray execute() {
        List<Item> items;
        if (remote) {
            items = (query.isEmpty()) ? defaultView() : search();
        } else {
            items = (query.isEmpty() && orgName == null) ? defaultView() : search();
        }
        return GSON.toJsonTree(items).getAsJsonArray();
    }

    private List<Item> getLocalAgents() {
        if (cachedLocalAgents == null) {
            cachedLocalAgents = List.copyOf(LocalIndexCentral.getInstance().getAgents());
        }
        return cachedLocalAgents;
    }

    /**
     * Fetches agents from Ballerina Central with the "Type/Agent" keyword and builds the category.
     *
     * @param searchQuery optional search query to filter results
     * @return list of Items representing the remote agents category
     */
    private List<Item> getRemoteAgents(String searchQuery) {
        List<AvailableNode> agents = fetchAgentsFromCentral(searchQuery);
        if (agents.isEmpty()) {
            return rootBuilder.build().items();
        }

        // Filter by query if provided
        List<AvailableNode> filteredAgents = agents;
        if (searchQuery != null && !searchQuery.isEmpty()) {
            filteredAgents = agents.stream().filter(agent -> agent.metadata().label().toLowerCase(Locale.ROOT)
                    .contains(searchQuery.toLowerCase(Locale.ROOT))).toList();
        }

        Category.Builder categoryBuilder = rootBuilder.stepIn(CUSTOM_AGENTS_CATEGORY, null, null);
        filteredAgents.forEach(categoryBuilder::node);
        return rootBuilder.build().items();
    }

    /**
     * Fetches packages from Ballerina Central with the "Type/Agent" keyword.
     *
     * @param searchQuery optional search query to filter results
     * @return list of AvailableNode representing the packages
     */
    private List<AvailableNode> fetchAgentsFromCentral(String searchQuery) {
        if (cachedRemoteAgents != null && (searchQuery == null || searchQuery.isEmpty())) {
            return cachedRemoteAgents;
        }

        List<AvailableNode> agents = new ArrayList<>();
        try {
            PackageResponse response = getPackageResponse(searchQuery);
            if (response != null && response.packages() != null) {
                for (PackageResponse.Package pkg : response.packages()) {
                    AvailableNode node = generateAvailableNode(pkg);
                    agents.add(node);
                }
            }

            // Cache the results only for default view (no search query)
            if (searchQuery == null || searchQuery.isEmpty()) {
                cachedRemoteAgents = agents;
            }
        } catch (RuntimeException ignored) {
        }

        return agents;
    }

    private PackageResponse getPackageResponse(String searchQuery) {
        CentralAPI centralClient = RemoteCentral.getInstance();
        Map<String, String> centralQueryMap = new HashMap<>();
        centralQueryMap.put("keyword", KEYWORD);
        centralQueryMap.put("limit", String.valueOf(limit));
        centralQueryMap.put("offset", String.valueOf(offset));

        if (searchQuery != null && !searchQuery.isEmpty()) {
            centralQueryMap.put("q", searchQuery);
        }

        return centralClient.searchPackages(centralQueryMap);
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

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
import io.ballerina.compiler.api.ModuleID;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.ClassSymbol;
import io.ballerina.compiler.api.symbols.Documentation;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.flowmodelgenerator.core.LocalIndexCentral;
import io.ballerina.flowmodelgenerator.core.model.AvailableNode;
import io.ballerina.flowmodelgenerator.core.model.Category;
import io.ballerina.flowmodelgenerator.core.model.Codedata;
import io.ballerina.flowmodelgenerator.core.model.Item;
import io.ballerina.flowmodelgenerator.core.model.Metadata;
import io.ballerina.flowmodelgenerator.core.model.NodeKind;
import io.ballerina.modelgenerator.commons.CommonUtils;
import io.ballerina.modelgenerator.commons.PackageUtil;
import io.ballerina.modelgenerator.commons.SearchResult;
import io.ballerina.projects.Module;
import io.ballerina.projects.PackageCompilation;
import io.ballerina.projects.Project;
import io.ballerina.tools.text.LineRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Handles the search command for agents. Supports three source types:
 * <ul>
 *   <li>{@code default} - Searches pre-defined agents from LocalIndexCentral (JSON-based)</li>
 *   <li>{@code standard} - Searches agents from Ballerina Central with "Type/Agent" keyword</li>
 *   <li>{@code local} - Searches locally defined classes that are subtypes of BaseAgent</li>
 * </ul>
 *
 * @since 1.2.0
 */
public class AgentSearchCommand extends SearchCommand {

    private static final Gson GSON = new Gson();
    private static final String KEYWORD = "\"Type/Agent\"";
    private static final String STANDARD_AGENTS_CATEGORY = "Standard Agents";
    private static final String LOCAL_AGENTS_CATEGORY = "Local Agents";
    private static final String INIT_SYMBOL = "init";

    // Source type constants
    private static final String SOURCE_DEFAULT = "default";
    private static final String SOURCE_STANDARD = "standard";
    private static final String SOURCE_LOCAL = "local";

    // Agent type checking constants
    private static final String BASE_AGENT_TYPE_NAME = "BaseAgent";
    private static final String BALLERINA_ORG = "ballerina";
    private static final String BALLERINAX_ORG = "ballerinax";
    private static final String AI_MODULE = "ai";

    private List<Item> cachedDefaultAgents;
    private List<AvailableNode> cachedStandardAgents;
    private final String orgName;
    private final String source;

    public AgentSearchCommand(Project project, LineRange position, Map<String, String> queryMap) {
        super(project, position, queryMap);
        orgName = queryMap.get("orgName");
        source = queryMap.getOrDefault("source", SOURCE_DEFAULT);
    }

    @Override
    protected List<Item> defaultView() {
        return switch (source) {
            case SOURCE_STANDARD -> getStandardAgents(null);
            case SOURCE_LOCAL -> getLocalProjectAgents();
            default -> getDefaultAgents();
        };
    }

    @Override
    protected List<Item> search() {
        return switch (source) {
            case SOURCE_STANDARD -> getStandardAgents(query);
            case SOURCE_LOCAL -> searchLocalProjectAgents();
            default -> searchDefaultAgents();
        };
    }

    private List<Item> searchDefaultAgents() {
        List<Item> agents = getDefaultAgents();
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
        switch (source) {
            case SOURCE_STANDARD -> items = query.isEmpty() ? defaultView() : search();
            case SOURCE_LOCAL -> items = query.isEmpty() ? defaultView() : search();
            default -> items = (query.isEmpty() && orgName == null) ? defaultView() : search();
        }
        return GSON.toJsonTree(items).getAsJsonArray();
    }

    private List<Item> getDefaultAgents() {
        if (cachedDefaultAgents == null) {
            cachedDefaultAgents = List.copyOf(LocalIndexCentral.getInstance().getAgents());
        }
        return cachedDefaultAgents;
    }

    /**
     * Fetches agents from Ballerina Central with the "Type/Agent" keyword and builds the category.
     *
     * @param searchQuery optional search query to filter results
     * @return list of Items representing the standard agents category
     */
    private List<Item> getStandardAgents(String searchQuery) {
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

        Category.Builder categoryBuilder = rootBuilder.stepIn(STANDARD_AGENTS_CATEGORY, null, null);
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
        if (cachedStandardAgents != null && (searchQuery == null || searchQuery.isEmpty())) {
            return cachedStandardAgents;
        }

        List<AvailableNode> agents = new ArrayList<>();
        try {
            PackageResponse response = getPackageResponse(searchQuery);
            if (response != null && response.packages() != null) {
                for (PackageResponse.Package pkg : response.packages()) {
                    AvailableNode node = generateStandardAgentNode(pkg);
                    agents.add(node);
                }
            }

            // Cache the results only for default view (no search query)
            if (searchQuery == null || searchQuery.isEmpty()) {
                cachedStandardAgents = agents;
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
     * Generates an AvailableNode from a package response for standard agents.
     *
     * @param pkg the package from Central API response
     * @return AvailableNode representing the package
     */
    private static AvailableNode generateStandardAgentNode(PackageResponse.Package pkg) {
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

    /**
     * Searches for locally defined agent classes in the current project that are subtypes of BaseAgent.
     *
     * @return list of Items representing the local agents category
     */
    private List<Item> getLocalProjectAgents() {
        List<AvailableNode> localAgents = findLocalAgentClasses();
        if (localAgents.isEmpty()) {
            return rootBuilder.build().items();
        }

        Category.Builder categoryBuilder = rootBuilder.stepIn(LOCAL_AGENTS_CATEGORY, null, null);
        localAgents.forEach(categoryBuilder::node);
        return rootBuilder.build().items();
    }

    /**
     * Searches for locally defined agent classes with query filtering.
     *
     * @return list of Items representing the filtered local agents category
     */
    private List<Item> searchLocalProjectAgents() {
        List<AvailableNode> localAgents = findLocalAgentClasses();
        if (localAgents.isEmpty()) {
            return rootBuilder.build().items();
        }

        // Filter by query if provided
        List<AvailableNode> filteredAgents = localAgents;
        if (query != null && !query.isEmpty()) {
            filteredAgents = localAgents.stream()
                    .filter(agent -> agent.metadata().label().toLowerCase(Locale.ROOT)
                            .contains(query.toLowerCase(Locale.ROOT)) ||
                            (agent.codedata().object() != null &&
                                    agent.codedata().object().toLowerCase(Locale.ROOT)
                                            .contains(query.toLowerCase(Locale.ROOT))))
                    .toList();
        }

        // Filter by orgName if provided
        if (orgName != null && !filteredAgents.isEmpty()) {
            filteredAgents = filteredAgents.stream()
                    .filter(agent -> agent.codedata().org().equalsIgnoreCase(orgName))
                    .toList();
        }

        Category.Builder categoryBuilder = rootBuilder.stepIn(LOCAL_AGENTS_CATEGORY, null, null);
        filteredAgents.forEach(categoryBuilder::node);
        return rootBuilder.build().items();
    }

    /**
     * Finds all class symbols in the current project that are subtypes of BaseAgent.
     *
     * @return list of AvailableNode representing local agent classes
     */
    private List<AvailableNode> findLocalAgentClasses() {
        PackageCompilation compilation = PackageUtil.getCompilation(project);
        Iterable<Module> modules = project.currentPackage().modules();
        List<AvailableNode> localAgents = new ArrayList<>();

        for (Module module : modules) {
            SemanticModel semanticModel = compilation.getSemanticModel(module.moduleId());
            List<Symbol> symbols = semanticModel.moduleSymbols();

            for (Symbol symbol : symbols) {
                if (symbol.kind() != SymbolKind.CLASS) {
                    continue;
                }

                ClassSymbol classSymbol = (ClassSymbol) symbol;

                // Check if class has BaseAgent type inclusion
                if (!isBaseAgentSubtype(classSymbol)) {
                    continue;
                }

                // Build AvailableNode for this local agent class
                Optional<ModuleSymbol> optModule = symbol.getModule();
                if (optModule.isEmpty()) {
                    continue;
                }

                AvailableNode node = buildLocalAgentNode(classSymbol, optModule.get());
                localAgents.add(node);
            }
        }

        return localAgents;
    }

    /**
     * Checks if a class symbol is a subtype of BaseAgent from ballerina/ai or ballerinax/ai module.
     *
     * @param classSymbol the class symbol to check
     * @return true if the class is a subtype of BaseAgent
     */
    private static boolean isBaseAgentSubtype(ClassSymbol classSymbol) {
        return classSymbol.typeInclusions().stream()
                .filter(typeSymbol -> typeSymbol instanceof TypeReferenceTypeSymbol)
                .map(typeSymbol -> (TypeReferenceTypeSymbol) typeSymbol)
                .filter(typeRef -> typeRef.definition().nameEquals(BASE_AGENT_TYPE_NAME))
                .map(TypeSymbol::getModule)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .anyMatch(moduleSymbol -> {
                    ModuleID moduleId = moduleSymbol.id();
                    return (BALLERINA_ORG.equals(moduleId.orgName()) ||
                            BALLERINAX_ORG.equals(moduleId.orgName())) &&
                            AI_MODULE.equals(moduleId.moduleName());
                });
    }

    /**
     * Builds an AvailableNode for a local agent class.
     *
     * @param classSymbol  the class symbol
     * @param moduleSymbol the module symbol containing the class
     * @return AvailableNode representing the local agent class
     */
    private static AvailableNode buildLocalAgentNode(ClassSymbol classSymbol, ModuleSymbol moduleSymbol) {
        ModuleID moduleId = moduleSymbol.id();
        String className = classSymbol.getName().orElse("Agent");
        String description = classSymbol.documentation()
                .flatMap(Documentation::description)
                .orElse("Local agent class");

        Metadata metadata = new Metadata.Builder<>(null)
                .label(className)
                .description(description)
                .icon(CommonUtils.generateIcon(moduleId.orgName(), moduleId.packageName(), moduleId.version()))
                .build();

        Codedata codedata = new Codedata.Builder<>(null)
                .node(NodeKind.LOCAL_AGENT)
                .org(moduleId.orgName())
                .module(moduleId.moduleName())
                .packageName(moduleId.packageName())
                .object(className)
                .symbol(INIT_SYMBOL)
                .version(moduleId.version())
                .isGenerated(true)
                .build();

        return new AvailableNode(metadata, codedata, true);
    }
}

/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.discovery;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Assertions;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.coordination.Coordinator;
import org.elasticsearch.cluster.coordination.ElectionStrategy;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RerouteService;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.service.ClusterApplier;
import org.elasticsearch.cluster.service.MasterService;
import org.elasticsearch.common.Randomness;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.discovery.zen.ZenDiscovery;
import org.elasticsearch.gateway.GatewayMetaState;
import org.elasticsearch.monitor.NodeHealthService;
import org.elasticsearch.plugins.DiscoveryPlugin;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.elasticsearch.node.Node.NODE_NAME_SETTING;

/**
 * A module for loading classes for node discovery.
 */
public class DiscoveryModule {
    private static final Logger logger = LogManager.getLogger(DiscoveryModule.class);

    public static final String ZEN_DISCOVERY_TYPE = "legacy-zen-for-testing-only-do-not-use";
    public static final String ZEN2_DISCOVERY_TYPE = "zen";

    public static final String SINGLE_NODE_DISCOVERY_TYPE = "single-node";

    public static final Setting<String> DISCOVERY_TYPE_SETTING = new Setting<>(
        "discovery.type",
        ZEN2_DISCOVERY_TYPE,
        Function.identity(),
        Property.NodeScope
    );
    public static final Setting<List<String>> LEGACY_DISCOVERY_HOSTS_PROVIDER_SETTING = Setting.listSetting(
        "discovery.zen.hosts_provider",
        Collections.emptyList(),
        Function.identity(),
        Property.NodeScope,
        Property.Deprecated
    );
    public static final Setting<List<String>> DISCOVERY_SEED_PROVIDERS_SETTING = Setting.listSetting(
        "discovery.seed_providers",
        Collections.emptyList(),
        Function.identity(),
        Property.NodeScope
    );

    public static final String DEFAULT_ELECTION_STRATEGY = "default";

    public static final Setting<String> ELECTION_STRATEGY_SETTING = new Setting<>(
        "cluster.election.strategy",
        DEFAULT_ELECTION_STRATEGY,
        Function.identity(),
        Property.NodeScope
    );

    private final Discovery discovery;

    public DiscoveryModule(
        Settings settings,
        ThreadPool threadPool,
        BigArrays bigArrays,
        TransportService transportService,
        Client client,
        NamedWriteableRegistry namedWriteableRegistry,
        NetworkService networkService,
        MasterService masterService,
        ClusterApplier clusterApplier,
        ClusterSettings clusterSettings,
        List<DiscoveryPlugin> plugins,
        AllocationService allocationService,
        Path configFile,
        GatewayMetaState gatewayMetaState,
        RerouteService rerouteService,
        NodeHealthService nodeHealthService
    ) {
        final Collection<BiConsumer<DiscoveryNode, ClusterState>> joinValidators = new ArrayList<>();
        final Map<String, Supplier<SeedHostsProvider>> hostProviders = new HashMap<>();
        hostProviders.put("settings", () -> new SettingsBasedSeedHostsProvider(settings, transportService));
        hostProviders.put("file", () -> new FileBasedSeedHostsProvider(configFile));
        final Map<String, ElectionStrategy> electionStrategies = new HashMap<>();
        electionStrategies.put(DEFAULT_ELECTION_STRATEGY, ElectionStrategy.DEFAULT_INSTANCE);
        for (DiscoveryPlugin plugin : plugins) {
            plugin.getSeedHostProviders(transportService, networkService).forEach((key, value) -> {
                if (hostProviders.put(key, value) != null) {
                    throw new IllegalArgumentException("Cannot register seed provider [" + key + "] twice");
                }
            });
            BiConsumer<DiscoveryNode, ClusterState> joinValidator = plugin.getJoinValidator();
            if (joinValidator != null) {
                joinValidators.add(joinValidator);
            }
            plugin.getElectionStrategies().forEach((key, value) -> {
                if (electionStrategies.put(key, value) != null) {
                    throw new IllegalArgumentException("Cannot register election strategy [" + key + "] twice");
                }
            });
        }

        List<String> seedProviderNames = getSeedProviderNames(settings);
        // for bwc purposes, add settings provider even if not explicitly specified
        if (seedProviderNames.contains("settings") == false) {
            List<String> extendedSeedProviderNames = new ArrayList<>();
            extendedSeedProviderNames.add("settings");
            extendedSeedProviderNames.addAll(seedProviderNames);
            seedProviderNames = extendedSeedProviderNames;
        }

        final Set<String> missingProviderNames = new HashSet<>(seedProviderNames);
        missingProviderNames.removeAll(hostProviders.keySet());
        if (missingProviderNames.isEmpty() == false) {
            throw new IllegalArgumentException("Unknown seed providers " + missingProviderNames);
        }

        List<SeedHostsProvider> filteredSeedProviders = seedProviderNames.stream()
            .map(hostProviders::get)
            .map(Supplier::get)
            .collect(Collectors.toList());

        String discoveryType = DISCOVERY_TYPE_SETTING.get(settings);

        final SeedHostsProvider seedHostsProvider = hostsResolver -> {
            final List<TransportAddress> addresses = new ArrayList<>();
            for (SeedHostsProvider provider : filteredSeedProviders) {
                addresses.addAll(provider.getSeedAddresses(hostsResolver));
            }
            return Collections.unmodifiableList(addresses);
        };

        final ElectionStrategy electionStrategy = electionStrategies.get(ELECTION_STRATEGY_SETTING.get(settings));
        if (electionStrategy == null) {
            throw new IllegalArgumentException("Unknown election strategy " + ELECTION_STRATEGY_SETTING.get(settings));
        }

        if (ZEN2_DISCOVERY_TYPE.equals(discoveryType) || SINGLE_NODE_DISCOVERY_TYPE.equals(discoveryType)) {
            discovery = new Coordinator(
                NODE_NAME_SETTING.get(settings),
                settings,
                clusterSettings,
                bigArrays,
                transportService,
                client,
                namedWriteableRegistry,
                allocationService,
                masterService,
                gatewayMetaState::getPersistedState,
                seedHostsProvider,
                clusterApplier,
                joinValidators,
                new Random(Randomness.get().nextLong()),
                rerouteService,
                electionStrategy,
                nodeHealthService
            );
        } else if (Assertions.ENABLED && ZEN_DISCOVERY_TYPE.equals(discoveryType)) {
            discovery = new ZenDiscovery(
                settings,
                threadPool,
                transportService,
                namedWriteableRegistry,
                masterService,
                clusterApplier,
                clusterSettings,
                seedHostsProvider,
                allocationService,
                joinValidators,
                rerouteService
            );
        } else {
            throw new IllegalArgumentException("Unknown discovery type [" + discoveryType + "]");
        }

        logger.info("using discovery type [{}] and seed hosts providers {}", discoveryType, seedProviderNames);
    }

    private List<String> getSeedProviderNames(Settings settings) {
        if (LEGACY_DISCOVERY_HOSTS_PROVIDER_SETTING.exists(settings)) {
            if (DISCOVERY_SEED_PROVIDERS_SETTING.exists(settings)) {
                throw new IllegalArgumentException(
                    "it is forbidden to set both ["
                        + DISCOVERY_SEED_PROVIDERS_SETTING.getKey()
                        + "] and ["
                        + LEGACY_DISCOVERY_HOSTS_PROVIDER_SETTING.getKey()
                        + "]"
                );
            }
            return LEGACY_DISCOVERY_HOSTS_PROVIDER_SETTING.get(settings);
        }
        return DISCOVERY_SEED_PROVIDERS_SETTING.get(settings);
    }

    public static boolean isSingleNodeDiscovery(Settings settings) {
        return SINGLE_NODE_DISCOVERY_TYPE.equals(DISCOVERY_TYPE_SETTING.get(settings));
    }

    public Discovery getDiscovery() {
        return discovery;
    }
}

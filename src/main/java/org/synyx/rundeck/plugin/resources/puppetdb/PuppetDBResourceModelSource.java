package org.synyx.rundeck.plugin.resources.puppetdb;

import com.dtolabs.rundeck.core.common.INodeSet;
import com.dtolabs.rundeck.core.resources.ResourceModelSource;
import com.dtolabs.rundeck.core.resources.ResourceModelSourceException;
import com.puppetlabs.puppetdb.javaclient.PuppetDBClient;
import com.puppetlabs.puppetdb.javaclient.model.Fact;
import com.puppetlabs.puppetdb.javaclient.model.Node;
import com.puppetlabs.puppetdb.javaclient.query.Expression;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.CacheManagerBuilder;
import org.ehcache.Status;
import org.ehcache.config.CacheConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.puppetlabs.puppetdb.javaclient.query.Query.eq;
import static com.puppetlabs.puppetdb.javaclient.query.Query.or;

/**
 * @author Johannes Graf - graf@synyx.de
 */
public class PuppetDBResourceModelSource implements ResourceModelSource {

    private static final Logger LOG = LoggerFactory.getLogger(PuppetDBResourceModelSource.class);

    private final PuppetDBClient client;
    private final String username;
    private final Set<String> customFactNamesToQuery;
    private final Set<String> mandatoryFactNames = new HashSet<String>(Arrays.asList("hardwaremodel", "operatingsystem", "operatingsystemrelease", "osfamily"));
    private final CacheManager cacheManager;

    public PuppetDBResourceModelSource(PuppetDBClient puppetDBClient, String username) {
        this(puppetDBClient, username, null);
    }

    public PuppetDBResourceModelSource(PuppetDBClient puppetDBClient, String username, Set<String> customFactNamesToQuery) {
        this.client = puppetDBClient;
        this.username = username;
        this.customFactNamesToQuery = customFactNamesToQuery;

        this.cacheManager = CacheManagerBuilder.newCacheManagerBuilder()
                .withCache(
                        "puppetdb",
                        CacheConfigurationBuilder.newCacheConfigurationBuilder().buildConfig(String.class, INodeSet.class)
                )
                .build(true);
    }

    @Override
    public INodeSet getNodes() throws ResourceModelSourceException {

        Cache<String, INodeSet> cache = cacheManager.getCache("puppetdb", String.class, INodeSet.class);

        if(cache.containsKey("nodes")) {
            LOG.info("Using cached puppet nodes");
        } else {
            LOG.info("Cache is empty");
            cache.put("nodes", queryNodesFromPuppetDB());
            LOG.info("Cache refreshed");
        }

        return cache.get("nodes");
    }

    private INodeSet queryNodesFromPuppetDB() throws ResourceModelSourceException {
        try {
            LOG.info("Requesting nodes from PuppetDB");
            List<Node> activeNodes = client.getActiveNodes(null);
            if (activeNodes.isEmpty()) {
                throw new ResourceModelSourceException("Received ZERO nodes from PuppetDB!");
            } else {
                LOG.info("Received {} nodes from PuppetDB", activeNodes.size());
            }

            INodeSet rundeckNodes = new PuppetNodesToRundeckConverter(this.username).convert(activeNodes);

            LOG.info("Requesting facts from PuppetDB");
            List<Fact> facts = client.getFacts(createFactsQuery());
            if (facts.isEmpty()) {
                throw new ResourceModelSourceException("Received ZERO facts from PuppetDB!");
            } else {
                LOG.info("Received {} facts from PuppetDB", facts.size());
            }

            INodeSet mappedRundeckNodes = new PuppetFactsRundeckNodeEnricher().enrich(rundeckNodes, facts);
            return mappedRundeckNodes;
        } catch (IOException e) {
            throw new ResourceModelSourceException("Error requesting PuppetDB!", e);
        }
    }

    private Expression<Fact> createFactsQuery() throws IOException {

        ArrayList<Expression<Fact>> factsToQuery = new ArrayList<>();

        Set<String> factNames = new HashSet<>();

        factNames.addAll(mandatoryFactNames);

        if (customFactNamesToQuery != null) {
            factNames.addAll(customFactNamesToQuery);
        }

        for (String factName : factNames) {
            factsToQuery.add(eq(Fact.NAME, factName));
        }

        return or(factsToQuery);
    }
}

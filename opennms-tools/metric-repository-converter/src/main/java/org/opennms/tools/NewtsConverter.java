/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2013-2015 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2015 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.BooleanUtils;
import org.joda.time.Interval;
import org.joda.time.Period;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;
import org.opennms.core.db.DataSourceFactory;
import org.opennms.netmgt.model.ResourcePath;
import org.opennms.netmgt.newts.support.NewtsUtils;
import org.opennms.netmgt.rrd.model.AbstractDS;
import org.opennms.netmgt.rrd.model.AbstractRRD;
import org.opennms.netmgt.rrd.model.RrdConvertUtils;
import org.opennms.netmgt.rrd.model.RrdSample;
import org.opennms.newts.api.MetricType;
import org.opennms.newts.api.Resource;
import org.opennms.newts.api.Sample;
import org.opennms.newts.api.SampleRepository;
import org.opennms.newts.api.Timestamp;
import org.opennms.newts.api.ValueType;
import org.opennms.newts.api.search.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.google.common.collect.Lists;

/**
 * The Class NewtsConverter.
 *
 * The converter requires multi-ds metrics; otherwise it won't work.
 *
 * @author Alejandro Galue <agalue@opennms.org>
 * @author Dustin Frisch <dustin@opennms.org>
 */
public class NewtsConverter implements AutoCloseable {
    private final static Logger LOG = LoggerFactory.getLogger(NewtsConverter.class);

    /** The Constant CMD_SYNTAX. */
    private final static String CMD_SYNTAX = "newts-converter [options]";

    private final static Timestamp EPOCH = Timestamp.fromEpochMillis(0);
    private final static ValueType<?> ZERO = ValueType.compose(0, MetricType.GAUGE);

    private final Path onmsHome;
    private final Path rrdDir;
    private final Path rrdBinary;
    private final boolean storeByGroup;
    private final boolean rrdTool;

    private static class ForeignId {
        private final String foreignSource;
        private final String foreignId;

        public ForeignId(final String foreignSource,
                         final String foreignId) {
            this.foreignSource = foreignSource;
            this.foreignId = foreignId;
        }
    }

    /**
     * Mapping form node ID to foreign ID.
     **/
    private final Map<Integer, ForeignId> foreignIds = Maps.newHashMap();

    /** The Cassandra/Newts Repository. */
    private final SampleRepository repository;

    /** The Cassandra/Newts Indexer. */
    private final Indexer indexer;

    /** The processed resources. */
    private static AtomicLong processedMetrics = new AtomicLong(0);
    private static AtomicLong processedSamples = new AtomicLong(0);

    /** The Newts inject batch size. */
    private int batchSize;

    private final ExecutorService executor;

    /**
     * The main method.
     *
     * @param args the arguments
     */
    public static void main(final String... args) {
        final long start;
        try (final NewtsConverter converter = new NewtsConverter(args)) {
            start = System.currentTimeMillis();

            converter.execute();

        } catch (final NewtsConverterError e) {
            LOG.error(e.getMessage(), e);
            System.exit(1);
            throw null;
        }

        final Period period = new Interval(start, System.currentTimeMillis()).toPeriod();
        final PeriodFormatter formatter = new PeriodFormatterBuilder()
                .appendDays().appendSuffix(" days ")
                .appendHours().appendSuffix(" hours ")
                .appendMinutes().appendSuffix(" min ")
                .appendSeconds().appendSuffix(" sec ")
                .printZeroNever()
                .toFormatter();

        LOG.info("Conversion Finished: metrics: {}, samples: {}, time: {}", processedMetrics, processedSamples, formatter.print(period));
    }

    private NewtsConverter(final String... args) {
        final Options options = new Options();

        final Option helpOption = new Option("h", "help", false, "Print this help");
        options.addOption(helpOption);

        final Option opennmsHomeOption = new Option("o", "onms-home", true, "OpenNMS Home Directory (defaults to /opt/opennms)");
        options.addOption(opennmsHomeOption);

        final Option rrdPathOption = new Option("r", "rrd-dir", true, "The path to the RRD data (defaults to /var/opennms/rrd)");
        options.addOption(rrdPathOption);

        final Option rrdToolOption = new Option("t", "rrd-tool", true, "Whether to use rrdtool or JRobin (defaults to use rrdtool)");
        options.addOption(rrdToolOption);

        final Option rrdBinaryOption = new Option("T",
                                                  "rrd-binary",
                                                  true,
                                                  "The binary path to the rrdtool command (defaults to /usr/bin/rrdtool, only used if rrd-tool is set)");
        options.addOption(rrdBinaryOption);

        final Option storeByGroupOption = new Option("s", "store-by-group", true, "Whether store by group was enabled or not");
        storeByGroupOption.setRequired(true);
        options.addOption(storeByGroupOption);

        final Option threadsOption = new Option("n", "threads", true, "Number of conversion threads (defaults to number of CPUs)");
        options.addOption(threadsOption);

        final CommandLineParser parser = new PosixParser();

        final CommandLine cmd;
        try {
            cmd = parser.parse(options, args);

        } catch (ParseException e) {
            new HelpFormatter().printHelp(80, CMD_SYNTAX, String.format("ERROR: %s%n", e.getMessage()), options, null);
            System.exit(1);
            throw null;
        }

        // Processing Options
        if (cmd.hasOption('h')) {
            new HelpFormatter().printHelp(80, CMD_SYNTAX, null, options, null);
            System.exit(0);
        }

        this.onmsHome = cmd.hasOption('o')
                        ? Paths.get(cmd.getOptionValue('o'))
                        : Paths.get("/opt/opennms");
        if (!Files.exists(this.onmsHome) || !Files.isDirectory(this.onmsHome)) {
            new HelpFormatter().printHelp(80, CMD_SYNTAX, String.format("ERROR: Directory %s doesn't exist%n", this.onmsHome.toAbsolutePath()), options, null);
            System.exit(1);
            throw null;
        }
        System.setProperty("opennms.home", onmsHome.toAbsolutePath().toString());

        this.rrdDir = cmd.hasOption('r')
                      ? Paths.get(cmd.getOptionValue('r'))
                      : Paths.get("/var/opennms/rrd");
        if (!Files.exists(this.rrdDir) || !Files.isDirectory(this.rrdDir)) {
            new HelpFormatter().printHelp(80, CMD_SYNTAX, String.format("ERROR: Directory %s doesn't exist%n", this.rrdDir.toAbsolutePath()), options, null);
            System.exit(1);
            throw null;
        }

        try {
            storeByGroup = BooleanUtils.toBooleanObject(cmd.getOptionValue('s'));

        } catch (NullPointerException e) {
            new HelpFormatter().printHelp(80, CMD_SYNTAX, String.format("ERROR: Invalid value for storeByGroup%n"), options, null);
            System.exit(1);
            throw null;
        }

        try {
            rrdTool = cmd.hasOption('t')
                      ? BooleanUtils.toBooleanObject(cmd.getOptionValue('t'))
                      : true;

        } catch (NullPointerException e) {
            new HelpFormatter().printHelp(80, CMD_SYNTAX, String.format("ERROR: Invalid value for rrd-tool%n"), options, null);
            System.exit(1);
            throw null;
        }

        this.rrdBinary = cmd.hasOption('T')
                         ? Paths.get(cmd.getOptionValue('T'))
                         : Paths.get("/usr/bin/rrdtool");
        if (!Files.exists(this.rrdBinary) || !Files.isExecutable(this.rrdBinary)) {
            new HelpFormatter().printHelp(80,
                                          CMD_SYNTAX,
                                          String.format("ERROR: RRDtool command %s doesn't exist%n", this.rrdBinary.toAbsolutePath()),
                                          options,
                                          null);
            System.exit(1);
            throw null;
        }
        System.setProperty("rrd.binary", this.rrdBinary.toString());

        final int threads;
        try {
            threads = cmd.hasOption('n')
                      ? Integer.parseInt(cmd.getOptionValue('n'))
                      : Runtime.getRuntime().availableProcessors();
            this.executor = new ForkJoinPool(threads,
                                             ForkJoinPool.defaultForkJoinWorkerThreadFactory,
                                             null,
                                             true);

        } catch (Exception e) {
            new HelpFormatter().printHelp(80, CMD_SYNTAX, String.format("ERROR: Invalid number of threads: %s%n", e.getMessage()), options, null);
            System.exit(1);
            throw null;
        }

        // Initialize OpenNMS
        OnmsProperties.initialize();

        final String strategy = System.getProperty("org.opennms.timeseries.strategy", "rrd");
        final String host = System.getProperty("org.opennms.newts.config.hostname", "localhost");
        final String keyspace = System.getProperty("org.opennms.newts.config.keyspace", "newts");
        int ttl = Integer.parseInt(System.getProperty("org.opennms.newts.config.ttl", "31540000"));
        int port = Integer.parseInt(System.getProperty("org.opennms.newts.config.port", "9042"));

        batchSize = Integer.parseInt(System.getProperty("org.opennms.newts.config.max_batch_size", "16"));

        LOG.info("OpenNMS Home: {}", onmsHome);
        LOG.info("RRD Directory: {}", rrdDir);
        LOG.info("Use RRDtool Tool: {}", rrdTool);
        LOG.info("RRDtool CLI: {}", rrdBinary);
        LOG.info("StoreByGroup: {}", storeByGroup);
        LOG.info("Timeseries Strategy: {}", strategy);
        LOG.info("Conversion Threads: {}", threads);
        LOG.info("Cassandra Host: {}", host);
        LOG.info("Cassandra Port: {}", port);
        LOG.info("Cassandra Keyspace: {}", keyspace);
        LOG.info("Newts Max Batch Size: {}", batchSize);
        LOG.info("Newts TTL: {}", ttl);

        if (!"newts".equals(strategy)) {
            throw NewtsConverterError.create("The configured timeseries strategy must be 'newts' on opennms.properties (org.opennms.timeseries.strategy)");
        }

        try {
            final ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext(new String[]{
                    "classpath:/META-INF/opennms/applicationContext-soa.xml",
                    "classpath:/META-INF/opennms/applicationContext-newts.xml"
            });
            context.registerShutdownHook();

            this.repository = context.getBean(SampleRepository.class);
            this.indexer = context.getBean(Indexer.class);

        } catch (final Exception e) {
            throw NewtsConverterError.create(e, "Cannot connect to the Cassandra/Newts backend: {}", e.getMessage());
        }

        // Initialize node ID to foreign ID mapping
        try (final Connection conn = DataSourceFactory.getInstance().getConnection();
             final Statement st = conn.createStatement();
             final ResultSet rs = st.executeQuery("SELECT nodeid, foreignsource, foreignid from node n")) {
            while (rs.next()) {
                foreignIds.put(rs.getInt("nodeid"),
                               new ForeignId(rs.getString("foreignsource"),
                                             rs.getString("foreignid")));
            }

        } catch (final Exception e) {
            throw NewtsConverterError.create(e, "Failed to connect to database: {}", e.getMessage());
        }

        LOG.trace("Found {} nodes on the database", foreignIds.size());
    }

    public void execute() {
        LOG.trace("Starting Conversion...");

        this.processStoreByGroupResources(this.rrdDir.resolve("response"));

        this.processStringsProperties(this.rrdDir.resolve("snmp"));
        if (storeByGroup) {
            this.processStoreByGroupResources(this.rrdDir.resolve("snmp"));
        } else {
            this.processStoreByMetricResources(this.rrdDir.resolve("snmp"));
        }
    }

    private void processStoreByGroupResources(final Path path) {
        try {
            // Find and process all resource folders containing a 'ds.properties' file
            Files.walk(path)
                 .filter(p -> p.endsWith("ds.properties"))
                 .forEach(p -> processStoreByGroupResource(p.getParent()));

        } catch (Exception e) {
            throw NewtsConverterError.create(e, "Error while reading RRD files: {}", e.getMessage());
        }
    }

    private void processStoreByGroupResource(final Path path) {
        // Load the 'ds.properties' for the current path
        final Properties ds = new Properties();
        try (final BufferedReader r = Files.newBufferedReader(path.resolve("ds.properties"))) {
            ds.load(r);

        } catch (final IOException e) {
            throw NewtsConverterError.create("No group information found - please verify storeByGroup settings");
        }

        // Get all groups declared in the ds.properties and process the RRD files
        Sets.newHashSet(Iterables.transform(ds.values(), Object::toString))
            .forEach(group -> this.executor.execute(() -> processResource(path,
                                                                          group,
                                                                          group)));
    }

    private void processStoreByMetricResources(final Path path) {
        try {
            // Find an process all '.meta' files and the according RRD files
            Files.walk(path)
                 .filter(p -> p.getFileName().toString().endsWith(".meta"))
                 .forEach(p -> this.processStoreByMetricResource(p));

        } catch (Exception e) {
            throw NewtsConverterError.create(e, "Error while reading RRD files: {}", e.getMessage());
        }
    }

    private void processStoreByMetricResource(final Path metaPath) {
        // Use the containing directory as resource path
        final Path path = metaPath.getParent();

        // Extract the metric name from the file name
        final String metric = FilenameUtils.removeExtension(metaPath.getFileName().toString());

        // Load the '.meta' file to get the group name
        final Properties meta = new Properties();
        try (final BufferedReader r = Files.newBufferedReader(path.resolve(String.format("%s.meta", metric)))) {
            meta.load(r);

        } catch (final IOException e) {
            throw Throwables.propagate(e);
        }

        final String group = meta.getProperty("GROUP");
        if (group == null) {
            throw NewtsConverterError.create("No group information found - please verify storeByGroup settings");
        }

        // Process the resource
        this.executor.execute(() -> this.processResource(path,
                                                         metric,
                                                         group));
    }

    /**
     * Process metric.
     *
     * @param resourceDir the path where the resource file lives in
     * @param fileName the RRD file name without extension
     * @param group the group name
     */
    private void processResource(final Path resourceDir,
                                 final String fileName,
                                 final String group) {
        LOG.info("Processing resource: dir={}, file={}, group={}", resourceDir, fileName, group);

        final ResourcePath resourcePath = buildResourcePath(resourceDir);
        if (resourcePath == null) {
            return;
        }

        // Load and interpolate the RRD file
        final AbstractRRD rrd;
        try {
            if (this.rrdTool) {
                rrd = RrdConvertUtils.dumpRrd(resourceDir.resolve(fileName + ".rrd").toFile());

            } else {
                rrd = RrdConvertUtils.dumpJrb(resourceDir.resolve(fileName + ".jrb").toFile());
            }

        } catch (final Exception e) {
            throw NewtsConverterError.create(e, "Can't parse JRB/RRD {}.(rrd/jrb): {}", resourceDir.resolve(fileName), e.getMessage());
        }

        // Inject the samples from the RRD file to NewTS
        this.injectSamplesToNewts(resourcePath,
                                  group,
                                  rrd.getDataSources(),
                                  rrd.generateSamples());
    }

    private void injectSamplesToNewts(final ResourcePath resourcePath,
                                      final String group,
                                      final List<? extends AbstractDS> dataSources,
                                      final Collection<RrdSample> samples) {
        final ResourcePath groupPath = ResourcePath.get(resourcePath, group);

        // Create a resource ID from the resource path
        final String groupId = NewtsUtils.toResourceId(groupPath);

        // Build indexing attributes
        final Map<String, String> attributes = Maps.newHashMap();
        NewtsUtils.addIndicesToAttributes(groupPath, attributes);

        // Create the NewTS resource to insert
        final Resource resource = new Resource(groupId,
                                               Optional.of(attributes));

        // Transform the RRD samples into NewTS samples
        List<Sample> batch = new ArrayList<>(this.batchSize);
        for (final RrdSample s : samples) {
            for (int i = 0; i < dataSources.size(); i++) {
                final double value = s.getValue(i);
                if (Double.isNaN(value)) {
                    continue;
                }

                final AbstractDS ds = dataSources.get(i);

                final Timestamp timestamp = Timestamp.fromEpochMillis(s.getTimestamp());

                final String metric = ds.getName();

                final MetricType type = ds.isCounter()
                                        ? MetricType.COUNTER
                                        : MetricType.GAUGE;
                final ValueType<?> valueType = ValueType.compose(value, type);

                batch.add(new Sample(timestamp, resource, metric, type, valueType));

                if (batch.size() >= this.batchSize) {
                    this.repository.insert(batch, true);
                    this.processedSamples.getAndAdd(batch.size());

                    batch = new ArrayList<>(this.batchSize);
                }
            }
        }

        if (!batch.isEmpty()) {
            this.repository.insert(batch, true);
            this.processedSamples.getAndAdd(batch.size());
        }

        this.processedMetrics.getAndAdd(dataSources.size());

        LOG.trace("Stats: {} / {}", this.processedMetrics, this.processedSamples);
    }

    private void processStringsProperties(final Path path) {
        try {
            // Find an process all 'strings.properties' files
            Files.walk(path)
                 .filter(p -> p.endsWith("strings.properties"))
                 .forEach(p -> {
                     final Properties properties = new Properties();
                     try (final BufferedReader r = Files.newBufferedReader(p)) {
                         properties.load(r);

                     } catch (final IOException e) {
                         throw Throwables.propagate(e);
                     }

                     final ResourcePath resourcePath = buildResourcePath(p.getParent());
                     if (resourcePath == null) {
                         return;
                     }

                     this.injectStringPropertiesToNewts(resourcePath,
                                                        Maps.fromProperties(properties));
                 });

        } catch (Exception e) {
            throw NewtsConverterError.create(e, "Error while reading RRD files: {}", e.getMessage());
        }
    }

    private void injectStringPropertiesToNewts(final ResourcePath resourcePath,
                                               final Map<String, String> stringProperties) {
        final Resource resource = new Resource(NewtsUtils.toResourceId(resourcePath),
                                               Optional.of(stringProperties));

        final Sample sample = new Sample(EPOCH,
                                         resource,
                                         "strings",
                                         MetricType.GAUGE,
                                         ZERO);

        indexer.update(Lists.newArrayList(sample));
    }

    private ResourcePath buildResourcePath(final Path resourceDir) {
        final ResourcePath resourcePath;
        final Path relativeResourceDir = this.rrdDir.relativize(resourceDir);

        // Transform store-by-id path into store-by-foreign-source path
        if (relativeResourceDir.startsWith(Paths.get("snmp")) &&
            !relativeResourceDir.startsWith(Paths.get("snmp", "fs"))) {

            // The part after snmp/ is considered the node ID
            final int nodeId = Integer.valueOf(relativeResourceDir.getName(1).toString());

            // Get the foreign source for the node
            final ForeignId foreignId = foreignIds.get(nodeId);
            if (foreignId == null) {
                return null;
            }

            // Make a store-by-foreign-source compatible path by using the found foreign ID and append the remaining path as-is
            resourcePath = ResourcePath.get(ResourcePath.get(ResourcePath.get("snmp", "fs"),
                                                             foreignId.foreignSource,
                                                             foreignId.foreignId),
                                            Iterables.transform(relativeResourceDir.subpath(2, relativeResourceDir.getNameCount()),
                                                                Path::toString));

        } else {
            resourcePath = ResourcePath.get(relativeResourceDir);
        }
        return resourcePath;
    }

    @Override
    public void close() {
        this.executor.shutdown();

        while (!this.executor.isTerminated()) {
            try {
                this.executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
            }
        }
    }
}
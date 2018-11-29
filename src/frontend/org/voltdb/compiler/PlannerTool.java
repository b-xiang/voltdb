/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.compiler;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.hsqldb_voltpatches.HSQLInterface;
import org.hsqldb_voltpatches.HSQLInterface.HSQLParseException;
import org.voltcore.logging.VoltLogger;
import org.voltdb.ParameterSet;
import org.voltdb.PlannerStatsCollector;
import org.voltdb.PlannerStatsCollector.CacheUse;
import org.voltdb.StatsAgent;
import org.voltdb.StatsSelector;
import org.voltdb.VoltDB;
import org.voltdb.calciteadapter.rel.logical.VoltDBLRel;
import org.voltdb.catalog.Database;
import org.voltdb.common.Constants;
import org.voltdb.newplanner.CalcitePlanner;
import org.voltdb.newplanner.NonDdlBatch;
import org.voltdb.newplanner.SqlTask;
import org.voltdb.newplanner.VoltSqlToRelConverter;
import org.voltdb.newplanner.VoltSqlValidator;
import org.voltdb.newplanner.rules.PlannerPhase;
import org.voltdb.planner.BoundPlan;
import org.voltdb.planner.CompiledPlan;
import org.voltdb.planner.CorePlan;
import org.voltdb.planner.ParameterizationInfo;
import org.voltdb.planner.PlanningErrorException;
import org.voltdb.planner.QueryPlanner;
import org.voltdb.planner.StatementPartitioning;
import org.voltdb.planner.TrivialCostModel;
import org.voltdb.types.CalcitePlannerType;
import org.voltdb.utils.CompressionService;
import org.voltdb.utils.Encoder;

/**
 * Planner tool accepts an already compiled VoltDB catalog and then
 * interactively accept SQL and outputs plans on standard out.
 *
 * Used only for ad hoc queries.
 */
public class PlannerTool {
    private static final VoltLogger hostLog = new VoltLogger("HOST");
    private static final VoltLogger compileLog = new VoltLogger("COMPILE");

    private Database m_database;
    private byte[] m_catalogHash;
    private AdHocCompilerCache m_cache;
    private SchemaPlus m_schemaPlus;
    private long m_adHocLargeFallbackCount = 0;
    private long m_adHocLargeModeCount = 0;

    private final HSQLInterface m_hsql;

    private static PlannerStatsCollector m_plannerStats;

    // If -Dlarge_mode_ratio=xx is specified via ant, the value will show up in the environment variables and
    // take higher priority. Otherwise, the value specified via VOLTDB_OPTS will take effect.
    // If the test is started by ant and -Dlarge_mode_ratio is not set, it will take a default value "-1" which
    // we should ignore.
    private final double m_largeModeRatio = Double.valueOf((System.getenv("LARGE_MODE_RATIO") == null ||
            System.getenv("LARGE_MODE_RATIO").equals("-1")) ? System.getProperty("LARGE_MODE_RATIO", "0") : System.getenv("LARGE_MODE_RATIO"));

    public PlannerTool(final Database database, byte[] catalogHash)
    {
        assert(database != null);

        m_database = database;
        m_catalogHash = catalogHash;
        m_cache = AdHocCompilerCache.getCacheForCatalogHash(catalogHash);

        // LOAD HSQL
        m_hsql = HSQLInterface.loadHsqldb(ParameterizationInfo.getParamStateManager());
        String binDDL = m_database.getSchema();
        String ddl = CompressionService.decodeBase64AndDecompress(binDDL);
        String[] commands = ddl.split("\n");
        for (String command : commands) {
            String decoded_cmd = Encoder.hexDecodeToString(command);
            decoded_cmd = decoded_cmd.trim();
            if (decoded_cmd.length() == 0)
                continue;
            try {
                m_hsql.runDDLCommand(decoded_cmd);
            }
            catch (HSQLParseException e) {
                // need a good error message here
                throw new RuntimeException("Error creating hsql: " + e.getMessage() + " in DDL statement: " + decoded_cmd);
            }
        }
        hostLog.debug("hsql loaded");

        // Create and register a singleton planner stats collector, if this is the first time.
        if (m_plannerStats == null) {
            synchronized (this.getClass()) {
                if (m_plannerStats == null) {
                    final StatsAgent statsAgent = VoltDB.instance().getStatsAgent();
                    // In mock test environments there may be no stats agent.
                    if (statsAgent != null) {
                        m_plannerStats = new PlannerStatsCollector(-1);
                        statsAgent.registerStatsSource(StatsSelector.PLANNER, -1, m_plannerStats);
                    }
                }
            }
        }
    }

    public PlannerTool(final Database database, byte[] catalogHash, SchemaPlus schemaPlus) {
        this(database, catalogHash);
        m_schemaPlus = schemaPlus;
    }

    public PlannerTool updateWhenNoSchemaChange(Database database, byte[] catalogHash, SchemaPlus schemaPlus) {
        m_database = database;
        m_catalogHash = catalogHash;
        m_cache = AdHocCompilerCache.getCacheForCatalogHash(catalogHash);
        m_schemaPlus = schemaPlus;

        return this;
    }

    public HSQLInterface getHSQLInterface() {
        return m_hsql;
    }

    public long getAdHocLargeFallbackCount() {
        return m_adHocLargeFallbackCount;
    }

    public long getAdHocLargeModeCount() {
        return m_adHocLargeModeCount;
    }

    public AdHocPlannedStatement planSqlForTest(String sqlIn) {
        StatementPartitioning infer = StatementPartitioning.inferPartitioning();
        return planSql(sqlIn, infer, false, null, false, false);
    }

    private void logException(Exception e, String fmtLabel) {
        compileLog.error(fmtLabel + ": ", e);
    }

    /**
     * Stripped down compile that is ONLY used to plan default procedures.
     */
    public synchronized CompiledPlan planSqlCore(String sql, StatementPartitioning partitioning) {
        TrivialCostModel costModel = new TrivialCostModel();
        DatabaseEstimates estimates = new DatabaseEstimates();

        CompiledPlan plan = null;
        // This try-with-resources block acquires a global lock on all planning
        // This is required until we figure out how to do parallel planning.
        try (QueryPlanner planner = new QueryPlanner(
                sql, "PlannerTool", "PlannerToolProc", m_database,
                partitioning, m_hsql, estimates, !VoltCompiler.DEBUG_MODE,
                costModel, null, null, DeterminismMode.FASTER, false)) {

            // do the expensive full planning.
            planner.parse();
            plan = planner.plan();
            assert(plan != null);
        }
        catch (Exception e) {
            /*
             * Don't log PlanningErrorExceptions or HSQLParseExceptions, as they
             * are at least somewhat expected.
             */
            String loggedMsg = "";
            if (!(e instanceof PlanningErrorException || e instanceof HSQLParseException)) {
                logException(e, "Error compiling query");
                loggedMsg = " (Stack trace has been written to the log.)";
            }
            if (e.getMessage() != null) {
                throw new RuntimeException("SQL error while compiling query: " + e.getMessage() + loggedMsg, e);
            }
            throw new RuntimeException("SQL error while compiling query: " + e.toString() + loggedMsg, e);
        }

        if (plan == null) {
            throw new RuntimeException("Null plan received in PlannerTool.planSql");
        }

        return plan;
    }

    /**
     * Plan a query with the Calcite planner.
     * @param task the query to plan.
     * @param batch the query batch which this query belongs to.
     * @return a planned statement. Untill DQL is fully supported, it returns null, and the caller
     * will use fall-back behavior.
     */
    public synchronized AdHocPlannedStatement planSqlCalcite(SqlTask task, NonDdlBatch batch) {
        // create VoltSqlValidator from SchemaPlus.
        VoltSqlValidator validator = new VoltSqlValidator(m_schemaPlus);
        // validate the task's SqlNode.
        SqlNode validatedNode = validator.validate(task.getParsedQuery());
        // convert SqlNode to RelNode.
        VoltSqlToRelConverter converter = VoltSqlToRelConverter.create(validator, m_schemaPlus);
        RelRoot root = converter.convertQuery(validatedNode, false, true);
        root = root.withRel(converter.decorrelate(validatedNode, root.rel));
        // apply calcite and Volt logical rules
        RelTraitSet logicalTraits = root.rel.getTraitSet().replace(VoltDBLRel.VOLTDB_LOGICAL);
        RelNode nodeAfterLogical = CalcitePlanner.transform(CalcitePlannerType.VOLCANO, PlannerPhase.LOGICAL,
                root.rel, logicalTraits);

        // TODO: finish Calcite planning and convert into AdHocPlannedStatement.
        return null;
    }

    static final class SqlPlanner {
        private final Database m_database;
        private final HSQLInterface m_hsql;
        private final String m_sql;
        private final boolean m_isLargeQuery, m_isSwapTables, m_isExplainMode;
        private final Object[] m_userParams;
        private final AdHocCompilerCache m_cache;
        // outcome
        private final CompiledPlan m_plan;
        private AdHocPlannedStatement m_adhocPlan = null;
        private boolean m_hasQuestionMark;
        private boolean m_wrongNumberParameters = false;
        private boolean m_hasExceptionWhenParameterized;
        private StatementPartitioning m_partitioning;
        private long m_adHocLargeFallbackCount;
        private String m_parsedToken;

        public SqlPlanner(
                Database database, StatementPartitioning partitioning, HSQLInterface hsql, String sql,
                boolean isLargeQuery, boolean isSwapTables, boolean isExplainMode, long adHocLargeFallbackCount,
                Object[] userParams, AdHocCompilerCache cache) {
            m_database = database;
            m_partitioning = partitioning;
            m_hsql = hsql;
            m_sql = sql;
            m_isLargeQuery = isLargeQuery;
            m_isSwapTables = isSwapTables;
            m_isExplainMode = isExplainMode;
            m_adHocLargeFallbackCount = adHocLargeFallbackCount;
            m_userParams = userParams;
            m_cache = cache;
            m_plan = plan();
        }

        public CompiledPlan getCompiledPlan() {
            return m_plan;
        }

        public AdHocPlannedStatement getAdhocPlan() {
            return m_adhocPlan;
        }

        public boolean hasQuestionMark() {
            return m_hasQuestionMark;
        }

        public boolean hasWrongNumberParameters() {
            return m_wrongNumberParameters;
        }

        public boolean hasExceptionWhenParameterized() {
            return m_hasExceptionWhenParameterized;
        }

        public long getAdHocLargeFallBackCount() {
            return m_adHocLargeFallbackCount;
        }

        public String getParsedToken() {
            return m_parsedToken;
        }

        public StatementPartitioning getPartitioning() {
            return m_partitioning;
        }

        private CompiledPlan plan() {
            CompiledPlan plan;
            try (QueryPlanner planner = new QueryPlanner(
                    m_sql, "PlannerTool", "PlannerToolProc", m_database,
                    m_partitioning, m_hsql, new DatabaseEstimates(), !VoltCompiler.DEBUG_MODE, new TrivialCostModel(),
                    null, null, DeterminismMode.FASTER, m_isLargeQuery)) {
                if (m_isSwapTables) {
                    planner.planSwapTables();
                } else {
                    planner.parse();
                }
                m_parsedToken = planner.parameterize();

                // check the parameters count
                // check user input question marks with input parameters
                int inputParamsLength = m_userParams == null ? 0 : m_userParams.length;
                if (planner.getAdhocUserParamsCount() > CompiledPlan.MAX_PARAM_COUNT) {
                    throw new PlanningErrorException(
                            "The statement's parameter count " + planner.getAdhocUserParamsCount() +
                                    " must not exceed the maximum " + CompiledPlan.MAX_PARAM_COUNT);
                } else if (planner.getAdhocUserParamsCount() != inputParamsLength) {
                    m_wrongNumberParameters = true;
                    if (! m_isExplainMode) {
                        throw new PlanningErrorException(String.format(
                                "Incorrect number of parameters passed: expected %d, passed %d",
                                planner.getAdhocUserParamsCount(), inputParamsLength));
                    }
                }
                m_hasQuestionMark = planner.getAdhocUserParamsCount() > 0;

                // do not put wrong parameter explain query into cache
                if (!m_wrongNumberParameters && m_partitioning.isInferred() && ! m_isLargeQuery) {
                    // if cache-able, check the cache for a matching pre-parameterized plan
                    // if plan found, build the full plan using the parameter data in the
                    // QueryPlanner.
                    assert(m_parsedToken != null);
                    String[] extractedLiterals = planner.extractedParamLiteralValues();
                    List<BoundPlan> boundVariants = m_cache.getWithParsedToken(m_parsedToken);
                    if (boundVariants != null) {
                        assert( ! boundVariants.isEmpty());
                        BoundPlan matched = null;
                        for (BoundPlan boundPlan : boundVariants) {
                            if (boundPlan.allowsParams(extractedLiterals)) {
                                matched = boundPlan;
                                break;
                            }
                        }
                        if (matched != null) {
                            final CorePlan core = matched.m_core;
                            final ParameterSet params;
                            if (planner.compiledAsParameterizedPlan()) {
                                params = planner.extractedParamValues(core.parameterTypes);
                            } else if (m_hasQuestionMark) {
                                params = ParameterSet.fromArrayNoCopy(m_userParams);
                            } else {
                                // No constants AdHoc queries
                                params = ParameterSet.emptyParameterSet();
                            }

                            m_adhocPlan = new AdHocPlannedStatement(m_sql.getBytes(Constants.UTF8ENCODING),
                                    core,
                                    params,
                                    null);
                            m_adhocPlan.setBoundConstants(matched.m_constants);
                            // parameterized plan from the cache does not have exception
                            m_cache.put(m_sql, m_parsedToken, m_adhocPlan, extractedLiterals, m_hasQuestionMark, false);
                        }
                    }
                }

                // If not caching or there was no cache hit, do the expensive full planning.
                plan = planner.plan();
                if (plan.getStatementPartitioning() != null) {
                    m_partitioning = plan.getStatementPartitioning();
                }
                if (plan.getIsLargeQuery() != m_isLargeQuery) {
                    ++m_adHocLargeFallbackCount;
                }
                m_hasExceptionWhenParameterized = planner.wasBadPameterized();
                return plan;
            } catch (Exception e) {
                /*
                 * Don't log PlanningErrorExceptions or HSQLParseExceptions, as
                 * they are at least somewhat expected.
                 */
                String loggedMsg = "";
                if (! (e instanceof PlanningErrorException)) {
                    compileLog.error("Error compiling query: ", e);
                    loggedMsg = " (Stack trace has been written to the log.)";
                }
                if (e.getMessage() != null) {
                    throw new RuntimeException("SQL error while compiling query: " + e.getMessage() + loggedMsg, e);
                } else {
                    throw new RuntimeException("SQL error while compiling query: " + e.toString() + loggedMsg, e);
                }
            }
        }
    }

    public synchronized AdHocPlannedStatement planSql(String sql, StatementPartitioning partitioning,
            boolean isExplainMode, final Object[] userParams, boolean isSwapTables, boolean isLargeQuery) {
        // large_mode_ratio will force execution of SQL queries to use the "large" path (for read-only queries)
        // a certain percentage of the time
        if (m_largeModeRatio > 0 && !isLargeQuery) {
            if (m_largeModeRatio >= 1 || m_largeModeRatio > ThreadLocalRandom.current().nextDouble()) {
                isLargeQuery = true;
                m_adHocLargeModeCount++;
            }
        }
        CacheUse cacheUse = CacheUse.FAIL;
        if (m_plannerStats != null) {
            m_plannerStats.startStatsCollection();
        }
        try {
            if ((sql == null) || (sql = sql.trim()).isEmpty()) {    // remove any spaces or newlines
                throw new RuntimeException("Can't plan empty or null SQL.");
            }

            // No caching for forced single partition or forced multi partition SQL,
            // since these options potentially get different plans that may be invalid
            // or sub-optimal in other contexts. Likewise, plans cached from other contexts
            // may be incompatible with these options.
            // If this presents a planning performance problem, we could consider maintaining
            // separate caches for the 3 cases or maintaining up to 3 plans per cache entry
            // if the cases tended to have mostly overlapping queries.
            //
            // Large queries are not cached.  Their plans are different than non-large queries
            // with the same SQL text, and in general we expect them to be slow.  If at some
            // point it seems worthwhile to cache such plans, we can explore it.
            if (partitioning.isInferred() && !isLargeQuery) {
                // Check the literal cache for a match.
                AdHocPlannedStatement cachedPlan = m_cache.getWithSQL(sql);
                if (cachedPlan != null) {
                    cacheUse = CacheUse.HIT1;
                    return cachedPlan;
                }
                else {
                    cacheUse = CacheUse.MISS;
                }
            }

            //////////////////////
            // PLAN THE STMT
            //////////////////////

            String[] extractedLiterals = null;
            // This try-with-resources block acquires a global lock on all planning
            // This is required until we figure out how to do parallel planning.

            final SqlPlanner planner = new SqlPlanner(m_database, partitioning, m_hsql, sql,
                    isLargeQuery, isSwapTables, isExplainMode, m_adHocLargeFallbackCount, userParams, m_cache);
            if (planner.getAdhocPlan() != null) {
                return planner.getAdhocPlan();
            }
            partitioning = planner.getPartitioning();
            final boolean planHasExceptionsWhenParameterized = planner.hasExceptionWhenParameterized();
            final String parsedToken = planner.getParsedToken();
            final CompiledPlan plan = planner.getCompiledPlan();
            final boolean hasUserQuestionMark = planner.hasQuestionMark();
            final boolean wrongNumberParameters = planner.hasWrongNumberParameters();
            m_adHocLargeFallbackCount = planner.getAdHocLargeFallBackCount();
            //////////////////////
            // OUTPUT THE RESULT
            //////////////////////
            CorePlan core = new CorePlan(plan, m_catalogHash);
            AdHocPlannedStatement ahps = new AdHocPlannedStatement(plan, core);

            // Do not put wrong parameter explain query into cache.
            // Also, do not put large query plans into the cache.
            if (!wrongNumberParameters && partitioning.isInferred() && !isLargeQuery) {

                // Note either the parameter index (per force to a user-provided parameter) or
                // the actual constant value of the partitioning key inferred from the plan.
                // Either or both of these two values may simply default
                // to -1 and to null, respectively.
                core.setPartitioningParamIndex(partitioning.getInferredParameterIndex());
                core.setPartitioningParamValue(partitioning.getInferredPartitioningValue());
                assert(parsedToken != null);
                // Again, plans with inferred partitioning are the only ones supported in the cache.
                m_cache.put(sql, parsedToken, ahps, extractedLiterals, hasUserQuestionMark, planHasExceptionsWhenParameterized);
            }
            return ahps;
        }
        finally {
            if (m_plannerStats != null) {
                m_plannerStats.endStatsCollection(m_cache.getLiteralCacheSize(), m_cache.getCoreCacheSize(), cacheUse, -1);
            }
        }
    }
}

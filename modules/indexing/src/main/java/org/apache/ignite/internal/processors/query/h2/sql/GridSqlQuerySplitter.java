/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.h2.sql;

import org.apache.ignite.*;
import org.apache.ignite.internal.processors.cache.query.*;
import org.apache.ignite.internal.util.typedef.*;
import org.h2.jdbc.*;
import org.h2.value.*;
import org.jetbrains.annotations.*;

import java.util.*;

import static org.apache.ignite.internal.processors.query.h2.sql.GridSqlFunctionType.*;

/**
 * Splits a single SQL query into two step map-reduce query.
 */
public class GridSqlQuerySplitter {
    /** */
    private static final String TABLE_PREFIX = "__T";

    /** */
    private static final String COLUMN_PREFIX = "__C";

    /** */
    public static final String TABLE_FUNC_NAME = "__Z0";

    /**
     * @param idx Index of table.
     * @return Table name.
     */
    private static String table(int idx) {
        return TABLE_PREFIX + idx;
    }

    /**
     * @param idx Index of column.
     * @return Generated by index column alias.
     */
    private static String columnName(int idx) {
        return COLUMN_PREFIX + idx;
    }

    /**
     * @param qry Query.
     * @return Leftest simple query if this is UNION.
     */
    private static GridSqlSelect leftest(GridSqlQuery qry) {
        if (qry instanceof GridSqlUnion)
            return leftest(((GridSqlUnion)qry).left());

        return (GridSqlSelect)qry;
    }

    /**
     * @param stmt Prepared statement.
     * @param params Parameters.
     * @param collocated Collocated query.
     * @return Two step query.
     */
    public static GridCacheTwoStepQuery split(JdbcPreparedStatement stmt, Object[] params, boolean collocated) {
        if (params == null)
            params = GridCacheSqlQuery.EMPTY_PARAMS;

        final GridSqlQuery qry0 = GridSqlQueryParser.parse(stmt);

        GridSqlSelect srcQry;

        if (qry0 instanceof GridSqlSelect)
            srcQry = (GridSqlSelect)qry0;
        else { // Handle UNION.
            srcQry = new GridSqlSelect().from(new GridSqlSubquery(qry0));

            srcQry.explain(qry0.explain());

            GridSqlSelect left = leftest(qry0);

            int c = 0;

            for (GridSqlElement expr : left.select(true)) {
                String colName;

                if (expr instanceof GridSqlAlias)
                    colName = ((GridSqlAlias)expr).alias();
                else if (expr instanceof GridSqlColumn)
                    colName = ((GridSqlColumn)expr).columnName();
                else {
                    colName = columnName(c);

                    expr = alias(colName, expr);

                    // Set generated alias to the expression.
                    left.setSelectExpression(c, expr);
                }

                GridSqlColumn col = column(colName);

                srcQry.addSelectExpression(col, true);

                qry0.sort();

                c++;
            }

            // ORDER BY
            if (!qry0.sort().isEmpty()) {
                for (GridSqlSortColumn col : qry0.sort())
                    srcQry.addSort(col);
            }
        }

        final String mergeTable = TABLE_FUNC_NAME + "()"; // table(0); TODO

        // Create map and reduce queries.
        GridSqlSelect mapQry = srcQry.clone();

        mapQry.explain(false);

        GridSqlSelect rdcQry = new GridSqlSelect().from(new GridSqlFunction(null, TABLE_FUNC_NAME)); // table(mergeTable)); TODO

        // Split all select expressions into map-reduce parts.
        List<GridSqlElement> mapExps = F.addAll(
            new ArrayList<GridSqlElement>(srcQry.allColumns()),
            srcQry.select(false));

        GridSqlElement[] rdcExps = new GridSqlElement[srcQry.visibleColumns()];

        Set<String> colNames = new HashSet<>();

        boolean aggregateFound = false;

        for (int i = 0, len = mapExps.size(); i < len; i++) // Remember len because mapExps list can grow.
            aggregateFound |= splitSelectExpression(mapExps, rdcExps, colNames, i, collocated);

        // Fill select expressions.
        mapQry.clearSelect();

        for (GridSqlElement exp : mapExps) // Add all map expressions as visible.
            mapQry.addSelectExpression(exp, true);

        for (GridSqlElement rdcExp : rdcExps) // Add corresponding visible reduce columns.
            rdcQry.addSelectExpression(rdcExp, true);

        for (int i = rdcExps.length; i < mapExps.size(); i++)  // Add all extra map columns as invisible reduce columns.
            rdcQry.addSelectExpression(column(((GridSqlAlias)mapExps.get(i)).alias()), false);

        // -- GROUP BY
        if (srcQry.hasGroupBy()) {
            mapQry.clearGroups();

            for (int col : srcQry.groupColumns())
                mapQry.addGroupExpression(column(((GridSqlAlias)mapExps.get(col)).alias()));

            if (!collocated) {
                for (int col : srcQry.groupColumns())
                    rdcQry.addGroupExpression(column(((GridSqlAlias)mapExps.get(col)).alias()));
            }
        }

        // -- HAVING
        if (srcQry.having() != null && !collocated) {
            // TODO Find aggregate functions in HAVING clause.
            rdcQry.whereAnd(column(columnName(srcQry.havingColumn())));

            mapQry.having(null);
        }

        // -- ORDER BY
        if (!srcQry.sort().isEmpty()) {
            if (aggregateFound) // Ordering over aggregates does not make sense.
                mapQry.clearSort(); // Otherwise map sort will be used by offset-limit.

            for (GridSqlSortColumn sortCol : srcQry.sort())
                rdcQry.addSort(sortCol);
        }

        // -- LIMIT
        if (srcQry.limit() != null) {
            if (aggregateFound)
                mapQry.limit(null);

            rdcQry.limit(srcQry.limit());
        }

        // -- OFFSET
        if (srcQry.offset() != null) {
            mapQry.offset(null);

            rdcQry.offset(srcQry.offset());
        }

        // -- DISTINCT
        if (srcQry.distinct()) {
            mapQry.distinct(false);
            rdcQry.distinct(true);
        }

        // Build resulting two step query.
        GridCacheTwoStepQuery res = new GridCacheTwoStepQuery(rdcQry.getSQL(),
            findParams(rdcQry, params, new ArrayList<>()).toArray());

        res.addMapQuery(mergeTable, mapQry.getSQL(),
            findParams(mapQry, params, new ArrayList<>(params.length)).toArray());

        res.explain(qry0.explain());

        return res;
    }

    /**
     * @param qry Select.
     * @param params Parameters.
     * @param target Extracted parameters.
     * @return Extracted parameters list.
     */
    private static List<Object> findParams(GridSqlQuery qry, Object[] params, ArrayList<Object> target) {
        if (qry instanceof GridSqlSelect)
            return findParams((GridSqlSelect)qry, params, target);

        GridSqlUnion union = (GridSqlUnion)qry;

        findParams(union.left(), params, target);
        findParams(union.right(), params, target);

        findParams(qry.limit(), params, target);
        findParams(qry.offset(), params, target);

        return target;
    }

    /**
     * @param qry Select.
     * @param params Parameters.
     * @param target Extracted parameters.
     * @return Extracted parameters list.
     */
    private static List<Object> findParams(GridSqlSelect qry, Object[] params, ArrayList<Object> target) {
        if (params.length == 0)
            return target;

        for (GridSqlElement el : qry.select(false))
            findParams(el, params, target);

        findParams(qry.from(), params, target);
        findParams(qry.where(), params, target);

        for (GridSqlElement el : qry.groups())
            findParams(el, params, target);

        findParams(qry.having(), params, target);

        findParams(qry.limit(), params, target);
        findParams(qry.offset(), params, target);

        return target;
    }

    /**
     * @param el Element.
     * @param params Parameters.
     * @param target Extracted parameters.
     */
    private static void findParams(@Nullable GridSqlElement el, Object[] params, ArrayList<Object> target) {
        if (el == null)
            return;

        if (el instanceof GridSqlParameter) {
            // H2 Supports queries like "select ?5" but first 4 non-existing parameters are need to be set to any value.
            // Here we will set them to NULL.
            int idx = ((GridSqlParameter)el).index();

            while (target.size() < idx)
                target.add(null);

            if (params.length <= idx)
                throw new IgniteException("Invalid number of query parameters. " +
                    "Cannot find " + idx + " parameter.");

            Object param = params[idx];

            if (idx == target.size())
                target.add(param);
            else
                target.set(idx, param);
        }
        else if (el instanceof GridSqlSubquery)
            findParams(((GridSqlSubquery)el).select(), params, target);
        else
            for (GridSqlElement child : el)
                findParams(child, params, target);
    }

    /**
     * @param mapSelect Selects for map query.
     * @param rdcSelect Selects for reduce query.
     * @param colNames Set of unique top level column names.
     * @param idx Index.
     * @param collocated If it is a collocated query.
     * @return {@code true} If aggregate was found.
     */
    private static boolean splitSelectExpression(List<GridSqlElement> mapSelect, GridSqlElement[] rdcSelect,
        Set<String> colNames, int idx, boolean collocated) {
        GridSqlElement el = mapSelect.get(idx);

        GridSqlAlias alias = null;

        boolean aggregateFound = false;

        if (el instanceof GridSqlAlias) { // Unwrap from alias.
            alias = (GridSqlAlias)el;
            el = alias.child();
        }

        if (!collocated && el instanceof GridSqlAggregateFunction) {
            aggregateFound = true;

            GridSqlAggregateFunction agg = (GridSqlAggregateFunction)el;

            GridSqlElement mapAgg, rdcAgg;

            String mapAggAlias = columnName(idx);

            switch (agg.type()) {
                case AVG: // SUM( AVG(CAST(x AS DOUBLE))*COUNT(x) )/SUM( COUNT(x) ).
                    //-- COUNT(x) map
                    GridSqlElement cntMapAgg = aggregate(agg.distinct(), COUNT).addChild(agg.child());

                    // Add generated alias to COUNT(x).
                    // Using size as index since COUNT will be added as the last select element to the map query.
                    String cntMapAggAlias = columnName(mapSelect.size());

                    cntMapAgg = alias(cntMapAggAlias, cntMapAgg);

                    mapSelect.add(cntMapAgg);

                    //-- AVG(CAST(x AS DOUBLE)) map
                    mapAgg = aggregate(agg.distinct(), AVG).addChild( // Add function argument.
                        function(CAST).setCastType("DOUBLE").addChild(agg.child()));

                    //-- SUM( AVG(x)*COUNT(x) )/SUM( COUNT(x) ) reduce
                    GridSqlElement sumUpRdc = aggregate(false, SUM).addChild(
                        op(GridSqlOperationType.MULTIPLY,
                                column(mapAggAlias),
                                column(cntMapAggAlias)));

                    GridSqlElement sumDownRdc = aggregate(false, SUM).addChild(column(cntMapAggAlias));

                    rdcAgg = op(GridSqlOperationType.DIVIDE, sumUpRdc, sumDownRdc);

                    break;

                case SUM: // SUM( SUM(x) )
                case MAX: // MAX( MAX(x) )
                case MIN: // MIN( MIN(x) )
                    mapAgg = aggregate(agg.distinct(), agg.type()).addChild(agg.child());

                    rdcAgg = aggregate(agg.distinct(), agg.type()).addChild(column(mapAggAlias));

                    break;

                case COUNT_ALL: // CAST(SUM( COUNT(*) ) AS BIGINT)
                case COUNT: // CAST(SUM( COUNT(x) ) AS BIGINT)
                    mapAgg = aggregate(agg.distinct(), agg.type());

                    if (agg.type() == COUNT)
                        mapAgg.addChild(agg.child());

                    rdcAgg = aggregate(false, SUM).addChild(column(mapAggAlias));

                    rdcAgg = function(CAST).setCastType("BIGINT").addChild(rdcAgg);

                    break;

                default:
                    throw new IgniteException("Unsupported aggregate: " + agg.type());
            }

            assert !(mapAgg instanceof GridSqlAlias);

            // Add generated alias to map aggregate.
            mapAgg = alias(mapAggAlias, mapAgg);

            if (alias != null) // Add initial alias if it was set.
                rdcAgg = alias(alias.alias(), rdcAgg);

            // Set map and reduce aggregates to their places in selects.
            mapSelect.set(idx, mapAgg);

            rdcSelect[idx] = rdcAgg;
        }
        else {
            String mapColAlias = columnName(idx);
            String rdcColAlias;

            if (alias == null)  // Original column name for reduce column.
                rdcColAlias = el instanceof GridSqlColumn ? ((GridSqlColumn)el).columnName() : mapColAlias;
            else // Set initial alias for reduce column.
                rdcColAlias = alias.alias();

            // Always wrap map column into generated alias.
            mapSelect.set(idx, alias(mapColAlias, el)); // `el` is known not to be an alias.

            if (idx < rdcSelect.length) { // SELECT __C0 AS orginal_alias
                GridSqlElement rdcEl = column(mapColAlias);

                GridSqlType type = el.expressionResultType();

                if (type != null && type.type() == Value.UUID) // There is no JDBC type UUID, so conversion to bytes occurs.
                    rdcEl = function(CAST).setCastType("UUID").addChild(rdcEl);

                if (colNames.add(rdcColAlias)) // To handle column name duplication (usually wildcard for few tables).
                    rdcEl = alias(rdcColAlias, rdcEl);

                rdcSelect[idx] = rdcEl;
            }
        }

        return aggregateFound;
    }

    /**
     * @param distinct Distinct.
     * @param type Type.
     * @return Aggregate function.
     */
    private static GridSqlAggregateFunction aggregate(boolean distinct, GridSqlFunctionType type) {
        return new GridSqlAggregateFunction(distinct, type);
    }

    /**
     * @param name Column name.
     * @return Column.
     */
    private static GridSqlColumn column(String name) {
        return new GridSqlColumn(null, name, name);
    }

    /**
     * @param alias Alias.
     * @param child Child.
     * @return Alias.
     */
    private static GridSqlAlias alias(String alias, GridSqlElement child) {
        return new GridSqlAlias(alias, child);
    }

    /**
     * @param type Type.
     * @param left Left expression.
     * @param right Right expression.
     * @return Binary operator.
     */
    private static GridSqlOperation op(GridSqlOperationType type, GridSqlElement left, GridSqlElement right) {
        return new GridSqlOperation(type, left, right);
    }

    /**
     * @param type Type.
     * @return Function.
     */
    private static GridSqlFunction function(GridSqlFunctionType type) {
        return new GridSqlFunction(type);
    }

    /**
     * @param name Table name.
     * @return Table.
     */
    private static GridSqlTable table(String name) {
        return new GridSqlTable(null, name);
    }
}

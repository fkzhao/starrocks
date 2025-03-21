// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.starrocks.sql.optimizer.rule.transformation.materialization.common;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.Expr;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.common.Pair;
import com.starrocks.sql.optimizer.MvRewriteContext;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.operator.Projection;
import com.starrocks.sql.optimizer.operator.logical.LogicalAggregationOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ReplaceColumnRefRewriter;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriter;
import com.starrocks.sql.optimizer.rewrite.scalar.ImplicitCastRule;
import com.starrocks.sql.optimizer.rule.Rule;
import com.starrocks.sql.optimizer.rule.transformation.materialization.AggregatedMaterializedViewRewriter;
import com.starrocks.sql.optimizer.rule.transformation.materialization.MvUtils;
import com.starrocks.sql.optimizer.rule.transformation.materialization.PredicateSplit;
import com.starrocks.sql.optimizer.rule.tree.pdagg.AggregatePushDownContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.starrocks.sql.optimizer.OptimizerTraceUtil.logMVRewrite;
import static com.starrocks.sql.optimizer.rule.transformation.materialization.MvUtils.deriveLogicalProperty;
import static com.starrocks.sql.optimizer.rule.transformation.materialization.MvUtils.getQuerySplitPredicate;
import static com.starrocks.sql.optimizer.rule.transformation.materialization.common.AggregateFunctionRollupUtils.genRollupProject;
import static com.starrocks.sql.optimizer.rule.transformation.materialization.common.AggregateFunctionRollupUtils.getRollupFunctionName;

/**
 * AggregatePushDownUtils is used to rewrite query plan which is used in aggregations pushed down by materialized view
 */
public class AggregatePushDownUtils {
    /**
     * Rewrite query plan which has been pushed down by materialized view
     * @param optExpression: push down query plan
     * @return: rewritten query plan if rewrite success, otherwise return null
     */
    public static OptExpression doRewritePushDownAgg(MvRewriteContext mvRewriteContext,
                                                     AggregatePushDownContext ctx,
                                                     OptExpression optExpression,
                                                     Rule rule) {
        final ColumnRefFactory queryColumnRefFactory = mvRewriteContext.getMaterializationContext().getQueryRefFactory();
        final List<Table> queryTables = MvUtils.getAllTables(optExpression);
        final ReplaceColumnRefRewriter queryColumnRefRewriter =
                MvUtils.getReplaceColumnRefWriter(optExpression, queryColumnRefFactory);
        final OptimizerContext optimizerContext = mvRewriteContext.getMaterializationContext().getOptimizerContext();
        PredicateSplit queryPredicateSplit = getQuerySplitPredicate(optimizerContext,
                mvRewriteContext.getMaterializationContext(), optExpression, queryColumnRefFactory,
                queryColumnRefRewriter, rule);
        if (queryPredicateSplit == null) {
            logMVRewrite(mvRewriteContext, "Rewrite push down agg failed: get query split predicate failed");
            return null;
        }
        logMVRewrite(mvRewriteContext, "Push down agg query split predicate: {}", queryPredicateSplit);
        MvRewriteContext newMvRewriteContext = new MvRewriteContext(mvRewriteContext.getMaterializationContext(),
                queryTables, optExpression, queryColumnRefRewriter, queryPredicateSplit, Lists.newArrayList(), rule);
        // set aggregate push down context to be used in the final stage
        newMvRewriteContext.setAggregatePushDownContext(ctx);
        AggregatedMaterializedViewRewriter rewriter = new AggregatedMaterializedViewRewriter(newMvRewriteContext);
        OptExpression result = rewriter.doRewrite(mvRewriteContext);
        if (result == null) {
            logMVRewrite(mvRewriteContext, "doRewrite phase failed in AggregatedMaterializedViewRewriter");
            return null;
        }
        deriveLogicalProperty(result);
        return result;
    }

    public static OptExpression getPushDownRollupFinalAggregateOpt(MvRewriteContext mvRewriteContext,
                                                                   AggregatePushDownContext ctx,
                                                                   Map<ColumnRefOperator, ColumnRefOperator> remapping,
                                                                   OptExpression inputExpression,
                                                                   List<OptExpression> newChildren) {
        final Map<ColumnRefOperator, CallOperator> newAggregations = Maps.newHashMap();
        final Map<ColumnRefOperator, ScalarOperator> aggProjection = Maps.newHashMap();
        final Map<ColumnRefOperator, ScalarOperator> aggColRefToAggMap = Maps.newHashMap();
        final LogicalAggregationOperator origAggregate = inputExpression.getOp().cast();

        // TODO: use aggregate push down context to generate related push-down aggregation functions
        final Map<ColumnRefOperator, CallOperator> aggregations = origAggregate.getAggregations();
        for (Map.Entry<ColumnRefOperator, CallOperator> entry : aggregations.entrySet()) {
            ColumnRefOperator origAggColRef = entry.getKey();
            CallOperator aggCall = (CallOperator) entry.getValue().clone();
            if (ctx.avgToSumCountMapping.containsKey(aggCall)) {
                // if it's an avg function, we need to rewrite it to sum and count function
                Pair<ColumnRefOperator, ColumnRefOperator> newAggPair = ctx.avgToSumCountMapping.get(aggCall);
                ColumnRefOperator sumColRef = newAggPair.first;
                CallOperator sumAggCall = ctx.aggColRefToPushDownAggMap.get(sumColRef);
                if (!getRollupFinalAggregate(mvRewriteContext, ctx, remapping, sumColRef, sumAggCall, newAggregations,
                        aggColRefToAggMap)) {
                    return null;
                }
                ColumnRefOperator countColRef = newAggPair.second;
                CallOperator countAggCall = ctx.aggColRefToPushDownAggMap.get(countColRef);
                if (!getRollupFinalAggregate(mvRewriteContext, ctx, remapping, countColRef, countAggCall, newAggregations,
                        aggColRefToAggMap)) {
                    return null;
                }
                if (!aggColRefToAggMap.containsKey(sumColRef) || !aggColRefToAggMap.containsKey(countColRef)) {
                    return null;
                }
                // after rewrite sum and count function(push-down), we need to rewrite avg function
                ScalarOperator newAvg = createAvgBySumCount(aggCall, aggColRefToAggMap.get(sumColRef),
                        aggColRefToAggMap.get(countColRef));
                aggColRefToAggMap.put(origAggColRef, newAvg);
            } else {
                if (!getRollupFinalAggregate(mvRewriteContext, ctx, remapping, origAggColRef, aggCall, newAggregations,
                        aggColRefToAggMap)) {
                    return null;
                }
            }
        }

        ReplaceColumnRefRewriter rewriter = new ReplaceColumnRefRewriter(aggColRefToAggMap);
        // add projection to make sure that the output columns keep the same with the origin query
        if (origAggregate.getProjection() != null) {
            Map<ColumnRefOperator, ScalarOperator> originalMap = origAggregate.getProjection().getColumnRefMap();
            for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : originalMap.entrySet()) {
                ScalarOperator rewritten = rewriter.rewrite(entry.getValue());
                aggProjection.put(entry.getKey(), rewritten);
            }
        } else {
            for (ColumnRefOperator columnRefOperator : origAggregate.getGroupingKeys()) {
                aggProjection.put(columnRefOperator, columnRefOperator);
            }
            for (Map.Entry<ColumnRefOperator, CallOperator> entry : origAggregate.getAggregations().entrySet()) {
                aggProjection.put(entry.getKey(), aggColRefToAggMap.get(entry.getKey()));
            }
        }

        // rewrite aggregate's predicate
        ScalarOperator predicate = origAggregate.getPredicate();
        if (origAggregate.getPredicate() != null) {
            predicate = rewriter.rewrite(origAggregate.getPredicate());
        }

        Projection projection = new Projection(aggProjection);
        LogicalAggregationOperator newAgg = LogicalAggregationOperator.builder()
                .withOperator(origAggregate)
                .setAggregations(newAggregations)
                .setProjection(projection)
                .setPredicate(predicate)
                .build();
        OptExpression result = OptExpression.create(newAgg, newChildren);
        return result;
    }

    private static boolean getRollupFinalAggregate(MvRewriteContext mvRewriteContext,
                                                   AggregatePushDownContext ctx,
                                                   Map<ColumnRefOperator, ColumnRefOperator> remapping,
                                                   ColumnRefOperator origAggColRef,
                                                   CallOperator aggCall,
                                                   Map<ColumnRefOperator, CallOperator> newAggregations,
                                                   Map<ColumnRefOperator, ScalarOperator> aggColRefToAggMap) {
        final ColumnRefFactory queryColumnRefFactory = mvRewriteContext.getMaterializationContext().getQueryRefFactory();
        CallOperator newAggregate = getRollupFinalAggregate(mvRewriteContext, ctx, remapping, origAggColRef, aggCall);
        if (newAggregate == null) {
            return false;
        }
        // If rewritten function is not an aggregation function, it could be like ScalarFunc(AggregateFunc(...))
        // We need to decompose it into Projection function and Aggregation function
        // E.g. count(distinct x) => array_length(array_unique_agg(x))
        // The array_length is a ScalarFunction and array_unique_agg is AggregateFunction
        // So it's decomposed into 1: array_length(slot_2), 2: array_unique_agg(x)
        CallOperator realAggregate = newAggregate;
        // rewrite it with remapping and final aggregate should use the new input as its argument.
        ScalarOperator newArg0 = remapping.get(origAggColRef);
        Preconditions.checkArgument(newArg0 != null, "Aggregation's arg0 is null after " +
                "remapping, aggColRef:{}, aggCall:{}", origAggColRef, aggCall);
        if (!newAggregate.isAggregate()) {
            int foundIndex = 0;
            if (!newAggregate.isAggregate()) {
                foundIndex = -1;
                for (int i = 0; i < newAggregate.getChildren().size(); i++) {
                    if (newAggregate.getChild(i) instanceof CallOperator) {
                        CallOperator call = (CallOperator) newAggregate.getChild(i);
                        if (call.isAggregate()) {
                            foundIndex = i;
                            realAggregate = call;
                            break;
                        }
                    }
                }
                if (foundIndex == -1) {
                    logMVRewrite(mvRewriteContext,
                            "no aggregate functions found: " + newAggregate.getChildren());
                    return false;
                }
            }
            realAggregate = replaceAggFuncArgument(mvRewriteContext, realAggregate, newArg0, foundIndex);
            ColumnRefOperator newAggColRef = queryColumnRefFactory.create(realAggregate,
                    realAggregate.getType(), realAggregate.isNullable());
            newAggregations.put(newAggColRef, realAggregate);
            CallOperator copyProject = (CallOperator) newAggregate.clone();
            copyProject.setChild(foundIndex, newAggColRef);
            // replace original projection to newProjColRef.
            aggColRefToAggMap.put(origAggColRef, copyProject);
        } else {
            realAggregate = replaceAggFuncArgument(mvRewriteContext, realAggregate, newArg0, 0);
            ColumnRefOperator newAggColRef = queryColumnRefFactory.create(realAggregate,
                    realAggregate.getType(), realAggregate.isNullable());
            newAggregations.put(newAggColRef, realAggregate);
            // replace original projection to newAggColRef or no need to change?
            aggColRefToAggMap.put(origAggColRef, genRollupProject(aggCall, newAggColRef, true));
        }
        return true;
    }

    private static CallOperator getRollupFinalAggregate(MvRewriteContext mvRewriteContext,
                                                        AggregatePushDownContext ctx,
                                                        Map<ColumnRefOperator, ColumnRefOperator> remapping,
                                                        ColumnRefOperator origAggColRef,
                                                        CallOperator aggCall) {
        CallOperator newAggCall = ctx.aggColRefToPushDownAggMap.get(origAggColRef);
        if (newAggCall == null) {
            logMVRewrite(mvRewriteContext, "newAggCall is null");
            return null;
        }
        CallOperator newAggregate = null;
        if (ctx.isRewrittenByEquivalent(newAggCall)) {
            newAggregate = ctx.aggToFinalAggMap.get(newAggCall);
            if (newAggregate == null) {
                logMVRewrite(mvRewriteContext, "Aggregation's final stage function is not found, aggColRef:{}, " +
                        "aggCall:{}", origAggColRef, aggCall);
                return null;
            }
        } else {
            ScalarOperator newArg0 = remapping.get(origAggColRef);
            if (newArg0 == null) {
                logMVRewrite(mvRewriteContext, "Aggregation's arg0 is not rewritten after remapping, " +
                        "aggColRef:{}, aggCall:{}", origAggColRef, aggCall);
                return null;
            }
            List<ScalarOperator> newArgs = aggCall.getChildren();
            newArgs.set(0, newArg0);
            String rollupFuncName = getRollupFunctionName(aggCall, false);
            // eg: count(distinct) + rollup
            if (rollupFuncName == null) {
                logMVRewrite(mvRewriteContext, "Get rollup function name is null, aggCall:{}", aggCall);
                return null;
            }
            Type[] argTypes = newArgs.stream().map(ScalarOperator::getType).toArray(Type[]::new);
            Function newFunc = Expr.getBuiltinFunction(rollupFuncName, argTypes,
                    Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF);
            if (newFunc == null) {
                logMVRewrite(mvRewriteContext, "Get rollup function is null, rollupFuncName:", rollupFuncName);
                return null;
            }
            // ensure argument types are correct
            // clone function to avoid changing the original function
            Function cloned = newFunc.copy();
            cloned.setArgsType(argTypes);
            cloned.setRetType(aggCall.getType());
            newAggregate = new CallOperator(rollupFuncName, aggCall.getType(), newArgs, cloned);
        }
        if (newAggregate == null) {
            logMVRewrite(mvRewriteContext, "realAggregate is null");
            return null;
        }
        return newAggregate;
    }

    public static CallOperator getRollupPartialAggregate(MvRewriteContext mvRewriteContext,
                                                         AggregatePushDownContext ctx,
                                                         CallOperator aggCall) {
        if (!ctx.isRewrittenByEquivalent(aggCall)) {
            return aggCall;
        }
        int argIdx = 0;
        ScalarOperator aggArg = null;
        for (ScalarOperator child : aggCall.getArguments()) {
            if (!child.isConstant()) {
                aggArg = child;
                break;
            }
            argIdx += 1;
        }
        CallOperator origAggCall = ctx.aggToOrigAggMap.get(aggCall);
        if (origAggCall == null) {
            logMVRewrite(mvRewriteContext, "newAggCall is null");
            return null;
        }
        CallOperator newAggregate = replaceAggFuncArgument(mvRewriteContext, origAggCall, aggArg, argIdx);
        return newAggregate;
    }

    // rewrite it with remapping and final aggregate should use the new input as its argument.
    private static CallOperator replaceAggFuncArgument(MvRewriteContext mvRewriteContext,
                                                       CallOperator aggCall,
                                                       ScalarOperator newArg,
                                                       int argIdx) {
        if (newArg == null) {
            logMVRewrite(mvRewriteContext, "Aggregation's arg0 is not rewritten after remapping, " +
                    "newAggColRef:{}, oldAggCall:{}", newArg, aggCall);
            return null;
        }
        CallOperator newAggCall = (CallOperator) aggCall.clone();
        if (argIdx >= newAggCall.getChildren().size()) {
            logMVRewrite(mvRewriteContext, "Aggregation's arg index is out of range, " +
                    "newAggColRef:{}, oldAggCall:{}", newArg, aggCall);
            return null;
        }
        newAggCall.setChild(argIdx, newArg);
        return newAggCall;
    }

    /**
     * Create new call operator with new function and arguments
     */
    public static Pair<ColumnRefOperator, CallOperator> createNewCallOperator(ColumnRefFactory columnRefFactory,
                                                                              Map<ColumnRefOperator, CallOperator> aggregations,
                                                                              Function newFn,
                                                                              List<ScalarOperator> args) {
        Preconditions.checkState(newFn != null);
        CallOperator newCallOp = new CallOperator(newFn.functionName(), newFn.getReturnType(), args, newFn);
        ColumnRefOperator newColRef =
                columnRefFactory.create(newCallOp, newCallOp.getType(), newCallOp.isNullable());
        // reuse old aggregation functions if it has existed
        Optional<CallOperator> existedOpt = aggregations.values().stream().filter(newCallOp::equals).findFirst();
        if (existedOpt.isPresent()) {
            return Pair.create(newColRef, existedOpt.get());
        } else {
            return Pair.create(newColRef, newCallOp);
        }
    }

    /**
     * Create new avg function by sum and count function
     */
    public static CallOperator createAvgBySumCount(CallOperator avgFunc,
                                                   ScalarOperator sumCallOp,
                                                   ScalarOperator countCallOp) {
        CallOperator newAvg = new CallOperator(FunctionSet.DIVIDE, avgFunc.getType(),
                Lists.newArrayList(sumCallOp, countCallOp));
        Type argType = avgFunc.getChild(0).getType();
        if (argType.isDecimalV3()) {
            // There is not need to apply ImplicitCastRule to divide operator of decimal types.
            // but we should cast BIGINT-typed countColRef into DECIMAL(38,0).
            ScalarType decimal128p38s0 = ScalarType.createDecimalV3NarrowestType(38, 0);
            newAvg.getChildren().set(1, new CastOperator(decimal128p38s0, newAvg.getChild(1), true));
        } else {
            final ScalarOperatorRewriter scalarRewriter = new ScalarOperatorRewriter();
            newAvg = (CallOperator) scalarRewriter.rewrite(newAvg, Lists.newArrayList(new ImplicitCastRule()));
        }
        return newAvg;
    }
}

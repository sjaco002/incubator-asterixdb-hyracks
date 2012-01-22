/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.hyracks.algebricks.rewriter.rules;

import org.apache.commons.lang3.mutable.Mutable;
import org.apache.commons.lang3.mutable.MutableObject;

import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.IPhysicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalOperatorTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator.ExecutionMode;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.ExchangeOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.physical.OneToOneExchangePOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.util.OperatorPropertiesUtil;
import edu.uci.ics.hyracks.algebricks.core.api.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.core.rewriter.base.IAlgebraicRewriteRule;

public class IsolateHyracksOperatorsRule implements IAlgebraicRewriteRule {

    private final PhysicalOperatorTag[] operatorsBelowWhichJobGenIsDisabled;

    public IsolateHyracksOperatorsRule(PhysicalOperatorTag[] operatorsBelowWhichJobGenIsDisabled) {
        this.operatorsBelowWhichJobGenIsDisabled = operatorsBelowWhichJobGenIsDisabled;
    }

    @Override
    public boolean rewritePost(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        IPhysicalOperator pt = op.getPhysicalOperator();

        if (pt == null || op.getOperatorTag() == LogicalOperatorTag.EXCHANGE) {
            return false;
        }
        if (!pt.isMicroOperator()) {
            return testIfExchangeBelow(opRef, context);
        } else {
            return testIfExchangeAbove(opRef, context);
        }
    }

    @Override
    public boolean rewritePre(Mutable<ILogicalOperator> opRef, IOptimizationContext context) {
        return false;
    }

    private boolean testIfExchangeBelow(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        boolean exchInserted = false;

        for (Mutable<ILogicalOperator> i : op.getInputs()) {
            AbstractLogicalOperator c = (AbstractLogicalOperator) i.getValue();
            if (c.getOperatorTag() != LogicalOperatorTag.EXCHANGE) {
                if (c.getPhysicalOperator() == null) {
                    return false;
                }
                insertOneToOneExchange(i, context);
                exchInserted = true;
            }
        }
        IPhysicalOperator pt = op.getPhysicalOperator();
        if (pt.isJobGenDisabledBelowMe() || arrayContains(operatorsBelowWhichJobGenIsDisabled, pt.getOperatorTag())) {
            for (Mutable<ILogicalOperator> i : op.getInputs()) {
                disableJobGenRec(i.getValue());
            }
        }
        return exchInserted;
    }

    private void disableJobGenRec(ILogicalOperator operator) {
        AbstractLogicalOperator op = (AbstractLogicalOperator) operator;
        op.disableJobGen();
        for (Mutable<ILogicalOperator> i : op.getInputs()) {
            disableJobGenRec(i.getValue());
        }
    }

    private boolean testIfExchangeAbove(Mutable<ILogicalOperator> opRef, IOptimizationContext context)
            throws AlgebricksException {
        AbstractLogicalOperator op = (AbstractLogicalOperator) opRef.getValue();
        if (op.getOperatorTag() == LogicalOperatorTag.EXCHANGE) {
            return false;
        }
        boolean exchInserted = false;
        for (Mutable<ILogicalOperator> i : op.getInputs()) {
            AbstractLogicalOperator c = (AbstractLogicalOperator) i.getValue();
            IPhysicalOperator cpop = c.getPhysicalOperator();
            if (c.getOperatorTag() == LogicalOperatorTag.EXCHANGE || cpop == null) {
                continue;
            }
            if (!cpop.isMicroOperator()) {
                insertOneToOneExchange(i, context);
                exchInserted = true;
            }
        }
        return exchInserted;
    }

    private final static <T> boolean arrayContains(T[] array, T tag) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == tag) {
                return true;
            }
        }
        return false;
    }

    private final static void insertOneToOneExchange(Mutable<ILogicalOperator> i, IOptimizationContext context)
            throws AlgebricksException {
        ExchangeOperator e = new ExchangeOperator();
        e.setPhysicalOperator(new OneToOneExchangePOperator());
        ILogicalOperator inOp = i.getValue();

        e.getInputs().add(new MutableObject<ILogicalOperator>(inOp));
        i.setValue(e);
        // e.recomputeSchema();
        OperatorPropertiesUtil.computeSchemaAndPropertiesRecIfNull(e, context);
        ExecutionMode em = ((AbstractLogicalOperator) inOp).getExecutionMode();
        e.setExecutionMode(em);
        e.computeDeliveredPhysicalProperties(context);
        context.computeAndSetTypeEnvironmentForOperator(e);
    }

}
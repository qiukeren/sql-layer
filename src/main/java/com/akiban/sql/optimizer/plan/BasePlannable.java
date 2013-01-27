/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.sql.optimizer.plan;

import com.akiban.ais.model.UserTable;
import com.akiban.sql.optimizer.plan.PhysicalSelect.PhysicalResultColumn;
import com.akiban.sql.types.DataTypeDescriptor;

import com.akiban.qp.exec.Plannable;
import com.akiban.qp.rowtype.RowType;
import com.akiban.server.explain.ExplainContext;
import com.akiban.server.explain.format.DefaultFormatter;

import java.util.*;

/** Physical operator plan */
public abstract class BasePlannable extends BasePlanNode
{
    private Plannable plannable;
    private DataTypeDescriptor[] parameterTypes;
    private List<PhysicalResultColumn> resultColumns;
    private RowType rowType;
    private CostEstimate costEstimate;
    private Set<UserTable> affectedTables;

    protected BasePlannable(Plannable plannable,
                            DataTypeDescriptor[] parameterTypes,
                            RowType rowType,
                            List<PhysicalResultColumn> resultColumns,
                            CostEstimate costEstimate,
                            Set<UserTable> affectedTables) {
        this.plannable = plannable;
        this.parameterTypes = parameterTypes;
        this.rowType = rowType;
        this.resultColumns = resultColumns;
        this.costEstimate = costEstimate;
        this.affectedTables = affectedTables;
    }

    public Plannable getPlannable() {
        return plannable;
    }
    public DataTypeDescriptor[] getParameterTypes() {
        return parameterTypes;
    }

    public RowType getResultRowType() {
        return rowType;
    }

    public List<PhysicalResultColumn> getResultColumns() {
        return resultColumns;
    }

    public CostEstimate getCostEstimate() {
        return costEstimate;
    }

    public Set<UserTable> getAffectedTables() {
        return affectedTables;
    }

    public abstract boolean isUpdate();

    @Override
    public boolean accept(PlanVisitor v) {
        return v.visit(this);
    }

    @Override
    protected void deepCopy(DuplicateMap map) {
        super.deepCopy(map);
        // Do not copy operators.
    }
    
    public String explainToString(ExplainContext context, String defaultSchemaName) {
        return withIndentedExplain(new StringBuilder(getClass().getSimpleName()), context, defaultSchemaName);
    }

    @Override
    public String toString() {
        return explainToString(null, null);
    }

    @Override
    public String summaryString() {
        // Similar to above, but with @hash for consistency.
        return withIndentedExplain(new StringBuilder(super.summaryString()), null, null);
    }

    protected String withIndentedExplain(StringBuilder str, ExplainContext context, String defaultSchemaName) {
        if (context == null)
            context = new ExplainContext(); // Empty
        DefaultFormatter f = new DefaultFormatter(defaultSchemaName);
        for (String operator : f.format(plannable.getExplainer(context))) {
            str.append("\n  ");
            str.append(operator);
        }
        return str.toString();
    }

}

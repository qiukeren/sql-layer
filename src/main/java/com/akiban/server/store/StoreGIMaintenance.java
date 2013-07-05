/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.akiban.server.store;

import com.akiban.ais.model.Column;
import com.akiban.ais.model.Group;
import com.akiban.ais.model.GroupIndex;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.UserTable;
import com.akiban.qp.operator.API;
import com.akiban.qp.operator.Cursor;
import com.akiban.qp.operator.Operator;
import com.akiban.qp.operator.QueryBindings;
import com.akiban.qp.operator.QueryContext;
import com.akiban.qp.operator.SimpleQueryContext;
import com.akiban.qp.operator.SparseArrayQueryBindings;
import com.akiban.qp.operator.StoreAdapter;
import com.akiban.qp.persistitadapter.PersistitHKey;
import com.akiban.qp.row.FlattenedRow;
import com.akiban.qp.row.Row;
import com.akiban.qp.rowtype.FlattenedRowType;
import com.akiban.qp.rowtype.RowType;
import com.akiban.qp.rowtype.Schema;
import com.akiban.qp.rowtype.UserTableRowType;
import com.akiban.server.rowdata.RowData;
import com.akiban.server.rowdata.RowDataPValueSource;
import com.akiban.server.rowdata.RowDataValueSource;
import com.akiban.server.types3.Types3Switch;
import com.akiban.util.tap.InOutTap;
import com.akiban.util.tap.PointTap;
import com.akiban.util.tap.Tap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

class StoreGIMaintenance {
    public void run(StoreGIHandler.Action action,
                    PersistitHKey hKey,
                    RowData forRow,
                    StoreAdapter adapter,
                    StoreGIHandler handler)
    {
        if (storePlan.noMaintenanceRequired())
            return;
        Cursor cursor = null;
        boolean runTapEntered = false;
        ALL_TAP.in();
        try {
            Operator planOperator = rootOperator();
            if (planOperator == null)
                return;
            QueryContext context = new SimpleQueryContext(adapter);
            QueryBindings bindings = new SparseArrayQueryBindings();
            List<Column> lookupCols = rowType.userTable().getPrimaryKeyIncludingInternal().getColumns();

            bindings.setHKey(StoreGIMaintenance.HKEY_BINDING_POSITION, hKey);

            // Copy the values into the array bindings
            RowDataValueSource source = new RowDataValueSource();
            RowDataPValueSource pSource = new RowDataPValueSource();
            for (int i=0; i < lookupCols.size(); ++i) {
                int bindingsIndex = i+1;
                Column col = lookupCols.get(i);
                pSource.bind(col.getFieldDef(), forRow);
                source.bind(col.getFieldDef(), forRow);

                // New types
                if (Types3Switch.ON) bindings.setPValue(bindingsIndex, pSource);
                else bindings.setValue(bindingsIndex, source);
            }
            cursor = API.cursor(planOperator, context, bindings);
            RUN_TAP.in();
            runTapEntered = true;
            cursor.open();
            Row row;
            while ((row = cursor.next()) != null) {
                boolean actioned = false;
                if (row.rowType().equals(planOperator.rowType())) {
                    doAction(action, handler, row);
                    actioned = true;
                }
                else if (storePlan.incomingRowIsWithinGI) {
                    // "Natural" index cleanup. Look for the left half, but only if we need to
                    Index.JoinType giJoin = groupIndex.getJoinType();
                    switch (giJoin) {
                    case LEFT:
                        if (row.rowType().equals(storePlan.leftHalf) && useInvertType(action, context, bindings) &&
                                !skipCascadeRow(action, row, handler)) {
                            Row outerRow = new FlattenedRow(storePlan.topLevelFlattenType, row, null, row.hKey());
                            doAction(invert(action), handler, outerRow);
                            actioned = true;
                        }
                        break;
                    case RIGHT:
                        if (row.rowType().equals(storePlan.rightHalf) && useInvertType(action, context, bindings) &&
                                !skipCascadeRow(action, row, handler)) {
                            Row outerRow = new FlattenedRow(storePlan.topLevelFlattenType, null, row, row.hKey());
                            doAction(invert(action), handler, outerRow);
                            actioned = true;
                        }
                        break;
                    default: throw new AssertionError(giJoin.name());
                    }
                }
                else {
                    // Hkey cleanup. Look for the right half.
                    if (row.rowType().equals(storePlan.rightHalf) && !skipCascadeRow(action, row, handler)) {
                        Row outerRow = new FlattenedRow(storePlan.topLevelFlattenType, null, row, row.hKey());
                        doAction(invert(action), handler, outerRow);
                        actioned = true;
                    }
                }
                if (!actioned) {
                    extraTap(action).hit();
                }
            }
        } finally {
            if (cursor != null) {
                cursor.destroy();
            }
            if (runTapEntered) {
                RUN_TAP.out();
            }
            ALL_TAP.out();
        }
    }

    private boolean skipCascadeRow(StoreGIHandler.Action action, Row row, StoreGIHandler handler) {
        return action == StoreGIHandler.Action.CASCADE &&
               row.rowType().typeComposition().tables().contains(handler.getSourceTable());
    }

    private boolean useInvertType(StoreGIHandler.Action action, QueryContext context, QueryBindings bindings) {
        switch (groupIndex.getJoinType()) {
        case LEFT:
            switch (action) {
            case CASCADE_STORE:
            case STORE:
                return true;
            case CASCADE:
            case DELETE:
                if (siblingsLookup == null)
                    return false;
                Cursor siblingsCounter = API.cursor(siblingsLookup, context, bindings);
                SIBLING_ALL_TAP.in();
                try {
                    siblingsCounter.open();
                    int siblings = 0;
                    while (siblingsCounter.next() != null) {
                        SIBLING_ROW_TAP.hit();
                        if (++siblings > 1)
                            return false;
                    }
                    return true;
                }
                finally {
                    siblingsCounter.destroy();
                    SIBLING_ALL_TAP.out();
                }
             default:
                 throw new AssertionError(action.name());
            }
        case RIGHT:
            return true;
        default: throw new AssertionError(groupIndex.getJoinType().name());
        }
    }

    private void doAction(StoreGIHandler.Action action, StoreGIHandler handler, Row row)
    {
        InOutTap actionTap = actionTap(action);
        actionTap.in();
        try {
            handler.handleRow(groupIndex, row, action);
        } finally {
            actionTap.out();
        }
    }

    private static StoreGIHandler.Action invert(StoreGIHandler.Action action) {
        switch (action) {
        case STORE:     return StoreGIHandler.Action.DELETE;
        case DELETE:    return StoreGIHandler.Action.STORE;
        case CASCADE:   return StoreGIHandler.Action.CASCADE_STORE;
        case CASCADE_STORE:   return StoreGIHandler.Action.CASCADE_STORE;
        default: throw new AssertionError(action.name());
        }
    }

    private Operator rootOperator() {
        return storePlan.rootOperator;
    }

    private InOutTap actionTap(StoreGIHandler.Action action) {
        if (action == null)
            return OTHER_TAP;
        switch (action) {
            case STORE:     return STORE_TAP;
            case DELETE:    return DELETE_TAP;
            default:        return OTHER_TAP;
        }
    }

    private PointTap extraTap(StoreGIHandler.Action action) {
        if (action == null)
            return EXTRA_OTHER_ROW_TAP;
        switch (action) {
            case STORE:     return EXTRA_STORE_ROW_TAP;
            case DELETE:    return EXTRA_DELETE_ROW_TAP;
            default:        return EXTRA_OTHER_ROW_TAP;
        }
    }

    public StoreGIMaintenance(BranchTables branchTables,
                              GroupIndex groupIndex,
                              UserTableRowType rowType)
    {
        this.storePlan = createGroupIndexMaintenancePlan(branchTables, groupIndex, rowType);
        siblingsLookup = createSiblingsFinder(groupIndex, branchTables, rowType);
        this.rowType = rowType;
        this.groupIndex = groupIndex;
    }

    private final PlanCreationInfo storePlan;
    private final Operator siblingsLookup;
    private final GroupIndex groupIndex;
    private final UserTableRowType rowType;
    

    // for use in this class

    private Operator createSiblingsFinder(GroupIndex groupIndex, BranchTables branchTables, UserTableRowType rowType) {
        // only bother doing this for tables *leafward* of the rootmost table in the GI
        if (rowType.userTable().getDepth() <= branchTables.rootMost().userTable().getDepth())
            return null;
        UserTable parentUserTable = rowType.userTable().parentTable();
        if (parentUserTable == null) {
            return null;
        }
        final Group group = groupIndex.getGroup();
        final UserTableRowType parentRowType = branchTables.parentRowType(rowType);
        assert parentRowType != null;

        Operator plan = API.groupScan_Default(
                groupIndex.getGroup(),
                HKEY_BINDING_POSITION,
                false,
                rowType.userTable(),
                branchTables.fromRoot().get(0).userTable()
        );
        plan = API.ancestorLookup_Default(plan, group, rowType, Collections.singleton(parentRowType), API.InputPreservationOption.DISCARD_INPUT);
        plan = API.branchLookup_Default(plan, group, parentRowType, rowType, API.InputPreservationOption.DISCARD_INPUT);
        plan = API.filter_Default(plan, Collections.singleton(rowType));
        return plan;
    }

    private static List<UserTableRowType> ancestors(RowType rowType, List<UserTableRowType> branchTables) {
        List<UserTableRowType> ancestors = new ArrayList<>();
        for(UserTableRowType ancestor : branchTables) {
            if (ancestor.equals(rowType)) {
                return ancestors;
            }
            ancestors.add(ancestor);
        }
        throw new RuntimeException(rowType + "not found in " + branchTables);
    }

    private static PlanCreationInfo createGroupIndexMaintenancePlan(BranchTables branchTables,
                                                                      GroupIndex groupIndex,
                                                                      UserTableRowType rowType)
    {
        if (branchTables.isEmpty()) {
            throw new RuntimeException("group index has empty branch: " + groupIndex);
        }
        if (!branchTables.fromRoot().contains(rowType)) {
            throw new RuntimeException(rowType + " not in branch for " + groupIndex + ": " + branchTables);
        }

        PlanCreationInfo result = new PlanCreationInfo(rowType, groupIndex);

        Operator plan = API.groupScan_Default(
                groupIndex.getGroup(),
                HKEY_BINDING_POSITION,
                false,
                rowType.userTable(),
                branchTables.fromRoot().get(0).userTable()
        );
        if (branchTables.fromRoot().size() == 1) {
            result.rootOperator = plan;
            return result;
        }
        if (!branchTables.leafMost().equals(rowType)) {
            // the incoming row isn't the leaf, so we have to get its ancestors along the branch
            UserTableRowType child = branchTables.childOf(rowType);
            plan = API.branchLookup_Default(
                    plan,
                    groupIndex.getGroup(),
                    rowType,
                    child,
                    API.InputPreservationOption.KEEP_INPUT
            );
        }
        if (!branchTables.fromRoot().get(0).equals(rowType)) {
            plan = API.ancestorLookup_Default(
                    plan,
                    groupIndex.getGroup(),
                    rowType,
                    ancestors(rowType, branchTables.fromRoot()),
                    API.InputPreservationOption.KEEP_INPUT
            );
        }

        // RIGHT JOIN until the GI, and then the GI's join types

        RowType parentRowType = null;
        API.JoinType joinType = API.JoinType.RIGHT_JOIN;
        int branchStartDepth = branchTables.rootMost().userTable().getDepth() - 1;
        boolean withinBranch = branchStartDepth == -1;
        API.JoinType withinBranchJoin = operatorJoinType(groupIndex);
        result.incomingRowIsWithinGI = rowType.userTable().getDepth() >= branchTables.rootMost().userTable().getDepth();

        for (UserTableRowType branchRowType : branchTables.fromRoot()) {
            boolean breakAtTop = result.incomingRowIsWithinGI && withinBranchJoin == API.JoinType.LEFT_JOIN;
            if (breakAtTop && branchRowType.equals(rowType)) {
                result.leftHalf = parentRowType;
                parentRowType = null;
            }
            if (parentRowType == null) {
                parentRowType = branchRowType;
            } else {
                plan = API.flatten_HKeyOrdered(plan, parentRowType, branchRowType, joinType);
                parentRowType = plan.rowType();
            }
            if (branchRowType.userTable().getDepth() == branchStartDepth) {
                withinBranch = true;
            } else if (withinBranch) {
                joinType = withinBranchJoin;
            }
            if ( (!breakAtTop) && branchRowType.equals(rowType)) {
                result.leftHalf = parentRowType;
                parentRowType = null;
            }
        }
        result.rightHalf = parentRowType;
        if (result.leftHalf != null && result.rightHalf != null) {
            API.JoinType topJoinType = rowType.userTable().getDepth() <= branchTables.rootMost().userTable().getDepth()
                    ? API.JoinType.RIGHT_JOIN
                    : joinType;
            plan = API.flatten_HKeyOrdered(plan, result.leftHalf, result.rightHalf, topJoinType, KEEP_BOTH);
            result.topLevelFlattenType = (FlattenedRowType) plan.rowType();
        }

        result.rootOperator = plan;
        return result;
    }

    private static final EnumSet<API.FlattenOption> KEEP_BOTH = EnumSet.of(
            API.FlattenOption.KEEP_PARENT,
            API.FlattenOption.KEEP_CHILD
    );

    private static API.JoinType operatorJoinType(Index index) {
        switch (index.getJoinType()) {
        case LEFT:
            return API.JoinType.LEFT_JOIN;
        case RIGHT:
            return API.JoinType.RIGHT_JOIN;
        default:
            throw new AssertionError(index.getJoinType().name());
        }
    }

    // package consts
    private static final int HKEY_BINDING_POSITION = 0;
    private static final InOutTap ALL_TAP = Tap.createTimer("GI maintenance: all");
    private static final InOutTap RUN_TAP = Tap.createTimer("GI maintenance: run");
    private static final InOutTap STORE_TAP = Tap.createTimer("GI maintenance: STORE");
    private static final InOutTap DELETE_TAP = Tap.createTimer("GI maintenance: DELETE");
    private static final InOutTap OTHER_TAP = Tap.createTimer("GI maintenance: OTHER");
    private static final InOutTap SIBLING_ALL_TAP = Tap.createTimer("GI maintenance: sibling all");
    private static final PointTap SIBLING_ROW_TAP = Tap.createCount("GI maintenance: sibling row");
    private static final PointTap EXTRA_STORE_ROW_TAP = Tap.createCount("GI maintenance: extra store");
    private static final PointTap EXTRA_DELETE_ROW_TAP = Tap.createCount("GI maintenance: extra delete");
    private static final PointTap EXTRA_OTHER_ROW_TAP = Tap.createCount("GI maintenance: extra other");
    // nested classes

    static class BranchTables {

        // BranchTables interface

        public List<UserTableRowType> fromRoot() {
            return allTablesForBranch;
        }

        public List<UserTableRowType> fromRootMost() {
            return onlyBranch;
        }

        public boolean isEmpty() {
            return fromRootMost().isEmpty();
        }

        public UserTableRowType rootMost() {
            return onlyBranch.get(0);
        }

        public UserTableRowType leafMost() {
            return onlyBranch.get(onlyBranch.size()-1);
        }

        public UserTableRowType childOf(UserTableRowType rowType) {
            int inputDepth = rowType.userTable().getDepth();
            int childDepth = inputDepth + 1;
            return allTablesForBranch.get(childDepth);
        }

        public UserTableRowType parentRowType(UserTableRowType rowType) {
            UserTableRowType parentType = null;
            for (UserTableRowType type : allTablesForBranch) {
                if (type.equals(rowType)) {
                    return parentType;
                }
                parentType = type;
            }
            throw new IllegalArgumentException(rowType + " not in branch: " + allTablesForBranch);
        }

        public BranchTables(Schema schema, GroupIndex groupIndex) {
            List<UserTableRowType> localTables = new ArrayList<>();
            UserTable rootmost = groupIndex.rootMostTable();
            int branchRootmostIndex = -1;
            for (UserTable table = groupIndex.leafMostTable(); table != null; table = table.parentTable()) {
                if (table.equals(rootmost)) {
                    assert branchRootmostIndex == -1 : branchRootmostIndex;
                    branchRootmostIndex = table.getDepth();
                }
                localTables.add(schema.userTableRowType(table));
            }
            if (branchRootmostIndex < 0) {
                throw new RuntimeException("branch root not found! " + rootmost + " within " + localTables);
            }
            Collections.reverse(localTables);
            this.allTablesForBranch = Collections.unmodifiableList(localTables);
            this.onlyBranch = branchRootmostIndex == 0
                    ? allTablesForBranch
                    : allTablesForBranch.subList(branchRootmostIndex, allTablesForBranch.size());
        }

        // object state
        private final List<UserTableRowType> allTablesForBranch;
        private final List<UserTableRowType> onlyBranch;
    }

    static class PlanCreationInfo {

        public boolean noMaintenanceRequired() {
            return (!incomingRowIsWithinGI) && (incomingRowType.userTable().getDepth() == 0);
        }

        @Override
        public String toString() {
            return toString;
        }

        PlanCreationInfo(RowType forRow, GroupIndex forGi) {
            this.toString = String.format("for %s in %s", forRow, forGi.getIndexName().getName());
            this.incomingRowType = forRow;
        }

        public final String toString;
        public final RowType incomingRowType;

        public Operator rootOperator;
        public FlattenedRowType topLevelFlattenType;
        public RowType leftHalf;
        public RowType rightHalf;
        public boolean incomingRowIsWithinGI;
    }
}

/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.ais.model;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class Group implements Serializable, ModelNames
{
    public static Group create(AkibanInformationSchema ais, Map<String, Object> map)
    {
        return create(ais, (String) map.get(group_name));
    }

    public static Group create(AkibanInformationSchema ais, String groupName)
    {
        Group group = new Group(groupName);
        ais.addGroup(group);
        return group;
    }

    public Map<String, Object> map()
    {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(group_name, name);
        return map;
    }

    @SuppressWarnings("unused")
    private Group()
    {
        // GWT requires empty constructor
    }

    public Group(final String name)
    {
        this.name = name;
    }

    @Override
    public String toString()
    {
        return "Group(" + name + " -> " + groupTable.getName() + ")";
    }

    public String getName()
    {
        return name;
    }

    public String getDescription()
    {
        return name;
    }

    public GroupTable getGroupTable()
    {
        return groupTable;
    }

    public void setGroupTable(GroupTable groupTable)
    {
        this.groupTable = groupTable;
    }

    public Collection<GroupIndex> getIndexes()
    {
        return Collections.unmodifiableCollection(internalGetIndexMap().values());
    }

    public GroupIndex getIndex(String indexName)
    {
        return internalGetIndexMap().get(indexName.toLowerCase());
    }

    public void checkIntegrity(List<String> out)
    {
        for (Map.Entry<String, GroupIndex> entry : internalGetIndexMap().entrySet()) {
            String name = entry.getKey();
            GroupIndex index = entry.getValue();
            if (name == null) {
                out.add("null name for index: " + index);
            } else if (index == null) {
                out.add("null index for name: " + name);
            } else if (index.getGroup() != this) {
                out.add("group's index.getGroup() wasn't the group" + index + " <--> " + this);
            }
            if (index != null) {
                for (IndexColumn indexColumn : index.getColumns()) {
                    if (!index.equals(indexColumn.getIndex())) {
                        out.add("index's indexColumn.getIndex() wasn't index: " + indexColumn);
                    }
                    Column column = indexColumn.getColumn();
                    if (column == null) {
                        out.add("column was null in index column: " + indexColumn);
                    }
                    else if(column.getTable() == null) {
                        out.add("column's table was null: " + column);
                    }
                    else if(column.getTable().getGroup() != this) {
                        out.add("column table's group was wrong " + column.getTable().getGroup() + "<-->" + this);
                    }
                }
            }
        }
    }

    public void addIndex(GroupIndex index)
    {
        Map<String, GroupIndex> old;
        Map<String, GroupIndex> withNewIndex;
        do {
            old = internalGetIndexMap();
            withNewIndex = new TreeMap<String, GroupIndex>(old);
            withNewIndex.put(index.getIndexName().getName().toLowerCase(), index);
        } while(!internalIndexMapCAS(old, withNewIndex));
    }

    public void removeIndexes(Collection<GroupIndex> indexesToDrop)
    {
        Map<String, GroupIndex> old;
        Map<String, GroupIndex> remaining;
        do {
            old = internalGetIndexMap();
            remaining = new TreeMap<String, GroupIndex>(old);
            remaining.values().removeAll(indexesToDrop);
        } while (!internalIndexMapCAS(old, remaining));
    }

    private Map<String, GroupIndex> internalGetIndexMap() {
        return indexMap;
    }

    private boolean internalIndexMapCAS(Map<String, GroupIndex> expected, Map<String, GroupIndex> update)
    {
        // GWT-friendly CAS
        synchronized (LOCK) {
            if (indexMap != expected) {
                return false;
            }
            indexMap = update;
            return true;
        }
    }

    // State

    private String name;
    private GroupTable groupTable;
    private final Object LOCK = new Object();
    private volatile Map<String, GroupIndex> indexMap = Collections.emptyMap();
}

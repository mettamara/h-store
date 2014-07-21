/**
 * 
 */
package edu.brown.hashing;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.collections.map.LRUMap;
import org.apache.commons.lang.NotImplementedException;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.voltdb.CatalogContext;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Table;
import org.voltdb.utils.VoltTypeUtil;

import edu.brown.hashing.ReconfigurationPlan.ReconfigurationRange;
import edu.brown.hstore.HStoreConstants;
import edu.brown.hstore.reconfiguration.ReconfigurationUtil;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.utils.FileUtil;
import edu.brown.utils.JSONSerializable;
import edu.brown.utils.StringUtil;

//       TODO This class likely needs to be relocated (ae)
/**
 * @author aelmore A container for statically defined partitions plans. Each
 *         plan will contain multiple partition phases. Each partition phase
 *         will contain a list of tables that dictate how the table is
 *         partitioned. <br>
 *         PlannedPartitions Hierarchy:
 *         <ul>
 *         <li>Map[String, PartitionPhase] partition_phase_map
 *         <ul>
 *         <li>Map[String, PartitionedTable] tables_map
 *         <ul>
 *         <li>List[PartitionRange] partitions
 *         <ul>
 *         <li>PartitionRange: min,max,partition_id
 *         </ul>
 *         </ul>
 *         </ul>
 *         </ul>
 */

public class PlannedPartitions extends ExplicitPartitions implements JSONSerializable {
    private static final Logger LOG = Logger.getLogger(PlannedPartitions.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    public static final String PLANNED_PARTITIONS = "partition_plans";

    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }

    private Map<String, PartitionPhase> partition_phase_map;
    private String current_phase;
    private String previous_phase;

    public PlannedPartitions(CatalogContext catalog_context, File planned_partition_json_file) throws Exception {
        this(catalog_context, new JSONObject(FileUtil.readFile(planned_partition_json_file)));
    }

    public PlannedPartitions(CatalogContext catalog_context, JSONObject planned_partition_json) throws Exception {
        super(catalog_context, planned_partition_json);
        this.current_phase = null;
        this.previous_phase = null;
        this.partition_phase_map = new HashMap<>();

        if (planned_partition_json.has(PLANNED_PARTITIONS)) {
            JSONObject phases = planned_partition_json.getJSONObject(PLANNED_PARTITIONS);
            String first_key = null;
            Iterator<String> keys = phases.keys();
            while (keys.hasNext()) {
                String key = keys.next();

                JSONObject phase = phases.getJSONObject(key);
                this.partition_phase_map.put(key, new PartitionPhase(catalog_context, phase, partitionedTablesByFK));

                // Use the first phase by default
                if (first_key == null) {
                    first_key = key;
                    this.setPartitionPhase(first_key);
                }
            }

        } else {
            throw new JSONException(String.format("JSON file is missing key \"%s\". ", PLANNED_PARTITIONS));
        }

        // TODO check to make sure partitions exist that are in the plan (ae)

    }

    /**
     * Get the explicit partitioned tables and ensure that each phase has the
     * same set of tables
     * 
     * @param planned_partition_json
     * @return the set of tables in the partition plan
     */
    public Set<String> getExplicitPartitionedTables(JSONObject planned_partition_json) {
        try {
            Set<String> tables = null;
            if (planned_partition_json.has(PLANNED_PARTITIONS)) {
                JSONObject phases = planned_partition_json.getJSONObject(PLANNED_PARTITIONS);
                Iterator<String> keys = phases.keys();
                while (keys.hasNext()) {
                    JSONObject phase = phases.getJSONObject(keys.next());
                    Set<String> phase_tables = new HashSet<>();
                    Iterator<String> table_names = phase.getJSONObject(TABLES).keys();
                    while (table_names.hasNext()) {
                        phase_tables.add(table_names.next());
                    }
                    if (tables == null) {
                        // First set of tables
                        tables = phase_tables;
                    } else {
                        // check if equal
                        if (tables.equals(phase_tables) == false) {
                            throw new RuntimeException(String.format("Partition plan has mistmatched tables (%s) != (%s)", tables, phase_tables));
                        }
                    }
                }
            }
            return tables;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /*
     * (non-Javadoc)
     * @see edu.brown.hashing.ExplicitPartition#getPartitionId(java.lang.String,
     * java.lang.Object)
     */
    @Override
    public int getPartitionId(String table_name, List<Object> ids) throws Exception {
    	if (this.reconfigurationPlan != null) {
    		ReconfigurationRange range = this.reconfigurationPlan.findReconfigurationRange(table_name, ids);
    		if (range != null) {
    			return range.getNewPartition();
    		}
    	}
    	if (this.incrementalPlan != null) {
    		PartitionedTable table = incrementalPlan.getTable(table_name);
    		assert table != null : "Table not found " + table_name;
    		return table.findPartition(ids);
    	}

        PartitionPhase phase = this.partition_phase_map.get(this.getCurrent_phase());
        PartitionedTable table = phase.getTable(table_name);
        if (table == null) {
            if (debug.val)
                LOG.debug(String.format("Table not found: %s, using default:%s ", table_name, this.default_table));
            table = phase.getTable(this.default_table);
            if (table == null) {
                throw new RuntimeException(String.format("Default partition table is null. Lookup table:%s Default Table:%s", table_name, this.default_table));
            }
        }
        assert table != null : "Table not found " + table_name;
        return table.findPartition(ids);
    }

    /*
     * (non-Javadoc)
     * @see
     * edu.brown.hashing.ExplicitPartition#getPreviousPartitionId(java.lang.
     * String, java.lang.Object)
     */
    @Override
    public int getPreviousPartitionId(String table_name, List<Object> ids) throws Exception {
    	if (this.reconfigurationPlan != null) {
    		ReconfigurationRange range = this.reconfigurationPlan.findReconfigurationRange(table_name, ids);
    		if (range != null) {
    			return range.getOldPartition();
    		}
    	}
    	if (this.incrementalPlan != null) {
    		PartitionedTable table = incrementalPlan.getTable(table_name);
    		assert table != null : "Table not found " + table_name;
    		return table.findPartition(ids);
    	}

        String previousPhase = this.getPreviousPhase_phase();
        if (previousPhase == null)
            return -1;
        PartitionPhase phase = this.partition_phase_map.get(previousPhase);
        PartitionedTable table = phase.getTable(table_name);
        if (table == null) {
            table = phase.getTable(table_name.toLowerCase());
            if (table == null) {
                throw new Exception("Unable to find table " + table_name + " in phase  " + previousPhase);
            }
        }
        assert table != null : "Table not found " + table_name;
        return table.findPartition(ids);
    }

    @Override
    public List<Integer> getAllPartitionIds(String table_name, List<Object> ids) throws Exception {
        List<Integer> allPartitionIds = new ArrayList<Integer>();
        if (this.reconfigurationPlan != null) {
        	List<ReconfigurationRange> ranges = this.reconfigurationPlan.findAllReconfigurationRanges(table_name, ids);
        	for (ReconfigurationRange range : ranges) {
        		allPartitionIds.add(range.getNewPartition());
        	}
        }
        if (this.incrementalPlan != null) {
        	PartitionedTable table = incrementalPlan.getTable(table_name);
        	assert table != null : "Table not found " + table_name;
        	allPartitionIds.addAll(table.findAllPartitions(ids));
        	return allPartitionIds;
        }

        PartitionPhase phase = this.partition_phase_map.get(this.getCurrent_phase());
        PartitionedTable table = phase.getTable(table_name);
        if (table == null) {
            if (debug.val)
                LOG.debug(String.format("Table not found: %s, using default:%s ", table_name, this.default_table));
            table = phase.getTable(this.default_table);
            if (table == null) {
                throw new RuntimeException(String.format("Default partition table is null. Lookup table:%s Default Table:%s", table_name, this.default_table));
            }
        }
        assert table != null : "Table not found " + table_name;
        return table.findAllPartitions(ids);
    }

    @Override
    public List<Integer> getAllPreviousPartitionIds(String table_name, List<Object> ids) throws Exception {
        List<Integer> allPartitionIds = new ArrayList<Integer>();
        if (this.reconfigurationPlan != null) {
        	List<ReconfigurationRange> ranges = this.reconfigurationPlan.findAllReconfigurationRanges(table_name, ids);
        	for (ReconfigurationRange range : ranges) {
        		allPartitionIds.add(range.getOldPartition());
        	}
        }
        if (this.incrementalPlan != null) {
        	PartitionedTable table = incrementalPlan.getTable(table_name);
        	assert table != null : "Table not found " + table_name;
        	allPartitionIds.addAll(table.findAllPartitions(ids));
        	return allPartitionIds;
        }

        String previousPhase = this.getPreviousPhase_phase();
        if (previousPhase == null)
            return allPartitionIds;
        PartitionPhase phase = this.partition_phase_map.get(previousPhase);
        PartitionedTable table = phase.getTable(table_name);
        if (table == null) {
            table = phase.getTable(table_name.toLowerCase());
            if (table == null) {
                throw new Exception("Unable to find table " + table_name + " in phase  " + previousPhase);
            }
        }
        assert table != null : "Table not found " + table_name;
        return table.findAllPartitions(ids);
    }

    // ******** Containers *****************************************/

    /**
     * @author aelmore Holds the phases/epochs/version of a partition plan
     */
    public static class PartitionPhase {
        protected Map<String, PartitionedTable> tables_map;
        protected CatalogContext catalog_context;
        protected Map<String, String> partitionedTablesByFK;

        public List<PartitionRange> getPartitions(String table_name) {
            return this.tables_map.get(table_name).getRanges();
        }

        public PartitionedTable getTable(String table_name) {
            return this.tables_map.get(table_name);
        }

        /**
         * Create a new partition phase
         * 
         * @param catalog_db
         * @param table_vt_map
         *            mapping of table names to volt type of partition col
         * @param phase
         *            JSONObject
         * @param partitionedTablesByFK
         */
        public PartitionPhase(CatalogContext catalog_context, JSONObject phase, Map<String, String> partitionedTablesByFK) throws Exception {
            this.tables_map = new HashMap<String, PlannedPartitions.PartitionedTable>();
            this.catalog_context = catalog_context;
            this.partitionedTablesByFK = partitionedTablesByFK;
            assert (phase.has(TABLES));
            JSONObject json_tables = phase.getJSONObject(TABLES);
            Iterator<String> table_names = json_tables.keys();
            while (table_names.hasNext()) {
                String table_name = table_names.next();
                JSONObject table_json = json_tables.getJSONObject(table_name.toLowerCase());
                // Class<?> c = table_vt_map.get(table_name).classFromType();
                // TODO fix partitiontype
                this.tables_map.put(table_name, new PartitionedTable(table_name, table_json, catalog_context.getTableByName(table_name)));
            }

            // Add entries for tables that are partitioned on other columns
            for (Entry<String, String> partitionedFK : partitionedTablesByFK.entrySet()) {
                String table_name = partitionedFK.getKey();
                String fk_table_name = partitionedFK.getValue();
                if (json_tables.has(fk_table_name) == false) {
                    throw new RuntimeException(String.format("For table %s, the foreignkey partitioned table %s is not explicitly partitioned ", table_name, fk_table_name));
                }
                LOG.info(String.format("Adding FK partitioning %s->%s", table_name, fk_table_name));
                this.tables_map.put(partitionedFK.getKey(), this.tables_map.get(partitionedFK.getValue()).clone(table_name, catalog_context.getTableByName(table_name)));
            }
        }

        protected PartitionPhase(Map<String, PlannedPartitions.PartitionedTable> table_map) {
            this.tables_map = table_map;
        }

        protected PartitionPhase(CatalogContext catalog_context, List<PartitionRange> ranges, Map<String, String> partitionedTablesByFK) throws Exception {
            this.tables_map = new HashMap<String, PlannedPartitions.PartitionedTable>();
            this.catalog_context = catalog_context;
            this.partitionedTablesByFK = partitionedTablesByFK;
            
            HashMap<String, List<PartitionRange>> table_name_map = new HashMap<String, List<PartitionRange>>();

            for (PartitionRange range : ranges) {
                String table_name = range.catalog_table.getName().toLowerCase();
                if (!table_name_map.containsKey(table_name)) {
                    table_name_map.put(table_name, new ArrayList<PartitionRange>());
                }
                table_name_map.get(table_name).add(range);
            }

            for (Map.Entry<String, List<PartitionRange>> tableRanges : table_name_map.entrySet()) {
                String table_name = tableRanges.getKey();
                this.tables_map.put(table_name, new PartitionedTable(tableRanges.getValue(), table_name, this.catalog_context.getTableByName(table_name)));
            }
        }
    }

    /**
     * @author aelmore Holds the partitioning for a table, during a given phase
     * @param <T>
     *            The type of the ID which is partitioned on. Comparable
     */
    public static class PartitionedTable {

        protected List<PartitionRange> partitions;
        protected String table_name;
        private Table catalog_table;
        private JSONObject table_json;

        public PartitionedTable(String table_name, JSONObject table_json, Table catalog_table) throws Exception {
            this.catalog_table = catalog_table;
            this.partitions = new ArrayList<>();
            this.table_name = table_name;
            this.table_json = table_json;
            assert (table_json.has(PARTITIONS));
            JSONObject partitions_json = table_json.getJSONObject(PARTITIONS);
            Iterator<String> partitions = partitions_json.keys();
            while (partitions.hasNext()) {
                String partition = partitions.next();
                // TODO do we need more than ints, what about specifying ranges
                // as
                // replicated tables (ae)
                int partition_id = Integer.parseInt(partition);
                this.addPartitionRanges(partition_id, partitions_json.getString(partition));
            }
            Collections.sort(this.partitions);
        }

        public PartitionedTable clone(String new_table_name, Table new_catalog_table) throws Exception {
            return new PartitionedTable(new_table_name, this.table_json, new_catalog_table);
        }

        public PartitionedTable(List<PartitionRange> partitions, String table_name, Table catalog_table) {
            this.partitions = partitions;
            this.table_name = table_name;
            this.catalog_table = catalog_table;
        }

        /**
         * Find the partition for a key
         * 
         * @param id
         * @return the partition id or null partition id if no match could be
         *         found
         */
        public int findPartition(List<Object> ids) throws Exception {
            if (trace.val) {
                LOG.trace(String.format("Looking up key %s on table %s during phase %s", ids.get(0), this.table_name));
            }

            try {

            	
            	Object[] keys = ids.toArray();
                for (PartitionRange p : this.partitions) {
                    // if this greater than or equal to the min inclusive val
                    // and
                    // less than
                    // max_exclusive or equal to both min and max (singleton)
                    // TODO fix partitiontype
                    if (p.inRange(keys)) {

                    	return p.partition;
                    }
                }
            } catch (Exception e) {
                LOG.error("Error looking up partition", e);
            }

            if (debug.val)
                LOG.debug("Partition not found. ids: " + ids.toString() + ", partitions: " + this.partitions.toString());

            return HStoreConstants.NULL_PARTITION_ID;
        }

        /**
         * Find all the partitions that may contain a key
         * 
         * @param id
         * @return the matching partition ids
         */
        public List<Integer> findAllPartitions(List<Object> ids) throws Exception {
            if (trace.val) {
                LOG.trace(String.format("Looking up key %s on table %s during phase %s", ids.get(0), this.table_name));
            }

            List<Integer> partitionIds = new ArrayList<Integer>();
            Object[] keys = ids.toArray();
            for (PartitionRange p : this.partitions) {
                try {

                    // if this greater than or equal to the min inclusive val
                    // and
                    // less than
                    // max_exclusive or equal to both min and max (singleton)
                    // TODO fix partitiontype
                    if (p.inRangeIgnoreNullCols(keys)) {
                        partitionIds.add(p.partition);
                    }
                } catch (Exception e) {
                    LOG.error("Error looking up partition", e);
                }
            }

            return partitionIds;
        }

        /**
         * Associate a partition with a set of values in the form of val or
         * val1,val2 or val1-val2 or val1,val2-val3 or some other combination
         * 
         * @param partition_id
         * @param partitionValues
         * @throws ParseException
         */
        public void addPartitionRanges(int partition_id, String partition_values) throws ParseException {

            for (String range : partition_values.split(",")) {
                this.partitions.add(new PartitionRange(this.catalog_table, partition_id, range));
            }
        }

        public List<PartitionRange> getRanges() {
            return this.partitions;
        }

        public Table getCatalog_table() {
            return catalog_table;
        }
    }
    
    public static class PartitionKeyComparator implements Comparator<Object[]> {

    	@Override
    	public int compare(Object[] o1, Object[] o2) {
    		assert (o1 != null);
    		assert (o2 != null);
    		
    		int length = Math.min(o1.length, o2.length);
    		long cmp = 0;
    		for (int i = 0; i < length; i++) {
    			cmp = ((Number) o1[i]).longValue() - ((Number) o2[i]).longValue();
    			
    			if (cmp != 0)
    				break;
    		} // FOR
    		
    		if(cmp == 0) {
    			if(o1.length > o2.length) return 1;
    			else if(o2.length > o1.length) return -1;
    			else return 0;
    		}
    		
    		return (cmp < 0 ? -1 : 1);
    	}
    
    }

    /**
     * A defined range of keys and an associated partition id. Sorts by min id,
     * then max id only first, ie (1-4 < 2-3) and (1-4 < 1-5)
     * 
     * @author aelmore
     * @param <T>
     *            Comparable type of key
     */
    public static class PartitionRange implements Comparable<PartitionRange> {
        private int partition;
        private VoltTable keySchema;
        private Object[] min_incl;
        private Object[] max_excl;
        private PartitionKeyComparator cmp;
        private Table catalog_table;
        private int non_null_cols;

        public PartitionRange(Table table, int partition_id, String range_str) throws ParseException {
            this.partition = partition_id;
            this.catalog_table = table;

            this.keySchema = ReconfigurationUtil.getPartitionKeysVoltTable(table);
            Object[] min_row;
            Object[] max_row;

            // x-y
            if (range_str.contains("-")) {
                String vals[] = range_str.split("-", 2);

                min_row = getRangeKeys(vals[0]);
                max_row = getRangeKeys(vals[1]);

            } else {
                throw new ParseException("keys must be specified as min-max. range: " + range_str, -1);
            }

            this.cmp = new PartitionKeyComparator();

            keySchema.addRow(min_row);
            keySchema.advanceToRow(0);
            this.min_incl = keySchema.getRowArray();
            keySchema.clearRowData();

            keySchema.addRow(max_row);
            keySchema.advanceToRow(0);
            this.max_excl = keySchema.getRowArray();
            keySchema.clearRowData();

            if (cmp.compare(this.min_incl, this.max_excl) > 0) {
                throw new ParseException("Min cannot be greater than max", -1);
            }
            
            this.non_null_cols = this.getNonNullCols();
        }

        public PartitionRange(Table table, int partition_id, Object[] min_incl, Object[] max_excl) {
            this.partition = partition_id;
            this.catalog_table = table;

            this.keySchema = ReconfigurationUtil.getPartitionKeysVoltTable(table);
            this.cmp = new PartitionKeyComparator();

            this.min_incl = min_incl;
            this.max_excl = max_excl;
            this.non_null_cols = this.getNonNullCols();
        }

        private Object[] getRangeKeys(String key_str) throws ParseException {
            String keys[];
            // multi-key partitioning
            if (key_str.contains(":")) {
                keys = key_str.split(":");
            } else {
                keys = new String[] { key_str };
            }

            Object[] row = new Object[keySchema.getColumnCount()];

            int col = 0;
            for (String key : keys) {
                assert (col < keySchema.getColumnCount());
                VoltType vt = keySchema.getColumnType(col);

                row[col] = parseValue(vt, key);
                col++;
            }

            for (; col < keySchema.getColumnCount(); col++) {
                VoltType vt = keySchema.getColumnType(col);
                Object obj = vt.getNullValue();
                row[col] = obj;
            }

            return row;
        }

        private Object parseValue(VoltType vt, String value) throws ParseException {
            if (value.isEmpty()) {
                return vt.getNullValue();
            }
            return VoltTypeUtil.getObjectFromString(vt, value);
        }

        private int getNonNullCols() {
            int non_null_cols = 0;
            for (int i = 0; i < min_incl.length; i++) {
                VoltType vt = keySchema.getColumnType(i);
                if (vt.getNullValue().equals(min_incl[i]) && vt.getNullValue().equals(max_excl[i])) {
                    break;
                }
                non_null_cols++;
            }
            return non_null_cols;
        }

        @Override
        public String toString() {
            String min_str = "";
            String max_str = "";
            for (int i = 0; i < this.min_incl.length; i++) {
                Object min = this.min_incl[i];
                Object max = this.max_excl[i];
                VoltType vt = this.keySchema.getColumnType(i);
                if (!vt.getNullValue().equals(min)) {
                    if (i != 0) {
                        min_str += ":";
                    }
                    min_str += min.toString();
                }
                if (!vt.getNullValue().equals(max)) {
                    if (i != 0) {
                        max_str += ":";
                    }
                    max_str += max.toString();
                }
            }
            return "[PartitionRange (" + this.catalog_table.getName().toLowerCase() + ") [" + min_str + "-" + max_str + ") p_id=" + this.partition + "]";
        }

        @Override
        public int compareTo(PartitionRange o) {
            if (cmp.compare(this.min_incl, o.min_incl) < 0) {
                return -1;
            } else if (cmp.compare(this.min_incl, o.min_incl) == 0) {
                return cmp.compare(this.max_excl, o.max_excl);
            } else {
                return 1;
            }
        }

        public boolean inRange(Object[] keys) {
            if (cmp.compare(min_incl, keys) <= 0 && (cmp.compare(max_excl, keys) > 0 || (cmp.compare(min_incl, max_excl) == 0 && cmp.compare(min_incl, keys) == 0))) {
                if (keys.length >= this.non_null_cols) {
                    return true;
                }
            }

            return false;
        }

        public boolean inRangeIgnoreNullCols(Object[] keys) {
        	Object[] keys_all = new Object[min_incl.length];
        	for (int j = 0; j < keys.length; j++) {
                keys_all[j] = keys[j];
            }
            for (int j = keys.length; j < keys_all.length; j++) {
                keys_all[j] = min_incl[j];
            }
            if (cmp.compare(min_incl, keys_all) <= 0 && (cmp.compare(max_excl, keys_all) > 0 || (cmp.compare(min_incl, max_excl) == 0 && cmp.compare(min_incl, keys_all) == 0))) {
                return true;
            }

            return false;
        }

        public VoltTable getMinInclTable() {
            VoltTable minInclTable = this.keySchema.clone(0);
            minInclTable.addRow(this.min_incl);
            return minInclTable;
        }

        public VoltTable getMaxExclTable() {
            VoltTable maxExclTable = this.keySchema.clone(0);
            maxExclTable.addRow(this.max_excl);
            return maxExclTable;
        }

        public Object[] getMinIncl() {
            return this.min_incl;
        }

        public Object[] getMaxExcl() {
            return this.max_excl;
        }

        public VoltTable getKeySchema() {
            return this.keySchema;
        }

        public Table getTable() {
            return this.catalog_table;
        }

        public int getPartition() {
            return this.partition;
        }

    }

    // ********End Containers **************************************/

    /**
     * Update the current partition phase (plan/epoch/etc)
     * 
     * @param new_phase
     * @return The delta between the plans or null if there is no change
     */
    public ReconfigurationPlan setPartitionPhase(String new_phase) {
        String old_phase = this.current_phase;
        if (old_phase != null && old_phase.equals(new_phase)) {
            return null;
        }
        if (this.partition_phase_map.containsKey(new_phase) == false) {
            throw new RuntimeException("Invalid Phase Name: " + new_phase + " phases: " + StringUtil.join(",", this.partition_phase_map.keySet()));
        }
        this.current_phase = new_phase;
        this.previous_phase = old_phase;
        this.incrementalPlan = null;
        
        try {
            if (old_phase == null) {
                return null;
            }
            return new ReconfigurationPlan(this.catalog_context, this.partition_phase_map.get(old_phase), this.partition_phase_map.get(new_phase));
        } catch (Exception ex) {
            LOG.error("Exception on setting partition phase", ex);
            LOG.error(String.format("Old phase: %s  New Phase: %s", old_phase, new_phase));
            throw new RuntimeException("Exception building Reconfiguration plan", ex);
        }

    }

    /**
     * @return the current partition phase/epoch
     */
    public String getCurrent_phase() {
        return this.current_phase;
    }

    /**
     * @return the current partition phase/epoch
     */
    public String getPreviousPhase_phase() {
        return this.previous_phase;
    }

    /*
     * (non-Javadoc)
     * @see org.json.JSONString#toJSONString()
     */
    @Override
    public String toJSONString() {        
        throw new NotImplementedException();
    }

    /*
     * (non-Javadoc)
     * @see edu.brown.utils.JSONSerializable#save(java.io.File)
     */
    @Override
    public void save(File output_path) throws IOException {        
        throw new NotImplementedException();

    }

    /*
     * (non-Javadoc)
     * @see edu.brown.utils.JSONSerializable#load(java.io.File,
     * org.voltdb.catalog.Database)
     */
    @Override
    public void load(File input_path, Database catalog_db) throws IOException {
        
        throw new NotImplementedException();

    }

    /*
     * (non-Javadoc)
     * @see edu.brown.utils.JSONSerializable#toJSON(org.json.JSONStringer)
     */
    @Override
    public void toJSON(JSONStringer stringer) throws JSONException {
        
        throw new NotImplementedException();

    }

    /*
     * (non-Javadoc)
     * @see edu.brown.utils.JSONSerializable#fromJSON(org.json.JSONObject,
     * org.voltdb.catalog.Database)
     */
    @Override
    public void fromJSON(JSONObject json_object, Database catalog_db) throws JSONException {
        
        throw new NotImplementedException();

    }

    @Override
    public ReconfigurationPlan setPartitionPlan(File partition_json_file) throws Exception {
        throw new NotImplementedException();
    }

    @Override
    public ReconfigurationPlan setPartitionPlan(JSONObject partition_json) {
        throw new NotImplementedException();
    }

    @Override
    public PartitionPhase getCurrentPlan() {

        return partition_phase_map.get(this.current_phase);
    }

    @Override
    public PartitionPhase getPreviousPlan() {
        return partition_phase_map.get(this.previous_phase);
    }

}

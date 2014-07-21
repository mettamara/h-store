package edu.brown.hashing;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.voltdb.CatalogContext;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.catalog.CatalogType;
import org.voltdb.catalog.Column;
import org.voltdb.catalog.ColumnRef;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Statement;
import org.voltdb.catalog.Table;
import org.voltdb.utils.VoltTableComparator;

import edu.brown.catalog.DependencyUtil;
import edu.brown.hashing.PlannedPartitions.PartitionKeyComparator;
import edu.brown.hashing.PlannedPartitions.PartitionPhase;
import edu.brown.hashing.PlannedPartitions.PartitionRange;
import edu.brown.hashing.PlannedPartitions.PartitionedTable;
import edu.brown.hashing.ReconfigurationPlan.ReconfigurationRange;
import edu.brown.hstore.reconfiguration.ReconfigurationUtil;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.mappings.ParameterMappingsSet;

public abstract class ExplicitPartitions {

	protected CatalogContext catalog_context;
	protected Set<String> plan_tables;
	protected Map<String, String> partitionedTablesByFK;
	protected Map<CatalogType, Table> catalog_to_table_map;
	protected Map<String, Column[]> table_partition_cols_map;
	protected String default_table = null;
	protected Map<String, List<String>> relatedTablesMap;
	protected ReconfigurationPlan reconfigurationPlan;
	protected PartitionPhase incrementalPlan;
	protected PartitionPhase previousIncrementalPlan;
	
	private static final Logger LOG = Logger.getLogger(ExplicitPartitions.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    public static final String TABLES = "tables";
    public static final String PARTITIONS = "partitions";
    protected static final String DEFAULT_TABLE = "default_table";
    
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
	
	protected ExplicitPartitions(CatalogContext catalog_context, JSONObject partition_json) throws Exception {
		this.catalog_context = catalog_context;
        this.catalog_to_table_map = new HashMap<>();
        this.table_partition_cols_map = new HashMap<>();
        this.plan_tables = null;
        this.relatedTablesMap = new HashMap<>();
        this.reconfigurationPlan = null;
        Set<String> partitionedTables = getExplicitPartitionedTables(partition_json);
        // TODO find catalogContext.getParameter mapping to find
        // statement_column
        // from project mapping (ae)
        assert partition_json.has(DEFAULT_TABLE) : "default_table missing from planned partition json";
        this.default_table = partition_json.getString(DEFAULT_TABLE);
        for (Table table : catalog_context.getDataTables()) {
            if (table.getIsreplicated()) continue;
        	
        	String tableName = table.getName().toLowerCase();
          
            Column[] cols = new Column[table.getPartitioncolumns().size()];
            for(ColumnRef colRef : table.getPartitioncolumns().values()) {
            	cols[colRef.getIndex()] = colRef.getColumn();
        	}
            
            // partition columns may not have been set
            Column partitionCol;
            if (cols.length == 0) {
            	partitionCol = table.getPartitioncolumn();
            	if (partitionCol != null) {
            		table_partition_cols_map.put(tableName, new Column[]{partitionCol});
            	}
            }
            else {
            	partitionCol = cols[0];
            	table_partition_cols_map.put(tableName, cols);
            }
            if (partitionCol == null) {
                LOG.info(String.format("Partition col for table %s is null. Skipping", tableName));
            } else {
                LOG.info(String.format("Adding table:%s partitionCol:%s %s", tableName, partitionCol, VoltType.get(partitionCol.getType())));
                this.catalog_to_table_map.put(partitionCol, table);
            }
        }

        for (Procedure proc : catalog_context.procedures) {
            if (!proc.getSystemproc()) {
            	Column[] cols = new Column[proc.getPartitioncolumns().size()];
                for(ColumnRef colRef : proc.getPartitioncolumns().values()) {
                	cols[colRef.getIndex()] = colRef.getColumn();
            	}
                
                // partition columns may not have been set
                Column partitionCol;
                if (cols.length == 0) {
                	partitionCol = proc.getPartitioncolumn();
                }
                else {
                	partitionCol = cols[0];
                }
            	
                String table_name = null;
                Table table = this.catalog_to_table_map.get(partitionCol);
                if(table != null) {
                	table_name = table.getName().toLowerCase();
                }
                if ((table_name == null) || (table_name.equals("null")) || (table_name.trim().length() == 0)) {
                    LOG.info(String.format("Using default table %s for procedure: %s ", this.default_table, proc.toString()));
                    table_name = this.default_table;
                    table = this.catalog_context.getTableByName(this.default_table);
                } else {
                    LOG.info(table_name + " adding procedure: " + proc.toString());
                }
                this.catalog_to_table_map.put(proc, table);
                for (Statement statement : proc.getStatements()) {
                    LOG.debug(table_name + " adding statement: " + statement.toString());

                    this.catalog_to_table_map.put(statement, table);
                }

            }
        }
        // We need to track which tables are partitioned on another table in
        // order to generate the reconfiguration ranges for those tables,
        // because they are not explicitly partitioned in the plan.
        DependencyUtil dependUtil = DependencyUtil.singleton(catalog_context.database);
        partitionedTablesByFK = new HashMap<>();

        for (Table table : catalog_context.getDataTables()) {
            String tableName = table.getName().toLowerCase();
            // Making the assumption that the same tables are in all plans TODO
            // verify this
            if (partitionedTables.contains(tableName) == false) {

                Column[] partitionCols = this.table_partition_cols_map.get(tableName);
                if (partitionCols == null) {
                    LOG.info(tableName + " is not partitioned and has no partition column. skipping");
                    continue;
                } else {
                    LOG.info(tableName + " is not explicitly partitioned.");
                }

                List<Column> depCols = dependUtil.getAncestors(partitionCols[0]);
                List<Table> parentCandidates = new ArrayList<>();
                List<Table> prevParentCandidates = new ArrayList<>();
                for (Column c : depCols) {
                    CatalogType p = c.getParent();
                    if (p instanceof Table) {
                        // if in table then map to it
                        String relatedTblName = p.getName().toLowerCase();
                        Table relatedTbl = (Table) p;
                        LOG.info(String.format("Table %s is related to %s",tableName,relatedTblName));
                        if (partitionedTables.contains(relatedTblName) && 
                        		this.table_partition_cols_map.get(relatedTblName) != null &&
                        		this.table_partition_cols_map.get(relatedTblName)[0].equals(c)) {
                            parentCandidates.add(relatedTbl);
                        }
                    }
                }
                
                // find the parent with the greatest number of partition columns in common
                for(int i = 1; i < partitionCols.length; i++) {
                	if(parentCandidates.size() != 0) {
                		prevParentCandidates = parentCandidates;
                		parentCandidates = new ArrayList<>();
                	} else {
                		break;
                	}
                	
                	depCols = dependUtil.getAncestors(partitionCols[i]);
                	for (Column c : depCols) {
                        CatalogType p = c.getParent();
                        if (p instanceof Table) {
                        	String relatedTblName = p.getName().toLowerCase();
                            Table relatedTbl = (Table) p;
                            if (prevParentCandidates.contains(relatedTbl) && 
                            		this.table_partition_cols_map.get(relatedTblName) != null &&
                            		this.table_partition_cols_map.get(relatedTblName)[i].equals(c)) {
                                parentCandidates.add(relatedTbl);
                            }
                        }
                    }
                }
                
                Table parentTbl = null;
                if(parentCandidates.size() != 0) {
                	parentTbl = parentCandidates.get(0);
                } else if(prevParentCandidates.size() != 0) {
                	parentTbl = prevParentCandidates.get(0);
                } else {
                	throw new RuntimeException("No partitioned relationship found for table : " + tableName + " partitioned:" + partitionedTables.toString());
                }
                
                String parentTblName = parentTbl.getName().toLowerCase();
                LOG.info("parent partitioned table for " + tableName + ": " + parentTbl + " : " + parentTblName);
                partitionedTablesByFK.put(tableName, parentTblName);
                catalog_to_table_map.put(table, parentTbl);
                catalog_to_table_map.put(partitionCols[0], table);
                
                if(!relatedTablesMap.containsKey(parentTblName)) {
                	relatedTablesMap.put(parentTblName, new ArrayList<String>());
                	relatedTablesMap.get(parentTblName).add(parentTblName);
                }
                relatedTablesMap.get(parentTblName).add(tableName);
            }
        }
	}
	
    /**
     * Get the explicit partitioned tables and ensure that the old plan and the new plan have the
     * same set of tables
     * 
     * @param partition_json
     * @return the set of tables in the partition plan
     */
    public abstract Set<String> getExplicitPartitionedTables(JSONObject partition_json);

    /**
     * Get the partition id for a given table and partition id/key
     * 
     * @param table_name
     * @param id
     * @return the partition id, or -1 / null partition if the id/key is not
     *         found in the plan
     * @throws Exception
     */
    public abstract int getPartitionId(String table_name, List<Object> ids) throws Exception;
    
    public int getPartitionId(String table_name, Object[] ids) throws Exception {
    	return getPartitionId(table_name, Arrays.asList(ids));
    }
    
    public int getPartitionId(List<CatalogType> catalogs, List<Object> ids) throws Exception {
    	String table_name = this.catalog_to_table_map.get(catalogs.get(0)).getName().toLowerCase();
        return this.getPartitionId(table_name, ids);
    }

    public String getTableName(CatalogType catalog) {
        return this.catalog_to_table_map.get(catalog).getName().toLowerCase();
    }
    
    public String getTableName(List<CatalogType> catalog) {
        return this.catalog_to_table_map.get(catalog.get(0)).getName().toLowerCase();
    }

    public Table getTable(List<CatalogType> catalog) {
        return this.catalog_to_table_map.get(catalog.get(0));
    }

    /**
     * Get the previous partition id for a given table and partition id/key
     * 
     * @param table_name
     * @param id
     * @return the partition id, or -1 / null partition if the id/key is not
     *         found in the plan OR if there is no previous plan
     * @throws Exception
     */
    
    public int getPreviousPartitionId(List<CatalogType> catalogs, List<Object> ids) throws Exception {
        String table_name = this.catalog_to_table_map.get(catalogs.get(0)).getName().toLowerCase();
        return this.getPreviousPartitionId(table_name, ids);
    }
    
    public abstract int getPreviousPartitionId(String table_name, List<Object> ids) throws Exception;

    /**
     * get all partitions that may contain the key
     * @param table_name
     * @param ids
     * @return
     * @throws Exception
     */
    public abstract List<Integer> getAllPartitionIds(String table_name, List<Object> ids) throws Exception;
    
    public abstract List<Integer> getAllPreviousPartitionIds(String table_name, List<Object> ids) throws Exception;
    
    public abstract ReconfigurationPlan setPartitionPlan(File partition_json_file) throws Exception;

    public abstract ReconfigurationPlan setPartitionPhase(String new_phase);
    /**
     * Update the current partition plan
     * 
     * @param partition_json
     * @return The delta between the plans or null if there is no change
     */
    public abstract ReconfigurationPlan setPartitionPlan(JSONObject partition_json);

    /**
     * @return the current partition plan
     */
    public abstract PartitionPhase getCurrentPlan();

    /**
     * @return the previous partition plan
     */
    public abstract PartitionPhase getPreviousPlan();

    /*
     * (non-Javadoc)
     * @see org.json.JSONString#toJSONString()
     */
    public abstract String toJSONString();

    /*
     * (non-Javadoc)
     * @see edu.brown.utils.JSONSerializable#save(java.io.File)
     */
    public abstract void save(File output_path) throws IOException;

    /*
     * (non-Javadoc)
     * @see edu.brown.utils.JSONSerializable#load(java.io.File,
     * org.voltdb.catalog.Database)
     */
    public abstract void load(File input_path, Database catalog_db) throws IOException;

    /*
     * (non-Javadoc)
     * @see edu.brown.utils.JSONSerializable#toJSON(org.json.JSONStringer)
     */
    public abstract void toJSON(JSONStringer stringer) throws JSONException;

    /*
     * (non-Javadoc)
     * @see edu.brown.utils.JSONSerializable#fromJSON(org.json.JSONObject,
     * org.voltdb.catalog.Database)
     */
    public abstract void fromJSON(JSONObject json_object, Database catalog_db) throws JSONException;

    public List<String> getRelatedTables(String table_name) {
        if(relatedTablesMap.containsKey(table_name)) {
        	return relatedTablesMap.get(table_name);
        } else if(this.partitionedTablesByFK.containsKey(table_name)) {
        	return relatedTablesMap.get(this.partitionedTablesByFK.get(table_name));
        }
        
        return null;
    }
    
    public CatalogContext getCatalogContext() {
    	return this.catalog_context;
    }
    
    public void setReconfigurationPlan(ReconfigurationPlan reconfigurationPlan) {
	if(reconfigurationPlan == null) {
	    this.reconfigurationPlan = null;
	    return;
	}

	if(this.reconfigurationPlan != null && reconfigurationPlan.equals(this.reconfigurationPlan)) {
	    LOG.debug("plan already set");
	    return;
	}

   	this.reconfigurationPlan = reconfigurationPlan;
    	if(this.incrementalPlan == null) {
    		this.previousIncrementalPlan = this.getPreviousPlan();
    		this.incrementalPlan = this.getPreviousPlan();
    	}
	assert (this.reconfigurationPlan.range_map != null) : "Null reconfiguration range map";

    	List<PartitionRange> newRanges = new ArrayList<PartitionRange>();
	for(Map.Entry<String, PartitionedTable> tables : this.incrementalPlan.tables_map.entrySet()) {
	    String table_name = tables.getKey();
    		Iterator<PartitionRange> partitionRanges = tables.getValue().getRanges().iterator();
		Iterator<ReconfigurationRange> reconfigRanges;
		if(this.reconfigurationPlan.range_map.get(table_name) != null) {
		    reconfigRanges = this.reconfigurationPlan.range_map.get(table_name).iterator();
		} else {
		    newRanges.addAll(tables.getValue().getRanges());
		    continue;
		}
 		
    		ReconfigurationRange reconfigRange = reconfigRanges.next();
    		PartitionKeyComparator cmp = new PartitionKeyComparator();

    		Object[] max_old_accounted_for = null;

    		PartitionRange partitionRange = null;
    		// Iterate through the old partition ranges.
    		// Only move to the next old rang
    		while (partitionRanges.hasNext() || (max_old_accounted_for != null && (cmp.compare(max_old_accounted_for, partitionRange.getMaxExcl())) != 0 )) {
    			// only move to the next element if first time, or all of the previous
    			// range has been accounted for
    			if (partitionRange == null || cmp.compare(partitionRange.getMaxExcl(), max_old_accounted_for) <= 0) {
    				partitionRange = partitionRanges.next();
    			}

    			if (max_old_accounted_for == null) {
    				// We have not accounted for any range yet
    				max_old_accounted_for = partitionRange.getMinIncl();
    			}
   			if(cmp.compare(max_old_accounted_for, reconfigRange.getMinIncl().get(0)) < 0) {
    				if(cmp.compare(partitionRange.getMaxExcl(), reconfigRange.getMinIncl().get(0)) <= 0) {
    					// the end of the range is not moving
        				newRanges = addAndMergeRanges(newRanges, new PartitionRange(partitionRange.getTable(), partitionRange.getPartition(), max_old_accounted_for, partitionRange.getMaxExcl()));
    					max_old_accounted_for = partitionRange.getMaxExcl();
    				} else {
    					// the beginning/middle of the range is not moving
				    newRanges = addAndMergeRanges(newRanges, new PartitionRange(partitionRange.getTable(), partitionRange.getPartition(), max_old_accounted_for, reconfigRange.getMinIncl().get(0)));
    					max_old_accounted_for = reconfigRange.getMinIncl().get(0);
    				}
    			} else if(cmp.compare(max_old_accounted_for, reconfigRange.getMaxExcl().get(0)) < 0) {
			    assert (partitionRange.getPartition() == reconfigRange.getOldPartition()) : "partitions do not match: <" + partitionRange.getPartition() + "> != <" + reconfigRange.getOldPartition() + ">";
					if (cmp.compare(partitionRange.getMaxExcl(), reconfigRange.getMaxExcl().get(0)) < 0) {
						// the end of the range is moving
	    				newRanges = addAndMergeRanges(newRanges, new PartitionRange(partitionRange.getTable(), reconfigRange.getNewPartition(), max_old_accounted_for, partitionRange.getMaxExcl()));
    					max_old_accounted_for = partitionRange.getMaxExcl();
    				} else {
    					// the beginning/middle of the range is moving
        				newRanges = addAndMergeRanges(newRanges, new PartitionRange(partitionRange.getTable(), reconfigRange.getNewPartition(), max_old_accounted_for, reconfigRange.getMaxExcl().get(0)));
    					max_old_accounted_for = reconfigRange.getMaxExcl().get(0);
    					if(reconfigRanges.hasNext()) {
    						reconfigRange = reconfigRanges.next();  
    					}
    				}
    			} else {
    				if(reconfigRanges.hasNext()) {
    					reconfigRange = reconfigRanges.next();  
    				}
				else {
        				newRanges = addAndMergeRanges(newRanges, new PartitionRange(partitionRange.getTable(), partitionRange.getPartition(), max_old_accounted_for, partitionRange.getMaxExcl()));
    					max_old_accounted_for = partitionRange.getMaxExcl();
    				    
				}
    			}

            }
    	}

	LOG.info("New incremental plan ranges: " + newRanges.toString());
		
		try {
			this.previousIncrementalPlan = this.incrementalPlan;
			this.incrementalPlan = new PartitionPhase(this.incrementalPlan.catalog_context, newRanges, this.partitionedTablesByFK);
		} catch (Exception e) {
			LOG.error(e);
			throw new RuntimeException(e);
		}

    }
    
    public List<PartitionRange> addAndMergeRanges(List<PartitionRange> ranges, PartitionRange newRange) {
    	ArrayList<PartitionRange> newRanges = new ArrayList<>();
    	PartitionKeyComparator cmp = new PartitionKeyComparator();
    	
    	for(PartitionRange range : ranges) {
    		if(range.getTable().equals(newRange.getTable()) && range.getPartition() == newRange.getPartition()) {
    			if(cmp.compare(newRange.getMinIncl(), range.getMaxExcl()) == 0) {
    				newRange = new PartitionRange(newRange.getTable(), newRange.getPartition(), range.getMinIncl(), newRange.getMaxExcl());
    			} else if (cmp.compare(newRange.getMaxExcl(), range.getMinIncl()) == 0) {
    				newRange = new PartitionRange(newRange.getTable(), newRange.getPartition(), newRange.getMinIncl(), range.getMaxExcl());
    			} else {
    				newRanges.add(range);
    			}
    		} else {
    			newRanges.add(range);
    		}
    	}
    	
    	newRanges.add(newRange);
    	
    	return newRanges;
    }

}
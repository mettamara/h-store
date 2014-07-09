/**
 * 
 */
package edu.brown.hstore;

import org.apache.log4j.Logger;
import org.junit.Before;
import org.junit.Test;
import org.voltdb.VoltTable;
import org.voltdb.benchmark.tpcc.TPCCConstants;
import org.voltdb.catalog.Site;
import org.voltdb.catalog.Table;
import org.voltdb.client.Client;
import org.voltdb.jni.ExecutionEngine;
import org.voltdb.jni.ExecutionEngineJNI;
import org.voltdb.utils.Pair;
import org.voltdb.utils.VoltTableUtil;

import edu.brown.BaseTestCase;
import edu.brown.catalog.CatalogUtil;
import edu.brown.designer.MemoryEstimator;
import edu.brown.hashing.ReconfigurationPlan.ReconfigurationRange;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.reconfiguration.ReconfigurationUtil;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.ProjectType;

/**
 * @author aelmore
 *
 */
public class TestReconfigurationEEPerformance extends BaseTestCase {

    private static final Logger LOG = Logger.getLogger(TestReconfigurationEEPerformance.class);
    private static final int NUM_PARTITIONS = 1;
    private static final long NUM_TUPLES = 100;
    private static final String CUSTOMER_TABLE_NAME = TPCCConstants.TABLENAME_CUSTOMER;
    private static final String NEW_ORDER_TABLE_NAME = TPCCConstants.TABLENAME_NEW_ORDER;
    private static final int DEFAULT_LIMIT = ExecutionEngineJNI.DEFAULT_EXTRACT_LIMIT_BYTES;
    
    private HStoreSite hstore_site;
    private HStoreConf hstore_conf;
    private Client client;
    
    private PartitionExecutor executor;
    private ExecutionEngine ee;
    private Table customer_tbl;
    private Table neworder_tbl;
    private Table orderline;
    private int neworder_p_index;
    private int cust_p_index;
    private int ordline_ind;
    private int undo=1;
    
    //private int ycsbTableId(Catalog catalog) {
    //    return catalog.getClusters().get("cluster").getDatabases().get("database").getTables().get(TARGET_TABLE).getRelativeIndex();
    // }
    @Before
    public void setUp() throws Exception {
        super.setUp(ProjectType.TPCC);
        
        initializeCatalog(1, 1, NUM_PARTITIONS);
        
        // Just make sure that the Table has the evictable flag set to true
        this.customer_tbl = getTable(CUSTOMER_TABLE_NAME);
        this.cust_p_index = this.customer_tbl.getPartitioncolumn().getIndex();
        this.neworder_tbl = getTable(NEW_ORDER_TABLE_NAME);
        this.neworder_p_index = this.neworder_tbl.getPartitioncolumn().getIndex();
        this.orderline = getTable(TPCCConstants.TABLENAME_ORDER_LINE);
        this.ordline_ind = this.orderline.getPartitioncolumn().getIndex();
        
        Site catalog_site = CollectionUtil.first(CatalogUtil.getCluster(catalog).getSites());
        hstore_conf = HStoreConf.singleton();
        
        hstore_conf.site.coordinator_sync_time = false;
        hstore_conf.global.reconfiguration_enable = true;
        hstore_conf.global.hasher_class = "edu.brown.hashing.PlannedHasher";
        hstore_conf.global.hasher_plan = "scripts/reconfiguration/plans/tpcc-size1-2.json";
        //hstore_conf.global.hasher_plan = PlannedHasher.YCSB_TEST;        
        hstore_conf.site.status_enable = false;

        
        this.hstore_site = createHStoreSite(catalog_site, hstore_conf);
        this.executor = hstore_site.getPartitionExecutor(0);
        
        //RegressionSuiteUtil.initializeTPCCDatabase(this.catalogContext,this.client);
        assertNotNull(this.executor);
        this.ee = executor.getExecutionEngine();
        assertNotNull(this.executor);
        
        this.client = createClient();
        
        
    }
    
    @Override
    protected void tearDown() throws Exception {
        if (this.client != null) this.client.close();
        if (this.hstore_site != null) this.hstore_site.shutdown();
    }
    
    private void loadTPCCData(Long numTuples, Table table, int widIndex, int wid ) throws Exception {
        // Load in a bunch of dummy data for this table
        VoltTable vt = CatalogUtil.getVoltTable(table);
        assertNotNull(vt);
        for (int i = 0; i < numTuples; i++) {
            Object row[] = VoltTableUtil.getRandomRow(table);
            row[0] = i;
            row[widIndex] = wid;
            vt.addRow(row);
        } // FOR
        this.executor.loadTable(1000L, table, vt, false);

    }
    
    @Test
    public void tesxtNewOrder() throws Exception {
        long recs = 100000;
        int warehouses = 5;
        for (int i=0; i < warehouses; i++){
            LOG.info("Loading order lines: " + recs);
            loadTPCCData(recs, neworder_tbl, this.neworder_p_index, i);
        }

        int EXTRACT_LIMIT = 200000;
        ((ExecutionEngineJNI)(this.ee)).DEFAULT_EXTRACT_LIMIT_BYTES = EXTRACT_LIMIT;


        ReconfigurationRange range; 
        VoltTable extractTable;
        
        int wid =2;
        range = ReconfigurationUtil.getReconfigurationRange(neworder_tbl, new Long[][]{{ new Long(wid) }}, new Long[][]{{ new Long(wid+1) }}, 1, 2);
        extractTable = ReconfigurationUtil.getExtractVoltTable(range);   
        
        
        long tupleBytes = MemoryEstimator.estimateTupleSize(this.neworder_tbl);
        int tuplesInChunk = (int)(EXTRACT_LIMIT / tupleBytes);
        int expectedChunks = ((int)(NUM_TUPLES * 10)/tuplesInChunk);
        int resCount = 0;
        int chunks = 0;
        Pair<VoltTable,Boolean> resTable = null;
        long start,end;
        int count=0;
        do {
            start = System.currentTimeMillis();
            resTable = 
                    this.ee.extractTable(this.neworder_tbl, this.neworder_tbl.getRelativeIndex(), extractTable, 1, 1, undo++, -1, 1);
            end = System.currentTimeMillis();
            LOG.info(String.format("Rows:%s Size:%s Time taken: %s",resTable.getFirst().getRowCount(),(resTable.getFirst().getRowCount()*resTable.getFirst().getRowSize()),(end-start)));
            resCount += resTable.getFirst().getRowCount();
            //LOG.info("Total RowCount :" +resCount);
            
            chunks++;
            count++;
            if (count %10 == 0){
                //loadTPCCData((long)(Math.random()*30), orderline, this.ordline_ind, wid);
            }
        } while (resTable != null && resTable.getSecond());
        //System.out.println("Counts : " + count);
        assertEquals(recs, resCount);
       
    }
    
    
    @Test
    public void testOrderLine() throws Exception {
        long recs = 300000;
        int warehouses = 1;
        for (int i=0; i < warehouses; i++){
            LOG.info("Loading order lines: " + recs);
            loadTPCCData(recs, orderline, this.ordline_ind, i);
        }

        int EXTRACT_LIMIT = 30000*1024;
        ((ExecutionEngineJNI)(this.ee)).DEFAULT_EXTRACT_LIMIT_BYTES = EXTRACT_LIMIT;


        ReconfigurationRange range; 
        VoltTable extractTable;
        
        int wid =0;
        range = ReconfigurationUtil.getReconfigurationRange(orderline, new Long[][]{{ new Long(wid) }}, new Long[][]{{ new Long(wid+1) }}, 1, 2);
        extractTable = ReconfigurationUtil.getExtractVoltTable(range);   
        
        
        long tupleBytes = MemoryEstimator.estimateTupleSize(this.orderline);
        int tuplesInChunk = (int)(EXTRACT_LIMIT / tupleBytes);
        int expectedChunks = ((int)(NUM_TUPLES * 10)/tuplesInChunk);
        int resCount = 0;
        int chunks = 0;
        Pair<VoltTable,Boolean> resTable = null;
        long start,end;
        int count=0;
        do {
            start = System.currentTimeMillis();
            resTable = 
                    this.ee.extractTable(this.orderline, this.orderline.getRelativeIndex(), extractTable, 1, 1, undo++, -1, 1);
            end = System.currentTimeMillis();
            LOG.info(String.format("Rows:%s Size:%s Time taken: %s",resTable.getFirst().getRowCount(),(resTable.getFirst().getRowCount()*resTable.getFirst().getRowSize()),(end-start)));
            resCount += resTable.getFirst().getRowCount();
            if (resTable.getFirst().getRowCount() != (EXTRACT_LIMIT/1024)){
                LOG.error("****************************************");
                LOG.error("ROWS: " + resTable.getFirst().getRowCount());
                LOG.error("****************************************");
            }
            //LOG.info("Total RowCount :" +resCount);
            
            chunks++;
            count++;
            if (count %10 == 0){
                //loadTPCCData((long)(Math.random()*30), orderline, this.ordline_ind, wid);
            }
            LOG.info("  ");
        } while (resTable != null && resTable.getSecond());
        System.out.println("Counts : " + count);
        assertEquals(recs, resCount);
       
    }

    
  
    
    
}

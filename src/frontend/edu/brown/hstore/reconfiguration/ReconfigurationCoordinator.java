package edu.brown.hstore.reconfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang.NotImplementedException;
import org.apache.log4j.Logger;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.exceptions.ReconfigurationException.ExceptionTypes;
import org.voltdb.messaging.FastDeserializer;
import org.voltdb.messaging.FastSerializer;

import com.google.protobuf.ByteString;
import com.google.protobuf.RpcCallback;

import edu.brown.hashing.PlannedHasher;
import edu.brown.hashing.PlannedPartitions;
import edu.brown.hashing.ReconfigurationPlan;
import edu.brown.hashing.ReconfigurationPlan.ReconfigurationRange;
import edu.brown.hstore.HStoreSite;
import edu.brown.hstore.Hstoreservice.DataTransferRequest;
import edu.brown.hstore.Hstoreservice.DataTransferResponse;
import edu.brown.hstore.Hstoreservice.HStoreService;
import edu.brown.hstore.Hstoreservice.LivePullRequest;
import edu.brown.hstore.Hstoreservice.LivePullResponse;
import edu.brown.hstore.Hstoreservice.ReconfigurationControlRequest;
import edu.brown.hstore.Hstoreservice.ReconfigurationControlType;
import edu.brown.hstore.Hstoreservice.ReconfigurationRequest;
import edu.brown.hstore.Hstoreservice.ReconfigurationResponse;
import edu.brown.hstore.PartitionExecutor;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.reconfiguration.ReconfigurationConstants.ReconfigurationProtocols;
import edu.brown.interfaces.Shutdownable;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.brown.profilers.ReconfigurationProfiler;
import edu.brown.protorpc.ProtoRpcController;
import edu.brown.utils.FileUtil;

/**
 * @author vaibhav : Reconfiguration Coordinator at each site, responsible for
 *         maintaining reconfiguration state and sending communication messages
 */
/**
 * @author aelmore
 */
public class ReconfigurationCoordinator implements Shutdownable {
    private static final Logger LOG = Logger.getLogger(ReconfigurationCoordinator.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());

    // Cached list of local executors
    private List<PartitionExecutor> local_executors;
    static {
        LoggerUtil.setupLogging();
        LoggerUtil.attachObserver(LOG, debug, trace);
    }

    public enum ReconfigurationState {
        NORMAL, BEGIN, PREPARE, DATA_TRANSFER, BULK_TRANSFER, END
    }

    private HStoreSite hstore_site;

    private ReconfigurationState reconfigurationState;
    // Hostname of the reconfiguration leader site
    private Integer reconfigurationLeader;
    private AtomicBoolean reconfigurationInProgress;
    private ReconfigurationPlan currentReconfigurationPlan;
    private ReconfigurationProtocols reconfigurationProtocol;
    private String currentPartitionPlan;
    private int localSiteId;
    private HStoreService channels[];
    private Set<Integer> destinationsReady;
    private int destinationSize;

    // used for generating unique IDs
    private int rcRequestId;
    // Map of partitions in a reconfiguration and their state. No entry is not
    // in reconfiguration;
    private Map<Integer, ReconfigurationState> partitionStates;
    private Map<Integer, ReconfigurationState> initialPartitionStates;

    // map of requests a PE is blocked on
    private Map<Integer, Semaphore> blockedRequests;
    private PlannedPartitions planned_partitions;
    public ReconfigurationProfiler profilers[];
    private HStoreConf hstore_conf;
    
    private Set<Integer> reconfigurationDonePartitionIds;
    private Set<Integer> reconfigurationDoneSites;
    private int num_of_sites;
    private int num_sites_complete;
    private Set<Integer> sites_complete;

    public ReconfigurationCoordinator(HStoreSite hstore_site, HStoreConf hstore_conf) {        
        this.reconfigurationLeader = -1;
        this.reconfigurationInProgress = new AtomicBoolean(false);
        this.currentReconfigurationPlan = null;
        this.reconfigurationState = ReconfigurationState.NORMAL;
        this.hstore_site = hstore_site;
        this.local_executors = new ArrayList<>();
        this.channels = hstore_site.getCoordinator().getChannels();
        this.partitionStates = new ConcurrentHashMap<Integer, ReconfigurationCoordinator.ReconfigurationState>();
        this.hstore_conf = hstore_conf;
        
        int num_partitions = hstore_site.getCatalogContext().numberOfPartitions;
        this.num_of_sites = hstore_site.getCatalogContext().numberOfSites;
        if(hstore_conf.site.reconfiguration_profiling) 
            this.profilers = new ReconfigurationProfiler[num_partitions];
        for (int p_id : hstore_site.getLocalPartitionIds().values()) {
            this.local_executors.add(hstore_site.getPartitionExecutor(p_id));
            this.partitionStates.put(p_id, ReconfigurationState.NORMAL);
            if(hstore_conf.site.reconfiguration_profiling) 
                this.profilers[p_id] = new ReconfigurationProfiler();
        }
        this.initialPartitionStates = Collections.unmodifiableMap(partitionStates);
        this.localSiteId = hstore_site.getSiteId();
        this.blockedRequests = new ConcurrentHashMap<>();
        this.rcRequestId = 1;
        this.reconfigurationDonePartitionIds = new HashSet<Integer>();
        reconfigurationDoneSites = new HashSet<Integer>();
    }

    /**
     * Initialize a reconfiguration. May be called by multiple PEs, so first
     * request initializes and caches plan. Additional requests will be given
     * cached plan.
     * 
     * @param leaderId
     * @param reconfigurationProtocol
     * @param partitionPlan
     * @param partitionId
     * @return the reconfiguration plan or null if plan already set
     */
    public ReconfigurationPlan initReconfiguration(Integer leaderId, ReconfigurationProtocols reconfigurationProtocol, String partitionPlan, int partitionId) {

        // TODO ae start timing
        if (this.reconfigurationInProgress.get() == false && partitionPlan == this.currentPartitionPlan) {
            LOG.info("Ignoring initReconfiguration request. Requested plan is already set");
            return null;
        }
        if (reconfigurationProtocol == ReconfigurationProtocols.STOPCOPY) {
            if (partitionId != -1) {
                this.partitionStates.put(partitionId, ReconfigurationState.DATA_TRANSFER);
                this.reconfigurationState = ReconfigurationState.DATA_TRANSFER;
            } else {
                String msg = "No PARTITION ID set on init for stop and copy";
                LOG.error(msg);
                throw new RuntimeException(msg);
            }
        } else if (reconfigurationProtocol == ReconfigurationProtocols.LIVEPULL) {

        } else {
            throw new NotImplementedException();
        }

        // We may have reconfiguration initialized by PEs so need to ensure
        // atomic
        if (this.reconfigurationInProgress.compareAndSet(false, true)) {
            LOG.info("Initializing reconfiguration. New reconfig plan.");
            if (this.hstore_site.getSiteId() == leaderId) {
                // TODO : Check if more leader logic is needed
                FileUtil.appendEventToFile(this.hstore_site.getSiteId() + " LEADER_RECONFIG_INIT");
                if (debug.val) {
                    LOG.debug("Setting site as reconfig leader");
                }
            } else {
            	FileUtil.appendEventToFile(this.hstore_site.getSiteId()+ " RECONFIG_INIT ");
            }
            this.reconfigurationLeader = leaderId;
            this.reconfigurationProtocol = reconfigurationProtocol;
            this.currentPartitionPlan = partitionPlan;
            PlannedHasher hasher = (PlannedHasher) this.hstore_site.getHasher();
            ReconfigurationPlan reconfig_plan;
            
            //Used by the leader to track the reconfiguration state of each partition and each site respectively 
            this.reconfigurationDonePartitionIds = new HashSet<Integer>();
            reconfigurationDoneSites = new HashSet<Integer>();
            
            try {
                // Find reconfig plan
                reconfig_plan = hasher.changePartitionPhase(partitionPlan);
                this.planned_partitions = hasher.getPlanned_partitions();
                if (reconfigurationProtocol == ReconfigurationProtocols.STOPCOPY) {
                    // Nothing to do for S&C. PE's directly notified by
                    // sysProcedure
                } else if (reconfigurationProtocol == ReconfigurationProtocols.LIVEPULL) {
                    if (reconfig_plan != null) {
                        if (this.hstore_site.getSiteId() == leaderId) {
                            this.num_sites_complete = 0;
                            this.sites_complete = new HashSet<Integer>();
                        }
                        for (PartitionExecutor executor : this.local_executors) {
                            executor.initReconfiguration(reconfig_plan, reconfigurationProtocol, ReconfigurationState.PREPARE, this.planned_partitions);
                            this.partitionStates.put(partitionId, ReconfigurationState.PREPARE);
                        }
                        // Notify leader that this node has been initialized /
                        // prepared
                        notifyReconfigLeader(ReconfigurationState.PREPARE);
                    } else {
                        LOG.info("No reconfig plan, nothing to do");
                    }
                } else {
                    throw new NotImplementedException();
                }
            } catch (Exception e) {
                LOG.error("Exception converting plan", e);
                throw new RuntimeException(e);
            }
            this.currentReconfigurationPlan = reconfig_plan;
            return reconfig_plan;
        } else {
            // If the reconfig plan is null, but we are in progress we should
            // re-attempt to get it;
            int tries = 0;
            int max_tries = 20;
            long sleep_time = 50;
            while (this.currentReconfigurationPlan == null && tries < max_tries) {
                try {
                    Thread.sleep(sleep_time);
                    tries++;
                } catch (InterruptedException e) {
                    LOG.error("Error sleeping", e);
                }
            }
            LOG.debug(String.format("Init reconfiguration returning existing plan %s", this.currentReconfigurationPlan));

            return this.currentReconfigurationPlan;
        }
    }

    /**
     * Notify leader of a state change
     * 
     * @param begin
     */
    private void notifyReconfigLeader(ReconfigurationState state) {
        assert (this.reconfigurationLeader != -1) : "No reconfiguration leader set";
        LOG.info("Notifying reconfiguration leader of state:" + state.toString());
        if (this.hstore_site.getSiteId() == this.reconfigurationLeader) {
            // This node is the leader
        } else {
            // TODO send state notification to leader
            
        }

    }

    /**
     * Function called by a PE when its active part of the reconfiguration is
     * complete
     * 
     * @param partitionId
     */
    public void finishReconfiguration(int partitionId) {
        if (this.reconfigurationProtocol == ReconfigurationProtocols.STOPCOPY) {
            this.partitionStates.remove(partitionId);

            if (allPartitionsFinished()) {
                
                LOG.info("Last PE finished reconfiguration");
                if(partitionId == this.reconfigurationLeader){
                    FileUtil.appendEventToFile(this.hstore_site.getSiteId() + " LEADER_RECONFIGURATION_"+ReconfigurationState.END);
                }
                resetReconfigurationInProgress();
            }
        } else if (this.reconfigurationProtocol == ReconfigurationProtocols.LIVEPULL){
            // send a message to the leader that the reconfiguration is done
            this.reconfigurationDonePartitionIds.add(partitionId);
            LOG.info(" ** Partition has finished : " + partitionId + " " + reconfigurationDonePartitionIds.size() + " / " + this.local_executors.size());
            
            //Check all the partitions are done
            if(this.reconfigurationDonePartitionIds.size() == this.local_executors.size()){
                // signal end of reconfiguration to leader
                signalEndReconfigurationLeader(this.localSiteId, partitionId);
            }
            
        }

    }
    
    /**
     * Signal the end of reconfiguration for a siteId to the leader
     * @param siteId
     */
    public void signalEndReconfigurationLeader(int siteId, int callingPartition) {
        LOG.info("Signalling endReconfigToLeader : " + siteId );
        ReconfigurationControlRequest leaderCallback = ReconfigurationControlRequest.newBuilder().setSrcPartition(callingPartition)
                .setDestPartition(this.reconfigurationLeader)
                .setReconfigControlType(ReconfigurationControlType.RECONFIGURATION_DONE)
                .setReceiverSite(this.reconfigurationLeader)
                .setMessageIdentifier(-1).
                setSenderSite(localSiteId).build();
                
       
        //TODO : Can we get away with creating an instance each time
        ProtoRpcController controller = new ProtoRpcController();
        int destinationId = this.hstore_site.getCatalogContext().getSiteIdForPartitionId(this.reconfigurationLeader);

        if (this.channels == null){
        	LOG.error("Communication Channels are null. Can't send "+ReconfigurationControlType.RECONFIGURATION_DONE +" message");
        }
      
        if (destinationId == this.localSiteId){
            leaderReconfigurationComplete(this.localSiteId);
        } else{
        	if (this.channels[destinationId] == null){
        		  LOG.error("Reconfig Leader Channel is null. " +  destinationId + " : " + this.channels);
        	} else{
        		this.channels[destinationId].reconfigurationControlMsg(controller, leaderCallback, null);
        		FileUtil.appendEventToFile(this.hstore_site.getSiteId()+" RECONFIGURATION_" + ReconfigurationState.END.toString());
        	}
        }
    }
    
    private void sendReconfigEndAcknowledgementToAllSites(){
    	//Reconfiguration leader sends ack that reconfiguration has been done
    	for(int i = 0;i < num_of_sites;i++){
    		int dummySrcpartition = 0;
    		int dummyDestPartition = 0;
        	ProtoRpcController controller = new ProtoRpcController();
        	ReconfigurationControlRequest reconfigEndAck = ReconfigurationControlRequest.newBuilder()
                    .setSenderSite(this.reconfigurationLeader).setDestPartition(dummyDestPartition)
                    .setSrcPartition(dummySrcpartition)
                    .setReconfigControlType(ReconfigurationControlType.RECONFIGURATION_DONE_RECEIVED)
                    .setReceiverSite(i)
                    .setMessageIdentifier(-1).build();
        	if(i != localSiteId) {
        		this.channels[i].reconfigurationControlMsg(controller, reconfigEndAck, null);
        	} else {
        		markReconfigurationIsDoneLocally();
        	}
        	
        }
    }
    
    private void leaderReconfigurationComplete(int siteId){
        LOG.info("Site reconfiguration complete for the leader : " + siteId);
        reconfigurationDoneSites.add(siteId);
        LOG.info("Reconfiguration is done for the leader");
        if(reconfigurationDoneSites.size() == num_of_sites){
            LOG.info("All sites have reported that reconfiguration is complete "); 
            FileUtil.appendEventToFile(this.hstore_site.getSiteId()+" LEADER_RECONFIGURATION_" + ReconfigurationState.END.toString());
            LOG.info("Sending a message to notify all sites that reconfiguration has ended");
            sendReconfigEndAcknowledgementToAllSites();
        }
    }
    
    /**
     * Called for the reconfiguration leader to signify that
     * @siteId is done with reconfiguration
     * @param siteId
     */
    public void markReconfigurationIsDone(int siteId) {
        if(this.localSiteId != this.reconfigurationLeader){
            LOG.error("This message should only go to reconfiguration leader");
            return;
        } 
        LOG.info("Got a message to end Reconfiguration from site Id :"+ siteId);
        this.reconfigurationDoneSites.add(siteId);
        
        if(reconfigurationDoneSites.size() == this.hstore_site.getCatalogContext().numberOfSites){
            // Now the leader can be sure that the reconfiguration is done as all sites have checked in
            LOG.info("All sites have reported reconfiguration is complete");        
            LOG.info("Sending a message to notify all sites that reconfiguration has ended");
     
            sendReconfigEndAcknowledgementToAllSites();
            FileUtil.appendEventToFile(this.hstore_site.getSiteId() + " LEADER_RECONFIGURATION_" + ReconfigurationState.END.toString());
        }
    }
    
    public void markReconfigurationIsDoneLocally() {
    	LOG.info("Got a message from the reconfiguration leader to end the reconfiguration");
    	endReconfiguration();
    }

    private boolean allPartitionsFinished() {
        for (ReconfigurationState state : partitionStates.values()) {
            if (state != ReconfigurationState.END)
                return false;
        }
        return true;
    }

    private void resetReconfigurationInProgress() {
        this.partitionStates.putAll(this.initialPartitionStates);
        this.currentReconfigurationPlan = null;
        this.reconfigurationLeader = -1;
        this.reconfigurationProtocol = null;
        this.reconfigurationInProgress.set(false);
    }

    /**
     * For live pull protocol move the state to Data Transfer Mode For Stop and
     * Copy, move reconfiguration into Prepare Mode
     */
    public void prepareReconfiguration() {
        if (this.reconfigurationInProgress.get()) {
            if (this.reconfigurationProtocol == ReconfigurationProtocols.LIVEPULL) {
                // Move the reconfiguration state to data transfer and data will
                // be
                // pulled based on
                // demand form the destination
                this.reconfigurationState = ReconfigurationState.DATA_TRANSFER;
            } else if (this.reconfigurationProtocol == ReconfigurationProtocols.STOPCOPY) {
                // First set the state to send control messages
                this.reconfigurationState = ReconfigurationState.PREPARE;
                this.sendPrepare(this.findDestinationSites());
            }
        }
    }

    /**
     * @param oldPartitionId
     * @param newPartitionId
     * @param table_name
     * @param vt
     * @throws Exception
     */
    public void pushTuples(int oldPartitionId, int newPartitionId, String table_name, VoltTable vt, Long minInclusive, Long maxExclusive) throws Exception {
        LOG.info(String.format("pushTuples  keys for %s  partIds %s->%s", table_name, oldPartitionId, newPartitionId));
        // TODO Auto-generated method stub
        int destinationId = this.hstore_site.getCatalogContext().getSiteIdForPartitionId(newPartitionId);

        if (destinationId == localSiteId) {
            // Just push the message through local receive Tuples to the PE'S
            receiveTuples(destinationId, System.currentTimeMillis(), oldPartitionId, newPartitionId, table_name, vt, minInclusive, maxExclusive);
            return;
        }

        ProtoRpcController controller = new ProtoRpcController();
        ByteString tableBytes = null;
        try {
            ByteBuffer b = ByteBuffer.wrap(FastSerializer.serialize(vt));
            tableBytes = ByteString.copyFrom(b.array());
        } catch (Exception ex) {
            throw new RuntimeException("Unexpected error when serializing Volt Table", ex);
        }

        DataTransferRequest dataTransferRequest = DataTransferRequest.newBuilder().setMinInclusive(minInclusive).setMaxExclusive(maxExclusive).setSenderSite(this.localSiteId)
                .setOldPartition(oldPartitionId).setNewPartition(newPartitionId).setVoltTableName(table_name).setT0S(System.currentTimeMillis()).setVoltTableData(tableBytes).build();

        this.channels[destinationId].dataTransfer(controller, dataTransferRequest, dataTransferRequestCallback);
    }

    /**
     * Receive the tuples and send it to EE through PE
     * 
     * @param partitionId
     * @param newPartitionId
     * @param table_name
     * @param vt
     * @throws Exception
     */
    public DataTransferResponse receiveTuples(int sourceId, long sentTimeStamp, int partitionId, int newPartitionId, String table_name, VoltTable vt, Long minInclusive, Long maxExclusive)
            throws Exception {
        LOG.info(String.format("receiveTuples  keys for %s  partIds %s->%s", table_name, sourceId, newPartitionId));

        if (vt == null) {
            LOG.error("Volt Table received is null");
        }

        for (PartitionExecutor executor : this.local_executors) {
            // TODO : check if we can more efficient here
            if (executor.getPartitionId() == newPartitionId) {
                // Transaction Id is not needed to be tracked for Stop and Copy
                // so just set it to 0 for now
                executor.receiveTuples(0L, partitionId, newPartitionId, table_name, minInclusive, maxExclusive, vt);
            }
        }

        DataTransferResponse response = DataTransferResponse.newBuilder().setNewPartition(newPartitionId).setOldPartition(partitionId).setT0S(sentTimeStamp).setSenderSite(sourceId)
                .setVoltTableName(table_name).setMinInclusive(minInclusive).setMaxExclusive(maxExclusive).build();

        return response;
    }

    /**
     * Call to send tuples in response to a live pull request
     * 
     * @param senderId
     * @param requestTimestamp
     * @param txnId
     * @param oldPartitionId
     * @param newPartitionId
     * @param table_name
     * @param min_inclusive
     * @param max_exclusive
     * @param livePullResponseCallback
     *            - if its null the request was from a partition on a local site
     * @return
     */
    public void sendTuples(LivePullRequest livePullRequest, RpcCallback<LivePullResponse> livePullResponseCallback) {
        LOG.info(String.format("sendTuples livePullId %s  keys %s->%s for %s  partIds %s->%s", livePullRequest.getLivePullIdentifier(), livePullRequest.getMinInclusive(), livePullRequest.getMaxExclusive(), livePullRequest.getVoltTableName(),
                livePullRequest.getOldPartition(), livePullRequest.getNewPartition()));

        VoltTable vt = null;
        for (PartitionExecutor executor : this.local_executors) {
            // TODO : check if we can be more efficient here
            if (executor.getPartitionId() == livePullRequest.getOldPartition()) {
                // Queue the live Pull request to the work queue
                // TODO : Change the input parameters for the senTuples function
                if (debug.val)
                    LOG.debug("Queue the live Pull Request");
                executor.queueLivePullRequest(livePullRequest, livePullResponseCallback);
            }
        }

        // TODO : Remove
        /*
         * ByteString tableBytes = null; try { ByteBuffer b =
         * ByteBuffer.wrap(FastSerializer.serialize(vt)); tableBytes =
         * ByteString.copyFrom(b.array()); } catch (Exception ex) { throw new
         * RuntimeException("Unexpected error when serializing Volt Table", ex);
         * } LivePullResponse livePullResponse =
         * LivePullResponse.newBuilder().setSenderSite(this.localSiteId).
         * setOldPartition(oldPartitionId).setNewPartition(newPartitionId)
         * .setVoltTableName
         * (table_name).setT0S(System.currentTimeMillis()).setVoltTableData
         * (tableBytes)
         * .setMinInclusive(min_inclusive).setMaxExclusive(max_exclusive)
         * .setTransactionID(txnId).build();
         */
        return;
    }

    /**
     * Non-blocking call to pull reconfiguration ranges. Wrapper for pullRanges
     * 
     * @param callingPartition
     * @param pullRequests
     */
    public void pullRangesNonBlocking(int livePullId, long txnId, int callingPartition, List<ReconfigurationRange<? extends Comparable<?>>> pullRequests) {
        pullRanges(livePullId, txnId, callingPartition, pullRequests, null);
    }

    /**
     * A request to pull a list of ranges. Non-null blocking sempaphore
     * indicates PE is blocked on list. Each range is an item in semaphore. Does
     * not return until calls are issued and sempaphore is acquired
     * 
     * @param callingPartition
     * @param pullRequests
     * @param blockingSemaphore
     */
    public void pullRanges(int livePullId, 
            long txnId, int callingPartition, List<ReconfigurationRange<? extends Comparable<?>>> pullRequests, Semaphore blockingSemaphore){
        try {
            blockingSemaphore.acquire(pullRequests.size());
        } catch (InterruptedException e) {
            LOG.error("Exception acquiring locks for pull request",e);
        }
        
        for(ReconfigurationRange range : pullRequests){       
            //FIXME change pullTuples to be generic comparable
            
            pullTuples(livePullId, txnId, range.old_partition, range.new_partition, range.table_name, 
                    range.min_long, range.max_long, range.getVt());
            blockedRequests.put(livePullId, blockingSemaphore);
            //LOG.error("TODO temp removing sempahore for testing");
            //blockingSemaphore.release();         
        }
    }

    /**
     * Live Pull the tuples for a reconfiguration range by generating a live
     * pull request
     * 
     * @param txnId
     * @param oldPartitionId
     * @param newPartitionId
     * @param table_name
     * @param min_inclusive
     * @param max_exclusive
     * @param voltType
     */
    public void pullTuples(int livePullId, Long txnId, int oldPartitionId, int newPartitionId, String table_name, Long min_inclusive, Long max_exclusive, VoltType voltType) {
        LOG.info(String.format("pullTuples with Live Pull ID %s, keys %s->%s for %s  partIds %s->%s", livePullId, min_inclusive, max_exclusive, table_name, oldPartitionId, newPartitionId));
        // TODO : Check if volt type makes can be used here for generic values
        // or remove it
        int sourceID = this.hstore_site.getCatalogContext().getSiteIdForPartitionId(oldPartitionId);

        ProtoRpcController controller = new ProtoRpcController();

        LivePullRequest livePullRequest = LivePullRequest.newBuilder().setLivePullIdentifier(livePullId).setSenderSite(this.localSiteId).setTransactionID(txnId).setOldPartition(oldPartitionId)
                .setNewPartition(newPartitionId).setVoltTableName(table_name).setMinInclusive(min_inclusive).setMaxExclusive(max_exclusive).setT0S(System.currentTimeMillis()).build();

        if (sourceID == localSiteId) {
            LOG.debug("pulling from localsite");
            // Just push the message through local receive Tuples to the PE'S
            // If the callback is null, it shows that the request is from a
            // partition in
            // the local site itself.
            sendTuples(livePullRequest, null);
            return;
        }

        this.channels[sourceID].livePull(controller, livePullRequest, livePullRequestCallback);
    }

    public void receiveLivePullTuples(int livePullId, Long txnId, int oldPartitionId, int newPartitionId, String table_name, Long min_inclusive, Long max_exclusive, VoltTable voltTable) {

        LOG.info(String.format("Received tuples for %s %s (%s) (from:%s to:%s) for range, " + "(from:%s to:%s)", livePullId, txnId, table_name, newPartitionId, oldPartitionId, min_inclusive,
                max_exclusive));
        for (PartitionExecutor executor : local_executors) {
            // TODO : check if we can more efficient here
            if (executor.getPartitionId() == newPartitionId) {
                try {
                    executor.receiveTuples(txnId, oldPartitionId, newPartitionId, table_name, min_inclusive, max_exclusive, voltTable);
                    // Unblock the semaphore for a blocking request
                    if (blockedRequests.containsKey(livePullId) && blockedRequests.get(livePullId) != null) {
                        LOG.info("Unblocking the PE for the pulles request " + livePullId);
                        blockedRequests.get(livePullId).release();
                    }
                } catch (Exception e) {
                    LOG.error("Error in partition executors receiving tuples for their pull " + "request", e);
                }
            }
        }

    }

    /**
     * Fire off Async pull requests in the executors 
     * This is called when state turns to Bulk Data Transfer
     */
    public void scheduleAsyncPullRequests(){
        for (PartitionExecutor executor : local_executors) {
            executor.scheduleAsyncPullRequests();
        }
    }
    
    /**
     * Parse the partition plan and figure out the destination sites and
     * populates the destination size
     * 
     * @return
     */
    public ArrayList<Integer> findDestinationSites() {
        ArrayList<Integer> destinationSites = new ArrayList<Integer>();

        // TODO : Populate the destinationSize as well

        return destinationSites;
    }

    /**
     * Send prepare messages to all destination sites for Stop and Copy
     * 
     * @param destinationHostNames
     */
    public void sendPrepare(ArrayList<Integer> destinationSites) {
        if (this.reconfigurationProtocol == ReconfigurationProtocols.STOPCOPY) {
            for (Integer destinationId : destinationSites) {
                // Send a control message to start the reconfiguration

                ProtoRpcController controller = new ProtoRpcController();
                ReconfigurationRequest reconfigurationRequest = ReconfigurationRequest.newBuilder().setSenderSite
                        (this.localSiteId).setT0S(System.currentTimeMillis()).build();

                this.channels[destinationId].reconfiguration(controller, reconfigurationRequest, this.reconfigurationRequestCallback);
            }
        }
    }

    @Override
    public void prepareShutdown(boolean error) {
        // TODO Auto-generated method stub

    }

    @Override
    public void shutdown() {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean isShuttingDown() {
        // TODO Auto-generated method stub
        return false;
    }

    /**
     * used for bulk transfer during stop and copy
     */
    public void bulkDataTransfer() {
        if (this.reconfigurationProtocol == ReconfigurationProtocols.STOPCOPY) {
            // bulk transfer the table data
            // by calling on transfer for each partition of the site
        }
    }

    public int getNextRequestId() {
        return ++rcRequestId;
    }

    /**
     * Invoked after receiving messages from reconfiguration leader signaling
     * end of reconfiguration
     */
    public void endReconfiguration() {
        this.reconfigurationInProgress.set(false);
        LOG.info("Clearing the reconfiguration state for each partition at the site");
        for (PartitionExecutor executor : this.local_executors) {
            executor.endReconfiguration();
            this.partitionStates.put(executor.getPartitionId(), ReconfigurationState.END);
        }
    }

    private final RpcCallback<ReconfigurationResponse> reconfigurationRequestCallback = new RpcCallback<ReconfigurationResponse>() {
        @Override
        public void run(ReconfigurationResponse msg) {
            int senderId = msg.getSenderSite();
            ReconfigurationCoordinator.this.destinationsReady.add(senderId);
            if (ReconfigurationCoordinator.this.reconfigurationInProgress.get() && ReconfigurationCoordinator.this.reconfigurationState == ReconfigurationState.PREPARE
                    && ReconfigurationCoordinator.this.destinationsReady.size() == ReconfigurationCoordinator.this.destinationSize) {
                ReconfigurationCoordinator.this.reconfigurationState = ReconfigurationState.DATA_TRANSFER;
                // bulk data transfer for stop and copy after each destination
                // is ready
                ReconfigurationCoordinator.this.bulkDataTransfer();
            }
        }
    };

    private final RpcCallback<DataTransferResponse> dataTransferRequestCallback = new RpcCallback<DataTransferResponse>() {
        @Override
        public void run(DataTransferResponse msg) {
            // TODO : Do the book keeping of received messages
            int senderId = msg.getSenderSite();
            int oldPartition = msg.getOldPartition();
            int newPartition = msg.getNewPartition();
            long timeStamp = msg.getT0S();
            Long minInclusive = msg.getMinInclusive();
            Long maxExlusive = msg.getMaxExclusive();
            // We can track the data Transfer progress using these fields
        }
    };

    private final RpcCallback<LivePullResponse> livePullRequestCallback = new RpcCallback<LivePullResponse>() {
        @Override
        public void run(LivePullResponse msg) {
            // TODO : Do the book keeping of received messages
            int senderId = msg.getSenderSite();
            long timeStamp = msg.getT0S();
            long txnId = msg.getT0S();
            long pullId = msg.getLivePullIdentifier();
            LOG.info("Received senderId " + senderId + " timestamp " + timeStamp + " Transaction Id " + txnId + " Pull ID:"+pullId);
            VoltTable vt = null;
            try {
                vt = FastDeserializer.deserialize(msg.getVoltTableData().toByteArray(), VoltTable.class);
            } catch (IOException e) {
                LOG.error("Error in deserializing volt table");
            }
            // TODO : change the log status later. Info for testing.
            // LOG.info("Volt table Received in callback is "+vt.toString());

            receiveLivePullTuples(msg.getLivePullIdentifier(), msg.getTransactionID(), msg.getOldPartition(), msg.getNewPartition(), msg.getVoltTableName(), msg.getMinInclusive(),
                    msg.getMaxExclusive(), vt);
            
            // send Acknowledgement 

            ReconfigurationControlRequest acknowledgingCallback = ReconfigurationControlRequest.newBuilder().setSrcPartition(msg.getOldPartition())
                    .setDestPartition(msg.getNewPartition())
                    .setReconfigControlType(ReconfigurationControlType.PULL_RECEIVED)
                    .setReceiverSite(msg.getSenderSite())
                    .setMessageIdentifier(msg.getLivePullIdentifier()).
                    setSenderSite(localSiteId).build();
            
            //TODO : Can we get away with creating an instance each time
            ProtoRpcController controller = new ProtoRpcController();
            channels[msg.getSenderSite()].reconfigurationControlMsg(controller, acknowledgingCallback, null);
        }
    };

    /**
     * Deletes the tuples associated with the live Pull Id of the request processed before
     * @param request
     */
    public void deleteTuples(ReconfigurationControlRequest request){
        LOG.info("Acknowledgement received by source of the tuple in the specified pull request," +
        		" hence we can delete the associated tuples");
        for (PartitionExecutor executor : local_executors) {
            if(request.getSrcPartition() == executor.getPartitionId()){
                LOG.info("Partition Id is "+ executor.getPartitionId()+" Getting EE to delete the tuples");
                if(executor.getExecutionEngine() != null){
                    executor.getExecutionEngine().updateExtractRequest(request.getMessageIdentifier(), true);
                } else {
                    LOG.error("EE seems to be null here");
                }
                break;
            }
        }
    }
    
    public ReconfigurationState getState() {
        return this.reconfigurationState;
    }

    public Integer getReconfigurationLeader() {
        return this.reconfigurationLeader;
    }

    public ReconfigurationProtocols getReconfigurationProtocol() {
        return this.reconfigurationProtocol;
    }

    public String getCurrentPartitionPlan() {
        return this.currentPartitionPlan;
    }

    public boolean getReconfigurationInProgress() {
        return this.reconfigurationInProgress.get();
    }

    public void notifyAllRanges(int partitionId, ExceptionTypes allRangesMigratedType) {
        LOG.info(" ** NOTIFY " + partitionId + " " + allRangesMigratedType);
        if( allRangesMigratedType == ExceptionTypes.ALL_RANGES_MIGRATED_OUT){
            finishReconfiguration(partitionId);
        }
    }

    public boolean scheduleAsyncPush() {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean scheduleAsyncPull() {
        // TODO 06/18/2013- False for testing its priority, revert later
        return true;
    }

    public void showReconfigurationProfiler() {
        if(hstore_conf.site.reconfiguration_profiling) {
            for (int p_id : hstore_site.getLocalPartitionIds().values()) {
                LOG.info("Showing reconfig stats");
                LOG.info(this.profilers[p_id].toString());
                LOG.info(String.format("Avg demand pull Time MS %s Count:%s ",
                        this.profilers[p_id].on_demand_pull_time.getAverageThinkTimeMS(),
                        this.profilers[p_id].on_demand_pull_time.getInvocations()));
    
                LOG.info(String.format("Avg async pull Time MS %s Count:%s ",
                        this.profilers[p_id].async_pull_time.getAverageThinkTimeMS(),
                        this.profilers[p_id].async_pull_time.getInvocations()));
                
                
                LOG.info(String.format("Avg live pull response queue Time MS %s Count:%s ",
                        this.profilers[p_id].on_demand_pull_response_queue.getAverageThinkTimeMS(),
                        this.profilers[p_id].on_demand_pull_response_queue.getInvocations()));
                
            }
        }
    }

}

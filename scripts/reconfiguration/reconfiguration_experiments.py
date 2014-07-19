RECONFIG_EXPERIMENTS = [
    "reconfig-baseline",
    "reconfig-wh-baseline-2",
    "reconfig-wh-baseline-3",
    "reconfig-wh-baseline-4",
    "reconfig-.5",
    "reconfig-1",
    "reconfig-2",
    "reconfig-2b",
    "reconfig-2l",
    "reconfig-4",
    "reconfig-10",
    "reconfig-10b",
    "stopcopy-1",
    "stopcopy-2",
    "stopcopy-2b",
    "baseline-1",
    "reconfig-localhost",
    "reconfig-tpcc-small",
    "stopcopy-tpcc-small",
    "reconfig-fast",
    "stopcopy-fast",
    "reconfig-slow",
    "reconfig-2split",
    "reconfig-dynsplit",
    "reconfig-dynsplit-becca",
    "reconfig-dynsplit-fine-grained",
]

RECONFIG_CLIENT_COUNT = 1

def updateReconfigurationExperimentEnv(fabric, args, benchmark, partitions ):
    partitions_per_site = fabric.env["hstore.partitions_per_site"]
    if args['exp_type'] == 'reconfig-baseline':
        fabric.env["client.blocking_concurrent"] = 4 # * int(partitions/8)
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = min(50, int(partitions * 4))
    
    if args['exp_type'] == 'reconfig-localhost':
        fabric.env["client.blocking_concurrent"] = 4 # * int(partitions/8)
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = 2
        fabric.env["site.reconfig_live"] = False
        fabric.env["site.reconfig_chunk_size_kb"] = 10000
        fabric.env["site.commandlog_enable"] = False
    
    if 'reconfig-wh-baseline' in args['exp_type']:
        fabric.env["client.blocking_concurrent"] = 5 # * int(partitions/8)
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = min(50, int(partitions * 4))
        fabric.env["hstore.partitions_per_site"] = args['exp_type'].rsplit("-",1)[1] 
    
    if 'reconfig-.5' in args['exp_type']:
        fabric.env["client.blocking_concurrent"] = 5 # * int(partitions/8)
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT#4
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = min(50, int(partitions * 4))
        fabric.env["site.reconfig_chunk_size_kb"] = 512
        fabric.env["site.reconfig_async_chunk_size_kb"] = 512
        fabric.env["site.commandlog_enable"] = False

    if 'reconfig-1' in args['exp_type']:
        fabric.env["client.blocking_concurrent"] = 5 # * int(partitions/8)
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = min(50, int(partitions * 4))
        fabric.env["site.reconfig_chunk_size_kb"] = 1024
        fabric.env["site.reconfig_async_chunk_size_kb"] = 1024
        fabric.env["site.commandlog_enable"] = False

    if 'reconfig-2' == args['exp_type']:
        fabric.env["client.blocking_concurrent"] = 5 # * int(partitions/8)
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = min(15, int(partitions * 4))
        fabric.env["site.reconfig_chunk_size_kb"] = 2048 
        fabric.env["site.reconfig_async_chunk_size_kb"] = 2048
        fabric.env["site.commandlog_enable"] = False
    
    if 'reconfig-2b' in  args['exp_type'] or 'stopcopy-2b' in args['exp_type']:
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking_concurrent"] = 1
        #fabric.env["client.txnrate"] = 100000
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.output_exec_profiling"] = "execprofile.csv"
        fabric.env["client.output_txn_profiling"] = "txnprofile.csv"
        fabric.env["client.output_txn_profiling_combine"] = True
        fabric.env["client.output_txn_counters"] = "txncounters.csv"
        fabric.env["client.threads_per_host"] = partitions * 2  # max(1, int(partitions/2))
        fabric.env["site.reconfig_chunk_size_kb"] = 2048 
        fabric.env["site.reconfig_async_chunk_size_kb"] = 2048
        fabric.env["site.commandlog_enable"] = False
        fabric.env["benchmark.loadthread_per_warehouse"] = False
        fabric.env["benchmark.loadthreads"] = max(16, partitions)
        fabric.env["hstore.sites_per_host"]=1
        fabric.env["hstore.sites_per_host"]=3
        
        
    if 'reconfig-dynsplit' in  args['exp_type'] or 'stopcopy-2slpit' in args['exp_type']:
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking_concurrent"] = 1
        #fabric.env["client.txnrate"] = 100000
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.output_exec_profiling"] = "execprofile.csv"
        fabric.env["client.output_txn_profiling"] = "txnprofile.csv"
        fabric.env["client.output_txn_profiling_combine"] = True
        fabric.env["client.output_txn_counters"] = "txncounters.csv"
        fabric.env["client.threads_per_host"] = partitions * 2  # max(1, int(partitions/2))
        fabric.env["site.commandlog_enable"] = False
        fabric.env["benchmark.loadthread_per_warehouse"] = False
        fabric.env["benchmark.loadthreads"] = max(16, partitions)        
        fabric.env["partitionplan"]="tpcc-plan-warehouse-part.pplan" #"tpcc-plan.pplan"
        fabric.env["hstore.partitions_per_site"]=3
        fabric.env["hstore.sites_per_host"]=1

    if 'reconfig-dynsplit-becca' in  args['exp_type'] or 'stopcopy-2slpit' in args['exp_type']:
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking_concurrent"] = 1
        #fabric.env["client.txnrate"] = 100000
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.output_exec_profiling"] = "execprofile.csv"
        fabric.env["client.output_txn_profiling"] = "txnprofile.csv"
        fabric.env["client.output_txn_profiling_combine"] = True
        fabric.env["client.output_txn_counters"] = "txncounters.csv"
        fabric.env["client.threads_per_host"] = 10
        fabric.env["site.commandlog_enable"] = False
        fabric.env["benchmark.loadthread_per_warehouse"] = False
        fabric.env["benchmark.loadthreads"] = max(16, partitions)        
        fabric.env["partitionplan"]="tpcc-plan-warehouse-part.pplan" #"tpcc-plan.pplan"
        fabric.env["hstore.partitions_per_site"]=3
        fabric.env["hstore.sites_per_host"]=1

    if 'reconfig-dynsplit-fine-grained' in  args['exp_type'] or 'stopcopy-2slpit' in args['exp_type']:
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking_concurrent"] = 1
        #fabric.env["client.txnrate"] = 100000
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.output_exec_profiling"] = "execprofile.csv"
        fabric.env["client.output_txn_profiling"] = "txnprofile.csv"
        fabric.env["client.output_txn_profiling_combine"] = True
        fabric.env["client.output_txn_counters"] = "txncounters.csv"
        fabric.env["client.threads_per_host"] = 10
        fabric.env["site.commandlog_enable"] = False
        fabric.env["benchmark.loadthread_per_warehouse"] = False
        fabric.env["benchmark.loadthreads"] = max(16, partitions)        
        fabric.env["partitionplan"]="tpcc-plan-fine-grained.pplan" #"tpcc-plan.pplan"
        fabric.env["hstore.partitions_per_site"]=2
        #fabric.env["site.status_enable"]=True
        #fabric.env["site.status_interval"]=5000
        #fabric.env["site.status_exec_info"]=False
        #fabric.env["site.status_thread_info"]=True
        fabric.env["hstore.sites_per_host"]=2


    if 'reconfig-10b' in  args['exp_type'] or 'stopcopy-10b' in args['exp_type']:
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking_concurrent"] = 1
        #fabric.env["client.txnrate"] = 100000
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.output_exec_profiling"] = "execprofile.csv"
        fabric.env["client.output_txn_profiling"] = "txnprofile.csv"
        fabric.env["client.output_txn_profiling_combine"] = True
        fabric.env["client.output_txn_counters"] = "txncounters.csv"
        fabric.env["client.threads_per_host"] = partitions * 2  # max(1, int(partitions/2))
        fabric.env["site.reconfig_chunk_size_kb"] = 10000 
        fabric.env["site.reconfig_async_chunk_size_kb"] = 10000
        fabric.env["site.commandlog_enable"] = False
        fabric.env["benchmark.loadthread_per_warehouse"] = False
        fabric.env["benchmark.loadthreads"] = max(16, partitions)
     
    if 'reconfig-fast' in  args['exp_type'] or 'stopcopy-fast' in args['exp_type']:
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking_concurrent"] = 4
        #fabric.env["client.txnrate"] = 100000
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.output_exec_profiling"] = "execprofile.csv"
        fabric.env["client.output_txn_profiling"] = "txnprofile.csv"
        fabric.env["client.output_txn_profiling_combine"] = True
        fabric.env["client.output_txn_counters"] = "txncounters.csv"
        fabric.env["client.threads_per_host"] = partitions * 3  # max(1, int(partitions/2))
        fabric.env["site.reconfig_chunk_size_kb"] = 2048 
        fabric.env["site.reconfig_async_chunk_size_kb"] = 2048
        fabric.env["site.commandlog_enable"] = False

    
    if 'reconfig-2l' in args['exp_type']:
        fabric.env["client.blocking_concurrent"] = 5 # * int(partitions/8)
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = min(50, int(partitions * 4))
        fabric.env["site.reconfig_chunk_size_kb"] = 4096 
        fabric.env["site.reconfig_async_chunk_size_kb"] = 2048
        fabric.env["site.commandlog_enable"] = False

    if 'reconfig-4' in args['exp_type']:
        fabric.env["client.blocking_concurrent"] = 5 # * int(partitions/8)
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = min(50, int(partitions * 4))
        fabric.env["site.commandlog_enable"] = False
        fabric.env["site.reconfig_chunk_size_kb"] = 4096
        fabric.env["site.reconfig_async_chunk_size_kb"] = 4096

    if 'reconfig-10' in args['exp_type']:
        fabric.env["client.blocking_concurrent"] = 5 # * int(partitions/8)
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = min(50, int(partitions * 4))
        fabric.env["site.commandlog_enable"] = False
        fabric.env["site.reconfig_chunk_size_kb"] = 10000
        fabric.env["site.reconfig_async_chunk_size_kb"] = 10000


    if 'stopcopy-1' == args['exp_type']:
        fabric.env["client.blocking_concurrent"] = 5 # * int(partitions/8)
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = min(50, int(partitions * 4))
        fabric.env["site.reconfig_chunk_size_kb"] = 30000
        fabric.env["site.reconfig_async_chunk_size_kb"] = 30000

    if 'stopcopy-2' == args['exp_type']:
        fabric.env["client.blocking_concurrent"] = 5 # * int(partitions/8)
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = min(15, int(partitions * 4))
        fabric.env["site.reconfig_chunk_size_kb"] = 30000 
        fabric.env["site.reconfig_async_chunk_size_kb"] = 30000 
        fabric.env["site.commandlog_enable"] = False

    if 'baseline-1' in args['exp_type']:
        fabric.env["client.blocking_concurrent"] = 5 # * int(partitions/8)
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = min(50, int(partitions * 4))	    
        args["reconfig"] = None

    if 'reconfig-tpcc-small' in  args['exp_type'] or 'stopcopy-tpcc-small' in args['exp_type']:
        fabric.env["client.count"] = RECONFIG_CLIENT_COUNT
        fabric.env["client.blocking_concurrent"] = 1
        #fabric.env["client.txnrate"] = 100
        fabric.env["client.blocking"] = True
        fabric.env["client.output_exec_profiling"] = "execprofile.csv"
        fabric.env["client.output_txn_profiling"] = "txnprofile.csv"
        fabric.env["client.output_txn_profiling_combine"] = True
        fabric.env["client.output_txn_counters"] = "txncounters.csv"
        fabric.env["client.threads_per_host"] = 50 #partitions * 2  # max(1, int(partitions/2))
        fabric.env["client.txnrate"] = 10
        fabric.env["site.reconfig_chunk_size_kb"] = 20048
        fabric.env["site.reconfig_async_chunk_size_kb"] = 2048
        fabric.env["site.commandlog_enable"] = False
        fabric.env["benchmark.neworder_multip"] = False
        fabric.env["benchmark.payment_multip"] = False

    if 'reconfig-slow' == args['exp_type']:
        fabric.env["client.blocking_concurrent"] = 1 # * int(partitions/8)
        fabric.env["client.count"] = 1
        fabric.env["client.blocking"] = True
        fabric.env["client.output_response_status"] = True
        fabric.env["client.threads_per_host"] = 2
        fabric.env["site.reconfig_chunk_size_kb"] = 2048 
        fabric.env["site.reconfig_async_chunk_size_kb"] = 2048
        fabric.env["site.commandlog_enable"] = False
        fabric.env["client.txnrate"] = 100
        #fabric.env["site.reconfig_async"] = False
        fabric.env["benchmark.loadthreads"] = 1
        fabric.env["benchmark.requestdistribution"] = "uniform"


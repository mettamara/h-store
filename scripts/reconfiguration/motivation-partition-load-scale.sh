#!/bin/bash

# ---------------------------------------------------------------------

trap onexit 1 2 3 15
function onexit() {
    local exit_status=${1:-$?}
    exit $exit_status
}

# ---------------------------------------------------------------------

DATA_DIR="out"
FABRIC_TYPE="ssh"
FIRST_PARAM_OFFSET=1

EXP_TYPES=( \
    "reconfig-motivation  --global.hasher_plan=scripts/reconfiguration/plans/tpcc-reconfig-motivation-p4w4.json --partitions=4  --results-dir=${DATA_DIR} --exp-suffix p4w4" \
    "reconfig-motivation --global.hasher_plan=scripts/reconfiguration/plans/tpcc-reconfig-motivation-p4w8.json --partitions=4  --results-dir=${DATA_DIR} --exp-suffix p4w8" \
    "reconfig-motivation --global.hasher_plan=scripts/reconfiguration/plans/tpcc-reconfig-motivation-p4w12.json --partitions=4  --results-dir=${DATA_DIR} --exp-suffix p4w12" \
    "reconfig-motivation --global.hasher_plan=scripts/reconfiguration/plans/tpcc-reconfig-motivation-p4w16.json --partitions=4  --results-dir=${DATA_DIR} --exp-suffix p4w16" \
    "reconfig-motivation --global.hasher_plan=scripts/reconfiguration/plans/tpcc-reconfig-motivation-p4w24.json --partitions=4  --results-dir=${DATA_DIR} --exp-suffix p4w24" \
    "reconfig-motivation --global.hasher_plan=scripts/reconfiguration/plans/tpcc-reconfig-motivation-p4w32.json --partitions=4  --results-dir=${DATA_DIR} --exp-suffix p4w32" \
)

#for b in smallbank tpcc seats; do
for b in tpcc; do
# for b in seats; do
    PARAMS=( \
        --benchmark=$b \
        --stop-on-error \
        --exp-trials=1 \
        --exp-attempts=1 \        
        --no-json \
        --client.interval=20000 \
        --client.output_interval=false \
        --client.duration=600000 \
        --client.warmup=200000 \
	--overwrite \
        --client.output_results_csv=interval_res.csv
    )
    
    i=0
    cnt=${#EXP_TYPES[@]}
    while [ "$i" -lt "$cnt" ]; do
        ./experiment-runner.py $FABRIC_TYPE ${PARAMS[@]:$FIRST_PARAM_OFFSET} \
            --exp-type=${EXP_TYPES[$i]}
        FIRST_PARAM_OFFSET=0
        i=`expr $i + 1`
    done

done

package edu.brown.profilers;

public class ReconfigurationProfiler extends AbstractProfiler {

    public ReconfigurationProfiler() {
        // TODO Auto-generated constructor stub
    }
    
    public ProfileMeasurement on_demand_pull_time= new ProfileMeasurement("DEMAND_PULL");
    public ProfileMeasurement async_pull_time = new ProfileMeasurement("ASYNC_PULL");

    public ProfileMeasurement on_demand_pull_response_queue = new ProfileMeasurement("DEMAN_PULL_RESPONSE_QUEUE");
}
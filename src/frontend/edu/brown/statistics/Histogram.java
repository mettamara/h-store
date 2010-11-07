package edu.brown.statistics;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.Map.Entry;

import org.apache.log4j.Logger;
import org.json.*;
import org.voltdb.VoltType;
import org.voltdb.VoltTypeException;
import org.voltdb.catalog.Database;
import org.voltdb.utils.VoltTypeUtil;

import edu.brown.utils.JSONSerializable;
import edu.brown.utils.JSONUtil;

/**
 * This class provides a way to visualize the variation in use of a variable.
 * 
 * @author svelagap
 *
 */
public class Histogram implements JSONSerializable {
    private static final Logger LOG = Logger.getLogger(Histogram.class);
    
    public static final String DELIMITER = "\t";
    public static final String MARKER = "*";
    public static final Integer MAX_CHARS = 80;
    public static final Integer MAX_VALUE_LENGTH = 20;
    
    public enum Members {
        VALUE_TYPE,
        HISTOGRAM,
        NUM_SAMPLES,
        MIN_VALUE,
        MIN_COUNT,
        MIN_COUNT_VALUE,
        MAX_VALUE,
        MAX_COUNT,
        MAX_COUNT_VALUE
    }
    
    protected VoltType value_type = VoltType.INVALID;
    protected final SortedMap<Object, Long> histogram = new TreeMap<Object, Long>();
    protected long num_samples = 0;
    
    /**
     * 
     */
    protected final transient Map<Object, String> debug_names = new HashMap<Object, String>(); 
    
    /**
     * The Min/Max values are the smallest/greatest values we have seen based
     * on some natural ordering
     */
    protected Comparable<Object> min_value;
    protected Comparable<Object> max_value;
    
    /**
     * The Min/Max counts are the values that have the smallest/greatest number of
     * occurences in the histogram
     */
    protected long min_count = 0;
    protected Object min_count_value = null;
    protected long max_count = 0;
    protected Object max_count_value = null;
    
    /**
     * A switchable flag that determines whether non-zero entries are kept or removed
     */
    private transient boolean keep_zero_entries = false;
    
    /**
     * Constructor
     */
    public Histogram() {
        // Nothing...
    }
    
    /**
     * Copy Constructor
     * @param other
     */
    public Histogram(Histogram other) {
        assert(other != null);
        this.putHistogram(other);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Histogram) {
            Histogram other = (Histogram)obj;
            return (this.histogram.equals(other.histogram));
        }
        return (false);
    }
    
    /**
     * Helper method used for replacing the object's toString() output with labels
     * @param names_map
     */
    public Histogram setDebugLabels(Map<Object, String> names_map) {
        this.debug_names.putAll(names_map);
        return (this);
    }
    public boolean hasDebugLabels() {
        return (!this.debug_names.isEmpty());
    }
    

    /**
     * Set whether this histogram is allowed to retain zero count entries
     * If the flag switches from true to false, then all zero count entries will be removed
     * Default is false
     * @param flag
     */
    public void setKeepZeroEntries(boolean flag) {
        // When this option is disabled, we need to remove all of the zeroed entries
        if (!flag && this.keep_zero_entries) {
            synchronized (this) {
                Iterator<Object> it = this.histogram.keySet().iterator();
                long ctr = 0;
                while (it.hasNext()) {
                    Object key = it.next();
                    if (this.histogram.get(key) == 0) {
                        it.remove();
                        ctr++;
                    }
                } // WHILE
                if (ctr > 0) {
                    LOG.debug("Removed " + ctr + " zero entries from histogram");
                    this.calculateInternalValues();
                }
            } // SYNCHRONIZED
        }
        this.keep_zero_entries = flag;
    }
    
    public boolean isZeroEntriesEnabled() {
        return this.keep_zero_entries;
    }
    
    /**
     * The main method that updates a value in the histogram with a given sample count
     * This should be called by one of the public interface methods that are synchronized
     * This method is not synchronized on purpose for performance
     * @param value
     * @param count
     */
    @SuppressWarnings("unchecked")
    private void _put(Object value, long count) {
        if (value == null) return;
        if (this.value_type == VoltType.INVALID) {
            try {
                this.value_type = VoltType.typeFromClass(value.getClass());
            } catch (VoltTypeException ex) {
                this.value_type = VoltType.NULL;
            }
        }
        
        this.num_samples += count;
        
        // If we already have this value in our histogram, then add the new count
        // to its existing total
        if (this.histogram.containsKey(value)) {
            count += this.histogram.get(value);
        } else if (this.histogram.isEmpty()) {
            this.min_count = count;
            this.min_count_value = value;
            this.max_count = count;
            this.max_count_value = value;
        }
        assert(count >= 0) : "Invalid negative count for '" + value + "' [count=" + count + "]";
        // If the new count is zero, then completely remove it if we're not allowed to have zero entries
        if (count == 0 && !this.keep_zero_entries) {
            this.histogram.remove(value);
        } else {
            this.histogram.put(value, count);
        }
            
        // Is this value the new min/max values?
        if (this.min_value == null || this.min_value.compareTo(value) > 0) {
            this.min_value = (Comparable<Object>)value;
        } else if (this.max_value == null || this.max_value.compareTo(value) < 0) {
            this.max_value = (Comparable<Object>)value;
        }
    }

    /**
     * 
     */
    private void calculateInternalValues() {
        // New Min/Max Counts
        // The reason we have to loop through and check every time is that our 
        // value may be the current min/max count and thus it may or may not still
        // be after the count is changed
        this.max_count = 0;
        this.min_count = Integer.MAX_VALUE;
        for (Entry<Object, Long> e : this.histogram.entrySet()) {
            if (e.getValue() < this.min_count) {
                this.min_count_value = e.getKey();
                this.min_count = e.getValue();
            }
            if (e.getValue() > this.max_count) {
                this.max_count_value = e.getKey();
                this.max_count = e.getValue();
            }
        } // FOR
    }
    
    
    /**
     * Get the number of samples entered into the histogram using the put methods
     * @return
     */
    public long getSampleCount() {
        return (this.num_samples);
    }
    /**
     * Get the number of unique values entered into the histogram 
     * @return
     */
    public int getValueCount() {
        return (this.histogram.values().size());
    }
    
    /**
     * Get the smallest value entered into the histogram
     * This assumes that the values implement the Comparable interface
     * @return
     */
    public Object getMinValue() {
        return (this.min_value);
    }
    /**
     * Get the largest value entered into the histogram
     * This assumes that the values implement the Comparable interface
     * @return
     */
    public Object getMaxValue() {
        return (this.max_value);
    }

    /**
     * Return the number of samples for the value with the smallest number of samples in the histogram
     * @return
     */
    public long getMinCount() {
        return (this.min_count);
    }
    /**
     * Return the value with the smallest number of samples
     * TODO: There might be more than one value with the samples. This return a set
     * @return
     */
    @SuppressWarnings("unchecked")
    public <T> T getMinCountValue() {
        return ((T)this.min_count_value);
    }
    /**
     * Return the number of samples for the value with the greatest number of samples in the histogram
     * @return
     */
    public long getMaxCount() {
        return (this.max_count);
    }
    /**
     * Return the value with the greatest number of samples
     * TODO: There might be more than one value with the samples. This return a set
     * @return
     */
    @SuppressWarnings("unchecked")
    public <T> T getMaxCountValue() {
        return ((T)this.max_count_value);
    }

    /**
     * Return all the values stored in the histogram
     * @return
     */
    @SuppressWarnings("unchecked")
    public <T> Set<T> values() {
        return (Collections.unmodifiableSet((Set<T>)this.histogram.keySet()));
    }
    
    /**
     * Returns the list of values sorted in descending order by cardinality
     * @return
     */
    public SortedSet<Object> sortedValues() {
        SortedSet<Object> sorted = new TreeSet<Object>(new Comparator<Object>() {
            public int compare(final Object item0, final Object item1) {
                final Long v0 = Histogram.this.get(item0);
                final Long v1 = Histogram.this.get(item1);
                if (v0.equals(v1)) return (-1);
                return (v1.compareTo(v0));
              }
        });
        sorted.addAll(this.histogram.keySet());
        return (sorted);
    }
    
    /**
     * Return the set of values from the histogram that have the matching count in the histogram
     * @param <T>
     * @param count
     * @return
     */
    @SuppressWarnings("unchecked")
    public <T> Set<T> getValuesForCount(long count) {
        Set<T> ret = new HashSet<T>();
        for (Entry<Object, Long> e : this.histogram.entrySet()) {
            if (e.getValue() == count) ret.add((T)e.getKey());
        } // FOR
        return (ret);
    }
    
    /**
     * Reset the histogram's internal data
     */
    public void clear() {
        this.histogram.clear();
        this.num_samples = 0;
        this.min_count = 0;
        this.min_count_value = null;
        this.min_value = null;
        this.max_count = 0;
        this.max_count_value = null;
        this.max_value = null;
        assert(this.histogram.isEmpty());
    }
    
    /**
     * 
     * @return
     */
    public boolean isEmpty() {
        return (this.histogram.isEmpty());
    }
    
    public boolean isSkewed(double skewindication) {
        return (this.getStandardDeviation() > skewindication);
    }
    
    /**
     * Increments the number of occurrences of this particular value i
     * @param value the value to be added to the histogram
     * 
     */
    public synchronized void put(Object value, long i) {
        this._put(value, i);
        this.calculateInternalValues();
    }
    
    /**
     * Increments the number of occurrences of this particular value i
     * @param value the value to be added to the histogram
     * 
     */
    public synchronized void put(Object value) {
        this._put(value, 1);
        this.calculateInternalValues();
    }
    
    /**
     * Increment multiple values
     * @param <T>
     * @param values
     */
    public <T> void putValues(Collection<T> values) {
        this.putValues(values, 1);
    }
    
    /**
     * Increment multiple values by the given count
     * @param <T>
     * @param values
     * @param count
     */
    public synchronized <T> void putValues(Collection<T> values, long count) {
        for (T v : values) {
            this._put(v, count);
        } // FOR
        this.calculateInternalValues();
    }
    
    /**
     * Add all the entries from the provided Histogram into this objects totals
     * @param other
     */
    public synchronized void putHistogram(Histogram other) {
        for (Entry<Object, Long> e : other.histogram.entrySet()) {
            if (e.getValue() > 0) this._put(e.getKey(), e.getValue());
        } // FOR
        this.calculateInternalValues();
    }
    
    /**
     * Remove the given count from the total of the value
     * @param value
     * @param count
     */
    public synchronized void remove(Object value, long count) {
        assert(this.histogram.containsKey(value));
        this._put(value, count * -1);
        this.calculateInternalValues();
    }
    
    /**
     * Decrement the count for the given value by one in the histogram
     * @param value
     */
    public synchronized void remove(Object value) {
        this._put(value, -1);
        this.calculateInternalValues();
    }
    
    /**
     * Remove the entrie count for the given value
     * @param value
     */
    public synchronized void removeAll(Object value) {
        long cnt = this.histogram.get(value);
        if (cnt > 0) {
            this._put(value, cnt * -1);
            this.calculateInternalValues();
        }
    }
    
    /**
     * For each value in the given collection, decrement their count by one for each
     * @param <T>
     * @param values
     */
    public synchronized <T> void removeValues(Collection<T> values) {
        for (T v : values) {
            this._put(v, -1);
        } // FOR
        this.calculateInternalValues();
    }
    
    /**
     * Decrement all the entries in the other histogram by their counter
     * @param <T>
     * @param values
     */
    public synchronized void removeHistogram(Histogram other) {
        for (Entry<Object, Long> e : other.histogram.entrySet()) {
            if (e.getValue() > 0) this._put(e.getKey(), -1 * e.getValue());
        } // FOR
        this.calculateInternalValues();
    }

    /**
     * Returns the current count for the given value
     * If the value was never entered into the histogram, then the count will be 0
     * @param value
     * @return
     */
    public Long get(Object value) {
        Long count = histogram.get(value); 
        return (count); //  == null ? 0 : count);
    }
    
    public long get(Object value, long if_null) {
        Long count = histogram.get(value);
        return (count == null ? if_null : count);
    }
    
    /**
     * Returns true if this histogram contains the specified key.
     * @param value
     * @return
     */
    public boolean contains(Object value) {
        return (this.histogram.containsKey(value));
    }
    
    /**
     * 
     * @return the standard deviation of the number of occurrences of each value
     * so for a histogram:
     * 4 *
     * 5 **
     * 6 ****
     * 7 *******
     * It would give the mean:(1+2+4+7)/4 = 3.5 and deviations:
     * 4 6.25
     * 5 2.25
     * 6 0.25
     * 7 12.5
     * Giving us a standard deviation of (drum roll):
     * 2.3
     */
    public double getStandardDeviation() {
        double average = getMeanOfOccurences();
        double[] deviance = new double[histogram.values().size()];
        int index = 0;
        double sumdeviance = 0;
        for(long i : histogram.values()) {
            deviance[index] = Math.pow(i*1.0-average,2);
            sumdeviance += deviance[index];
            index++;
        }
        return (Math.sqrt(sumdeviance/deviance.length));
    }
    /**
     * 
     * @return The mean of the occurrences of the particular histogram.
     */
    private double getMeanOfOccurences() {
        int sum = 0;
        for(long i : this.histogram.values()) {
            sum += i;
        }
        return (sum / (double)this.histogram.values().size());
    }
    /**
     * @return Uses the following template for the visualization of a histogram:
     * 4 *
     * 5 **
     * 6 ****
     * 7 *******
     * 
    */
    public String toString() {
        return (this.toString(MAX_CHARS, MAX_VALUE_LENGTH));
    }
    
    public String toString(Integer max_chars, Integer max_length) {
        StringBuilder s = new StringBuilder();
        
        // Don't let anything go longer than MAX_VALUE_LENGTH chars
        String f = "%-" + max_length + "s [%5d] ";
        boolean first = true;
        boolean has_labels = this.hasDebugLabels();
        for (Object value : this.histogram.keySet()) {
            if (!first) s.append("\n");
            String str = null;
            if (has_labels) str = this.debug_names.get(value);
            if (str == null) str = value.toString();
            int value_str_len = str.length();
            if (value_str_len > max_length) str = str.substring(0, max_length - 3) + "...";
            
            long cnt = this.histogram.get(value);
            int chars = (int)((cnt / (double)this.max_count) * max_chars);
            s.append(String.format(f, str, cnt));
            for (int i = 0; i < chars; i++) s.append(MARKER);
            first = false;
        } // FOR
        if (this.histogram.isEmpty()) s.append("<EMPTY>");
        return (s.toString());
    }
    
    // ----------------------------------------------------------------------------
    // SERIALIZATION METHODS
    // ----------------------------------------------------------------------------

    @Override
    public void load(String input_path, Database catalog_db) throws IOException {
        JSONUtil.load(this, catalog_db, input_path);
    }
    
    @Override
    public void save(String output_path) throws IOException {
        JSONUtil.save(this, output_path);
    }
    
    @Override
    public String toJSONString() {
        return (JSONUtil.toJSONString(this));
    }
    
    @Override
    public void toJSON(JSONStringer stringer) throws JSONException {
        for (Members element : Histogram.Members.values()) {
            try {
                Field field = Histogram.class.getDeclaredField(element.toString().toLowerCase());
                if (element == Members.HISTOGRAM) {
                    stringer.key(Members.HISTOGRAM.name()).object();
                    for (Object value : this.histogram.keySet()) {
                        stringer.key(value.toString()).value(this.histogram.get(value));
                    } // FOR
                    stringer.endObject();
                } else {
                    stringer.key(element.name()).value(field.get(this));
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                System.exit(1);
            }
        } // FOR
    }
    
    @Override
    public void fromJSON(JSONObject object, Database catalog_db) throws JSONException {
        this.value_type = VoltType.typeFromString(object.get(Members.VALUE_TYPE.name()).toString());
        assert(this.value_type != null);
        
        for (Members element : Histogram.Members.values()) {
            if (element == Members.VALUE_TYPE) continue;
            try {
                String field_name = element.toString().toLowerCase();
                Field field = Histogram.class.getDeclaredField(field_name);
                if (element == Members.HISTOGRAM) {
                    JSONObject jsonObject = object.getJSONObject(Members.HISTOGRAM.name());
                    Iterator<String> keys = jsonObject.keys();
                    while (keys.hasNext()) {
                        String key_name = keys.next();
                        Object key_value = VoltTypeUtil.getObjectFromString(this.value_type, key_name);
                        Long count = jsonObject.getLong(key_name);
                        this.histogram.put(key_value, count);
                    } // WHILE
                } else if (field_name.endsWith("_value")) {
                    if (object.isNull(element.name())) {
                        field.set(this, null);
                    } else {
                        Object value = object.get(element.name());
                        field.set(this, VoltTypeUtil.getObjectFromString(this.value_type, value.toString()));
                    }
                } else {
                    field.set(this, object.getLong(element.name()));
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                System.exit(1);
            }
        } // FOR
    }
}

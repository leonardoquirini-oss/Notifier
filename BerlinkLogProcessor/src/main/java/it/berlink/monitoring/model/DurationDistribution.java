package it.berlink.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Distribution of query execution durations across predefined buckets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DurationDistribution {

    private long under10ms;
    private long from10to50ms;
    private long from50to100ms;
    private long from100to500ms;
    private long over500ms;
    private long totalExecutions;
}

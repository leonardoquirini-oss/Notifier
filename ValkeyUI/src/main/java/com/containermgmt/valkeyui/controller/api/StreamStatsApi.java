package com.containermgmt.valkeyui.controller.api;

import com.containermgmt.valkeyui.model.TimeBucket;
import com.containermgmt.valkeyui.model.TimeSeriesPoint;
import com.containermgmt.valkeyui.service.StreamStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/streams/{key}")
@RequiredArgsConstructor
public class StreamStatsApi {

    private final StreamStatsService statsService;

    @GetMapping("/stats")
    public List<TimeBucket> stats(@PathVariable String key,
                                  @RequestParam(value = "bucket", defaultValue = "minute") String bucket,
                                  @RequestParam(value = "range", defaultValue = "24h") String range) {
        return statsService.messagesPerBucket(key, bucket, range);
    }

    @GetMapping("/heatmap")
    public long[][] heatmap(@PathVariable String key) {
        return statsService.heatmapWeekly(key);
    }

    @GetMapping("/timeseries")
    public List<TimeSeriesPoint> timeseries(@PathVariable String key,
                                            @RequestParam(value = "metric", defaultValue = "length") String metric,
                                            @RequestParam(value = "range", defaultValue = "24h") String range) {
        return statsService.timeseries(key, metric, range);
    }

}
